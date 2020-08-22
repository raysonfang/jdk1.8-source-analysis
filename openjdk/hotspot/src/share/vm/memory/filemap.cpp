/*
 * Copyright (c) 2003, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#include "precompiled.hpp"
#include "classfile/classLoader.hpp"
#include "classfile/symbolTable.hpp"
#include "classfile/altHashing.hpp"
#include "memory/filemap.hpp"
#include "runtime/arguments.hpp"
#include "runtime/java.hpp"
#include "runtime/os.hpp"
#include "services/memTracker.hpp"
#include "utilities/defaultStream.hpp"

# include <sys/stat.h>
# include <errno.h>

#ifndef O_BINARY       // if defined (Win32) use binary files.
#define O_BINARY 0     // otherwise do nothing.
#endif


extern address JVM_FunctionAtStart();
extern address JVM_FunctionAtEnd();

// Complain and stop. All error conditions occurring during the writing of
// an archive file should stop the process.  Unrecoverable errors during
// the reading of the archive file should stop the process.

static void fail(const char *msg, va_list ap) {
  // This occurs very early during initialization: tty is not initialized.
  jio_fprintf(defaultStream::error_stream(),
              "An error has occurred while processing the"
              " shared archive file.\n");
  jio_vfprintf(defaultStream::error_stream(), msg, ap);
  jio_fprintf(defaultStream::error_stream(), "\n");
  // Do not change the text of the below message because some tests check for it.
  vm_exit_during_initialization("Unable to use shared archive.", NULL);
}


void FileMapInfo::fail_stop(const char *msg, ...) {
        va_list ap;
  va_start(ap, msg);
  fail(msg, ap);        // Never returns.
  va_end(ap);           // for completeness.
}


// Complain and continue.  Recoverable errors during the reading of the
// archive file may continue (with sharing disabled).
//
// If we continue, then disable shared spaces and close the file.

void FileMapInfo::fail_continue(const char *msg, ...) {
  va_list ap;
  va_start(ap, msg);
  if (RequireSharedSpaces) {
    fail(msg, ap);
  }
  va_end(ap);
  UseSharedSpaces = false;
  close();
}

// Fill in the fileMapInfo structure with data about this VM instance.

// This method copies the vm version info into header_version.  If the version is too
// long then a truncated version, which has a hash code appended to it, is copied.
//
// Using a template enables this method to verify that header_version is an array of
// length JVM_IDENT_MAX.  This ensures that the code that writes to the CDS file and
// the code that reads the CDS file will both use the same size buffer.  Hence, will
// use identical truncation.  This is necessary for matching of truncated versions.
template <int N> static void get_header_version(char (&header_version) [N]) {
  assert(N == JVM_IDENT_MAX, "Bad header_version size");

  const char *vm_version = VM_Version::internal_vm_info_string();
  const int version_len = (int)strlen(vm_version);

  if (version_len < (JVM_IDENT_MAX-1)) {
    strcpy(header_version, vm_version);

  } else {
    // Get the hash value.  Use a static seed because the hash needs to return the same
    // value over multiple jvm invocations.
    unsigned int hash = AltHashing::murmur3_32(8191, (const jbyte*)vm_version, version_len);

    // Truncate the ident, saving room for the 8 hex character hash value.
    strncpy(header_version, vm_version, JVM_IDENT_MAX-9);

    // Append the hash code as eight hex digits.
    sprintf(&header_version[JVM_IDENT_MAX-9], "%08x", hash);
    header_version[JVM_IDENT_MAX-1] = 0;  // Null terminate.
  }
}

void FileMapInfo::populate_header(size_t alignment) {
  _header._magic = 0xf00baba2;
  _header._version = _current_version;
  _header._alignment = alignment;
  _header._obj_alignment = ObjectAlignmentInBytes;

  // The following fields are for sanity checks for whether this archive
  // will function correctly with this JVM and the bootclasspath it's
  // invoked with.

  // JVM version string ... changes on each build.
  get_header_version(_header._jvm_ident);

  // Build checks on classpath and jar files
  _header._num_jars = 0;
  ClassPathEntry *cpe = ClassLoader::classpath_entry(0);
  for ( ; cpe != NULL; cpe = cpe->next()) {

    if (cpe->is_jar_file()) {
      if (_header._num_jars >= JVM_SHARED_JARS_MAX) {
        fail_stop("Too many jar files to share.", NULL);
      }

      // Jar file - record timestamp and file size.
      struct stat st;
      const char *path = cpe->name();
      if (os::stat(path, &st) != 0) {
        // If we can't access a jar file in the boot path, then we can't
        // make assumptions about where classes get loaded from.
        fail_stop("Unable to open jar file %s.", path);
      }
      _header._jar[_header._num_jars]._timestamp = st.st_mtime;
      _header._jar[_header._num_jars]._filesize = st.st_size;
      _header._num_jars++;
    } else {

      // If directories appear in boot classpath, they must be empty to
      // avoid having to verify each individual class file.
      const char* name = ((ClassPathDirEntry*)cpe)->name();
      if (!os::dir_is_empty(name)) {
        fail_stop("Boot classpath directory %s is not empty.", name);
      }
    }
  }
}


// Read the FileMapInfo information from the file.

bool FileMapInfo::init_from_file(int fd) {

  size_t n = read(fd, &_header, sizeof(struct FileMapHeader));
  if (n != sizeof(struct FileMapHeader)) {
    fail_continue("Unable to read the file header.");
    return false;
  }
  if (_header._version != current_version()) {
    fail_continue("The shared archive file has the wrong version.");
    return false;
  }
  _file_offset = (long)n;
  return true;
}


// Read the FileMapInfo information from the file.
bool FileMapInfo::open_for_read() {
  _full_path = Arguments::GetSharedArchivePath();
  int fd = open(_full_path, O_RDONLY | O_BINARY, 0);
  if (fd < 0) {
    if (errno == ENOENT) {
      // Not locating the shared archive is ok.
      fail_continue("Specified shared archive not found.");
    } else {
      fail_continue("Failed to open shared archive file (%s).",
                    strerror(errno));
    }
    return false;
  }

  _fd = fd;
  _file_open = true;
  return true;
}


// Write the FileMapInfo information to the file.

void FileMapInfo::open_for_write() {
 _full_path = Arguments::GetSharedArchivePath();
  if (PrintSharedSpaces) {
    tty->print_cr("Dumping shared data to file: ");
    tty->print_cr("   %s", _full_path);
  }

#ifdef _WINDOWS  // On Windows, need WRITE permission to remove the file.
  chmod(_full_path, _S_IREAD | _S_IWRITE);
#endif

  // Use remove() to delete the existing file because, on Unix, this will
  // allow processes that have it open continued access to the file.
  remove(_full_path);
  int fd = open(_full_path, O_RDWR | O_CREAT | O_TRUNC | O_BINARY, 0444);
  if (fd < 0) {
    fail_stop("Unable to create shared archive file %s.", _full_path);
  }
  _fd = fd;
  _file_offset = 0;
  _file_open = true;
}


// Write the header to the file, seek to the next allocation boundary.

void FileMapInfo::write_header() {
  write_bytes_aligned(&_header, sizeof(FileMapHeader));
}


// Dump shared spaces to file.

void FileMapInfo::write_space(int i, Metaspace* space, bool read_only) {
  align_file_position();
  size_t used = space->used_bytes_slow(Metaspace::NonClassType);
  size_t capacity = space->capacity_bytes_slow(Metaspace::NonClassType);
  struct FileMapInfo::FileMapHeader::space_info* si = &_header._space[i];
  write_region(i, (char*)space->bottom(), used, capacity, read_only, false);
}


// Dump region to file.

void FileMapInfo::write_region(int region, char* base, size_t size,
                               size_t capacity, bool read_only,
                               bool allow_exec) {
  struct FileMapInfo::FileMapHeader::space_info* si = &_header._space[region];

  if (_file_open) {
    guarantee(si->_file_offset == _file_offset, "file offset mismatch.");
    if (PrintSharedSpaces) {
      tty->print_cr("Shared file region %d: 0x%6x bytes, addr " INTPTR_FORMAT
                    " file offset 0x%6x", region, size, base, _file_offset);
    }
  } else {
    si->_file_offset = _file_offset;
  }
  si->_base = base;
  si->_used = size;
  si->_capacity = capacity;
  si->_read_only = read_only;
  si->_allow_exec = allow_exec;
  write_bytes_aligned(base, (int)size);
}


// Dump bytes to file -- at the current file position.

void FileMapInfo::write_bytes(const void* buffer, int nbytes) {
  if (_file_open) {
    int n = ::write(_fd, buffer, nbytes);
    if (n != nbytes) {
      // It is dangerous to leave the corrupted shared archive file around,
      // close and remove the file. See bug 6372906.
      close();
      remove(_full_path);
      fail_stop("Unable to write to shared archive file.", NULL);
    }
  }
  _file_offset += nbytes;
}


// Align file position to an allocation unit boundary.

void FileMapInfo::align_file_position() {
  long new_file_offset = align_size_up(_file_offset, os::vm_allocation_granularity());
  if (new_file_offset != _file_offset) {
    _file_offset = new_file_offset;
    if (_file_open) {
      // Seek one byte back from the target and write a byte to insure
      // that the written file is the correct length.
      _file_offset -= 1;
      if (lseek(_fd, _file_offset, SEEK_SET) < 0) {
        fail_stop("Unable to seek.", NULL);
      }
      char zero = 0;
      write_bytes(&zero, 1);
    }
  }
}


// Dump bytes to file -- at the current file position.

void FileMapInfo::write_bytes_aligned(const void* buffer, int nbytes) {
  align_file_position();
  write_bytes(buffer, nbytes);
  align_file_position();
}


// Close the shared archive file.  This does NOT unmap mapped regions.

void FileMapInfo::close() {
  if (_file_open) {
    if (::close(_fd) < 0) {
      fail_stop("Unable to close the shared archive file.");
    }
    _file_open = false;
    _fd = -1;
  }
}


// JVM/TI RedefineClasses() support:
// Remap the shared readonly space to shared readwrite, private.
bool FileMapInfo::remap_shared_readonly_as_readwrite() {
  struct FileMapInfo::FileMapHeader::space_info* si = &_header._space[0];
  if (!si->_read_only) {
    // the space is already readwrite so we are done
    return true;
  }
  size_t used = si->_used;
  size_t size = align_size_up(used, os::vm_allocation_granularity());
  if (!open_for_read()) {
    return false;
  }
  char *base = os::remap_memory(_fd, _full_path, si->_file_offset,
                                si->_base, size, false /* !read_only */,
                                si->_allow_exec);
  close();
  if (base == NULL) {
    fail_continue("Unable to remap shared readonly space (errno=%d).", errno);
    return false;
  }
  if (base != si->_base) {
    fail_continue("Unable to remap shared readonly space at required address.");
    return false;
  }
  si->_read_only = false;
  return true;
}

// Map the whole region at once, assumed to be allocated contiguously.
ReservedSpace FileMapInfo::reserve_shared_memory() {
  struct FileMapInfo::FileMapHeader::space_info* si = &_header._space[0];
  char* requested_addr = si->_base;

  size_t size = FileMapInfo::shared_spaces_size();

  // Reserve the space first, then map otherwise map will go right over some
  // other reserved memory (like the code cache).
  ReservedSpace rs(size, os::vm_allocation_granularity(), false, requested_addr);
  if (!rs.is_reserved()) {
    fail_continue(err_msg("Unable to reserve shared space at required address " INTPTR_FORMAT, requested_addr));
    return rs;
  }
  // the reserved virtual memory is for mapping class data sharing archive
  MemTracker::record_virtual_memory_type((address)rs.base(), mtClassShared);

  return rs;
}

// Memory map a region in the address space.
static const char* shared_region_name[] = { "ReadOnly", "ReadWrite", "MiscData", "MiscCode"};

char* FileMapInfo::map_region(int i) {
  struct FileMapInfo::FileMapHeader::space_info* si = &_header._space[i];
  size_t used = si->_used;
  size_t alignment = os::vm_allocation_granularity();
  size_t size = align_size_up(used, alignment);
  char *requested_addr = si->_base;

  // map the contents of the CDS archive in this memory
  char *base = os::map_memory(_fd, _full_path, si->_file_offset,
                              requested_addr, size, si->_read_only,
                              si->_allow_exec);
  if (base == NULL || base != si->_base) {
    fail_continue(err_msg("Unable to map %s shared space at required address.", shared_region_name[i]));
    return NULL;
  }
#ifdef _WINDOWS
  // This call is Windows-only because the memory_type gets recorded for the other platforms
  // in method FileMapInfo::reserve_shared_memory(), which is not called on Windows.
  MemTracker::record_virtual_memory_type((address)base, mtClassShared);
#endif
  return base;
}


// Unmap a memory region in the address space.

void FileMapInfo::unmap_region(int i) {
  struct FileMapInfo::FileMapHeader::space_info* si = &_header._space[i];
  size_t used = si->_used;
  size_t size = align_size_up(used, os::vm_allocation_granularity());
  if (!os::unmap_memory(si->_base, size)) {
    fail_stop("Unable to unmap shared space.");
  }
}


void FileMapInfo::assert_mark(bool check) {
  if (!check) {
    fail_stop("Mark mismatch while restoring from shared file.", NULL);
  }
}


FileMapInfo* FileMapInfo::_current_info = NULL;


// Open the shared archive file, read and validate the header
// information (version, boot classpath, etc.).  If initialization
// fails, shared spaces are disabled and the file is closed. [See
// fail_continue.]
bool FileMapInfo::initialize() {
  assert(UseSharedSpaces, "UseSharedSpaces expected.");

  if (JvmtiExport::can_modify_any_class() || JvmtiExport::can_walk_any_space()) {
    fail_continue("Tool agent requires sharing to be disabled.");
    return false;
  }

  if (!open_for_read()) {
    return false;
  }

  init_from_file(_fd);
  if (!validate()) {
    return false;
  }

  SharedReadOnlySize =  _header._space[0]._capacity;
  SharedReadWriteSize = _header._space[1]._capacity;
  SharedMiscDataSize =  _header._space[2]._capacity;
  SharedMiscCodeSize =  _header._space[3]._capacity;
  return true;
}


bool FileMapInfo::validate() {
  if (_header._version != current_version()) {
    fail_continue("The shared archive file is the wrong version.");
    return false;
  }
  if (_header._magic != (int)0xf00baba2) {
    fail_continue("The shared archive file has a bad magic number.");
    return false;
  }
  char header_version[JVM_IDENT_MAX];
  get_header_version(header_version);
  if (strncmp(_header._jvm_ident, header_version, JVM_IDENT_MAX-1) != 0) {
    fail_continue("The shared archive file was created by a different"
                  " version or build of HotSpot.");
    return false;
  }
  if (_header._obj_alignment != ObjectAlignmentInBytes) {
    fail_continue("The shared archive file's ObjectAlignmentInBytes of %d"
                  " does not equal the current ObjectAlignmentInBytes of %d.",
                  _header._obj_alignment, ObjectAlignmentInBytes);
    return false;
  }

  // Cannot verify interpreter yet, as it can only be created after the GC
  // heap has been initialized.

  if (_header._num_jars >= JVM_SHARED_JARS_MAX) {
    fail_continue("Too many jar files to share.");
    return false;
  }

  // Build checks on classpath and jar files
  int num_jars_now = 0;
  ClassPathEntry *cpe = ClassLoader::classpath_entry(0);
  for ( ; cpe != NULL; cpe = cpe->next()) {

    if (cpe->is_jar_file()) {
      if (num_jars_now < _header._num_jars) {

        // Jar file - verify timestamp and file size.
        struct stat st;
        const char *path = cpe->name();
        if (os::stat(path, &st) != 0) {
          fail_continue("Unable to open jar file %s.", path);
          return false;
        }
        if (_header._jar[num_jars_now]._timestamp != st.st_mtime ||
            _header._jar[num_jars_now]._filesize != st.st_size) {
          fail_continue("A jar file is not the one used while building"
                        " the shared archive file.");
          return false;
        }
      }
      ++num_jars_now;
    } else {

      // If directories appear in boot classpath, they must be empty to
      // avoid having to verify each individual class file.
      const char* name = ((ClassPathDirEntry*)cpe)->name();
      if (!os::dir_is_empty(name)) {
        fail_continue("Boot classpath directory %s is not empty.", name);
        return false;
      }
    }
  }
  if (num_jars_now < _header._num_jars) {
    fail_continue("The number of jar files in the boot classpath is"
                  " less than the number the shared archive was created with.");
    return false;
  }

  return true;
}

// The following method is provided to see whether a given pointer
// falls in the mapped shared space.
// Param:
// p, The given pointer
// Return:
// True if the p is within the mapped shared space, otherwise, false.
bool FileMapInfo::is_in_shared_space(const void* p) {
  for (int i = 0; i < MetaspaceShared::n_regions; i++) {
    if (p >= _header._space[i]._base &&
        p < _header._space[i]._base + _header._space[i]._used) {
      return true;
    }
  }

  return false;
}

void FileMapInfo::print_shared_spaces() {
  gclog_or_tty->print_cr("Shared Spaces:");
  for (int i = 0; i < MetaspaceShared::n_regions; i++) {
    struct FileMapInfo::FileMapHeader::space_info* si = &_header._space[i];
    gclog_or_tty->print("  %s " INTPTR_FORMAT "-" INTPTR_FORMAT,
                        shared_region_name[i],
                        si->_base, si->_base + si->_used);
  }
}

// Unmap mapped regions of shared space.
void FileMapInfo::stop_sharing_and_unmap(const char* msg) {
  FileMapInfo *map_info = FileMapInfo::current_info();
  if (map_info) {
    map_info->fail_continue(msg);
    for (int i = 0; i < MetaspaceShared::n_regions; i++) {
      if (map_info->_header._space[i]._base != NULL) {
        map_info->unmap_region(i);
        map_info->_header._space[i]._base = NULL;
      }
    }
  } else if (DumpSharedSpaces) {
    fail_stop(msg, NULL);
  }
}
