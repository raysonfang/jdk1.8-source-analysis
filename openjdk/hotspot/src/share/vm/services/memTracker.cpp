/*
 * Copyright (c) 2012, 2013, Oracle and/or its affiliates. All rights reserved.
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

#include "oops/instanceKlass.hpp"
#include "runtime/atomic.hpp"
#include "runtime/interfaceSupport.hpp"
#include "runtime/mutexLocker.hpp"
#include "runtime/safepoint.hpp"
#include "runtime/threadCritical.hpp"
#include "runtime/vm_operations.hpp"
#include "services/memPtr.hpp"
#include "services/memReporter.hpp"
#include "services/memTracker.hpp"
#include "utilities/decoder.hpp"
#include "utilities/defaultStream.hpp"
#include "utilities/globalDefinitions.hpp"

bool NMT_track_callsite = false;

// walk all 'known' threads at NMT sync point, and collect their recorders
void SyncThreadRecorderClosure::do_thread(Thread* thread) {
  assert(SafepointSynchronize::is_at_safepoint(), "Safepoint required");
  if (thread->is_Java_thread()) {
    JavaThread* javaThread = (JavaThread*)thread;
    MemRecorder* recorder = javaThread->get_recorder();
    if (recorder != NULL) {
      MemTracker::enqueue_pending_recorder(recorder);
      javaThread->set_recorder(NULL);
    }
  }
  _thread_count ++;
}


MemRecorder* volatile           MemTracker::_global_recorder = NULL;
MemSnapshot*                    MemTracker::_snapshot = NULL;
MemBaseline                     MemTracker::_baseline;
Mutex*                          MemTracker::_query_lock = NULL;
MemRecorder* volatile           MemTracker::_merge_pending_queue = NULL;
MemRecorder* volatile           MemTracker::_pooled_recorders = NULL;
MemTrackWorker*                 MemTracker::_worker_thread = NULL;
int                             MemTracker::_sync_point_skip_count = 0;
MemTracker::NMTLevel            MemTracker::_tracking_level = MemTracker::NMT_off;
volatile MemTracker::NMTStates  MemTracker::_state = NMT_uninited;
MemTracker::ShutdownReason      MemTracker::_reason = NMT_shutdown_none;
int                             MemTracker::_thread_count = 255;
volatile jint                   MemTracker::_pooled_recorder_count = 0;
volatile unsigned long          MemTracker::_processing_generation = 0;
volatile bool                   MemTracker::_worker_thread_idle = false;
volatile jint                   MemTracker::_pending_op_count = 0;
volatile bool                   MemTracker::_slowdown_calling_thread = false;
debug_only(intx                 MemTracker::_main_thread_tid = 0;)
NOT_PRODUCT(volatile jint       MemTracker::_pending_recorder_count = 0;)

void MemTracker::init_tracking_options(const char* option_line) {
  _tracking_level = NMT_off;
  if (strcmp(option_line, "=summary") == 0) {
    _tracking_level = NMT_summary;
  } else if (strcmp(option_line, "=detail") == 0) {
    // detail relies on a stack-walking ability that may not
    // be available depending on platform and/or compiler flags
#if PLATFORM_NATIVE_STACK_WALKING_SUPPORTED
      _tracking_level = NMT_detail;
#else
      jio_fprintf(defaultStream::error_stream(),
        "NMT detail is not supported on this platform.  Using NMT summary instead.\n");
      _tracking_level = NMT_summary;
#endif
  } else if (strcmp(option_line, "=off") != 0) {
    vm_exit_during_initialization("Syntax error, expecting -XX:NativeMemoryTracking=[off|summary|detail]", NULL);
  }
}

// first phase of bootstrapping, when VM is still in single-threaded mode.
void MemTracker::bootstrap_single_thread() {
  if (_tracking_level > NMT_off) {
    assert(_state == NMT_uninited, "wrong state");

    // NMT is not supported with UseMallocOnly is on. NMT can NOT
    // handle the amount of malloc data without significantly impacting
    // runtime performance when this flag is on.
    if (UseMallocOnly) {
      shutdown(NMT_use_malloc_only);
      return;
    }

    _query_lock = new (std::nothrow) Mutex(Monitor::max_nonleaf, "NMT_queryLock");
    if (_query_lock == NULL) {
      shutdown(NMT_out_of_memory);
      return;
    }

    debug_only(_main_thread_tid = os::current_thread_id();)
    _state = NMT_bootstrapping_single_thread;
    NMT_track_callsite = (_tracking_level == NMT_detail && can_walk_stack());
  }
}

// second phase of bootstrapping, when VM is about to or already entered multi-theaded mode.
void MemTracker::bootstrap_multi_thread() {
  if (_tracking_level > NMT_off && _state == NMT_bootstrapping_single_thread) {
  // create nmt lock for multi-thread execution
    assert(_main_thread_tid == os::current_thread_id(), "wrong thread");
    _state = NMT_bootstrapping_multi_thread;
    NMT_track_callsite = (_tracking_level == NMT_detail && can_walk_stack());
  }
}

// fully start nmt
void MemTracker::start() {
  // Native memory tracking is off from command line option
  if (_tracking_level == NMT_off || shutdown_in_progress()) return;

  assert(_main_thread_tid == os::current_thread_id(), "wrong thread");
  assert(_state == NMT_bootstrapping_multi_thread, "wrong state");

  _snapshot = new (std::nothrow)MemSnapshot();
  if (_snapshot != NULL) {
    if (!_snapshot->out_of_memory() && start_worker(_snapshot)) {
      _state = NMT_started;
      NMT_track_callsite = (_tracking_level == NMT_detail && can_walk_stack());
      return;
    }

    delete _snapshot;
    _snapshot = NULL;
  }

  // fail to start native memory tracking, shut it down
  shutdown(NMT_initialization);
}

/**
 * Shutting down native memory tracking.
 * We can not shutdown native memory tracking immediately, so we just
 * setup shutdown pending flag, every native memory tracking component
 * should orderly shut itself down.
 *
 * The shutdown sequences:
 *  1. MemTracker::shutdown() sets MemTracker to shutdown pending state
 *  2. Worker thread calls MemTracker::final_shutdown(), which transites
 *     MemTracker to final shutdown state.
 *  3. At sync point, MemTracker does final cleanup, before sets memory
 *     tracking level to off to complete shutdown.
 */
void MemTracker::shutdown(ShutdownReason reason) {
  if (_tracking_level == NMT_off) return;

  if (_state <= NMT_bootstrapping_single_thread) {
    // we still in single thread mode, there is not contention
    _state = NMT_shutdown_pending;
    _reason = reason;
  } else {
    // we want to know who initialized shutdown
    if ((jint)NMT_started == Atomic::cmpxchg((jint)NMT_shutdown_pending,
                                       (jint*)&_state, (jint)NMT_started)) {
        _reason = reason;
    }
  }
}

// final phase of shutdown
void MemTracker::final_shutdown() {
  // delete all pending recorders and pooled recorders
  delete_all_pending_recorders();
  delete_all_pooled_recorders();

  {
    // shared baseline and snapshot are the only objects needed to
    // create query results
    MutexLockerEx locker(_query_lock, true);
    // cleanup baseline data and snapshot
    _baseline.clear();
    delete _snapshot;
    _snapshot = NULL;
  }

  // shutdown shared decoder instance, since it is only
  // used by native memory tracking so far.
  Decoder::shutdown();

  MemTrackWorker* worker = NULL;
  {
    ThreadCritical tc;
    // can not delete worker inside the thread critical
    if (_worker_thread != NULL && Thread::current() == _worker_thread) {
      worker = _worker_thread;
      _worker_thread = NULL;
    }
  }
  if (worker != NULL) {
    delete worker;
  }
  _state = NMT_final_shutdown;
}

// delete all pooled recorders
void MemTracker::delete_all_pooled_recorders() {
  // free all pooled recorders
  MemRecorder* volatile cur_head = _pooled_recorders;
  if (cur_head != NULL) {
    MemRecorder* null_ptr = NULL;
    while (cur_head != NULL && (void*)cur_head != Atomic::cmpxchg_ptr((void*)null_ptr,
      (void*)&_pooled_recorders, (void*)cur_head)) {
      cur_head = _pooled_recorders;
    }
    if (cur_head != NULL) {
      delete cur_head;
      _pooled_recorder_count = 0;
    }
  }
}

// delete all recorders in pending queue
void MemTracker::delete_all_pending_recorders() {
  // free all pending recorders
  MemRecorder* pending_head = get_pending_recorders();
  if (pending_head != NULL) {
    delete pending_head;
  }
}

/*
 * retrieve per-thread recorder of specified thread.
 * if thread == NULL, it means global recorder
 */
MemRecorder* MemTracker::get_thread_recorder(JavaThread* thread) {
  if (shutdown_in_progress()) return NULL;

  MemRecorder* rc;
  if (thread == NULL) {
    rc = _global_recorder;
  } else {
    rc = thread->get_recorder();
  }

  if (rc != NULL && rc->is_full()) {
    enqueue_pending_recorder(rc);
    rc = NULL;
  }

  if (rc == NULL) {
    rc = get_new_or_pooled_instance();
    if (thread == NULL) {
      _global_recorder = rc;
    } else {
      thread->set_recorder(rc);
    }
  }
  return rc;
}

/*
 * get a per-thread recorder from pool, or create a new one if
 * there is not one available.
 */
MemRecorder* MemTracker::get_new_or_pooled_instance() {
   MemRecorder* cur_head = const_cast<MemRecorder*> (_pooled_recorders);
   if (cur_head == NULL) {
     MemRecorder* rec = new (std::nothrow)MemRecorder();
     if (rec == NULL || rec->out_of_memory()) {
       shutdown(NMT_out_of_memory);
       if (rec != NULL) {
         delete rec;
         rec = NULL;
       }
     }
     return rec;
   } else {
     MemRecorder* next_head = cur_head->next();
     if ((void*)cur_head != Atomic::cmpxchg_ptr((void*)next_head, (void*)&_pooled_recorders,
       (void*)cur_head)) {
       return get_new_or_pooled_instance();
     }
     cur_head->set_next(NULL);
     Atomic::dec(&_pooled_recorder_count);
     cur_head->set_generation();
     return cur_head;
  }
}

/*
 * retrieve all recorders in pending queue, and empty the queue
 */
MemRecorder* MemTracker::get_pending_recorders() {
  MemRecorder* cur_head = const_cast<MemRecorder*>(_merge_pending_queue);
  MemRecorder* null_ptr = NULL;
  while ((void*)cur_head != Atomic::cmpxchg_ptr((void*)null_ptr, (void*)&_merge_pending_queue,
    (void*)cur_head)) {
    cur_head = const_cast<MemRecorder*>(_merge_pending_queue);
  }
  NOT_PRODUCT(Atomic::store(0, &_pending_recorder_count));
  return cur_head;
}

/*
 * release a recorder to recorder pool.
 */
void MemTracker::release_thread_recorder(MemRecorder* rec) {
  assert(rec != NULL, "null recorder");
  // we don't want to pool too many recorders
  rec->set_next(NULL);
  if (shutdown_in_progress() || _pooled_recorder_count > _thread_count * 2) {
    delete rec;
    return;
  }

  rec->clear();
  MemRecorder* cur_head = const_cast<MemRecorder*>(_pooled_recorders);
  rec->set_next(cur_head);
  while ((void*)cur_head != Atomic::cmpxchg_ptr((void*)rec, (void*)&_pooled_recorders,
    (void*)cur_head)) {
    cur_head = const_cast<MemRecorder*>(_pooled_recorders);
    rec->set_next(cur_head);
  }
  Atomic::inc(&_pooled_recorder_count);
}

// write a record to proper recorder. No lock can be taken from this method
// down.
void MemTracker::write_tracking_record(address addr, MEMFLAGS flags,
    size_t size, jint seq, address pc, JavaThread* thread) {

    MemRecorder* rc = get_thread_recorder(thread);
    if (rc != NULL) {
      rc->record(addr, flags, size, seq, pc);
    }
}

/**
 * enqueue a recorder to pending queue
 */
void MemTracker::enqueue_pending_recorder(MemRecorder* rec) {
  assert(rec != NULL, "null recorder");

  // we are shutting down, so just delete it
  if (shutdown_in_progress()) {
    rec->set_next(NULL);
    delete rec;
    return;
  }

  MemRecorder* cur_head = const_cast<MemRecorder*>(_merge_pending_queue);
  rec->set_next(cur_head);
  while ((void*)cur_head != Atomic::cmpxchg_ptr((void*)rec, (void*)&_merge_pending_queue,
    (void*)cur_head)) {
    cur_head = const_cast<MemRecorder*>(_merge_pending_queue);
    rec->set_next(cur_head);
  }
  NOT_PRODUCT(Atomic::inc(&_pending_recorder_count);)
}

/*
 * The method is called at global safepoint
 * during it synchronization process.
 *   1. enqueue all JavaThreads' per-thread recorders
 *   2. enqueue global recorder
 *   3. retrieve all pending recorders
 *   4. reset global sequence number generator
 *   5. call worker's sync
 */
#define MAX_SAFEPOINTS_TO_SKIP     128
#define SAFE_SEQUENCE_THRESHOLD    30
#define HIGH_GENERATION_THRESHOLD  60
#define MAX_RECORDER_THREAD_RATIO  30
#define MAX_RECORDER_PER_THREAD    100

void MemTracker::sync() {
  assert(_tracking_level > NMT_off, "NMT is not enabled");
  assert(SafepointSynchronize::is_at_safepoint(), "Safepoint required");

  // Some GC tests hit large number of safepoints in short period of time
  // without meaningful activities. We should prevent going to
  // sync point in these cases, which can potentially exhaust generation buffer.
  // Here is the factots to determine if we should go into sync point:
  // 1. not to overflow sequence number
  // 2. if we are in danger to overflow generation buffer
  // 3. how many safepoints we already skipped sync point
  if (_state == NMT_started) {
    // worker thread is not ready, no one can manage generation
    // buffer, so skip this safepoint
    if (_worker_thread == NULL) return;

    if (_sync_point_skip_count < MAX_SAFEPOINTS_TO_SKIP) {
      int per_seq_in_use = SequenceGenerator::peek() * 100 / max_jint;
      int per_gen_in_use = _worker_thread->generations_in_use() * 100 / MAX_GENERATIONS;
      if (per_seq_in_use < SAFE_SEQUENCE_THRESHOLD && per_gen_in_use >= HIGH_GENERATION_THRESHOLD) {
        _sync_point_skip_count ++;
        return;
      }
    }
    {
      // This method is running at safepoint, with ThreadCritical lock,
      // it should guarantee that NMT is fully sync-ed.
      ThreadCritical tc;

      // We can NOT execute NMT sync-point if there are pending tracking ops.
      if (_pending_op_count == 0) {
        SequenceGenerator::reset();
        _sync_point_skip_count = 0;

        // walk all JavaThreads to collect recorders
        SyncThreadRecorderClosure stc;
        Threads::threads_do(&stc);

        _thread_count = stc.get_thread_count();
        MemRecorder* pending_recorders = get_pending_recorders();

        if (_global_recorder != NULL) {
          _global_recorder->set_next(pending_recorders);
          pending_recorders = _global_recorder;
          _global_recorder = NULL;
        }

        // see if NMT has too many outstanding recorder instances, it usually
        // means that worker thread is lagging behind in processing them.
        if (!AutoShutdownNMT) {
          _slowdown_calling_thread = (MemRecorder::_instance_count > MAX_RECORDER_THREAD_RATIO * _thread_count);
        } else {
          // If auto shutdown is on, enforce MAX_RECORDER_PER_THREAD threshold to prevent OOM
          if (MemRecorder::_instance_count >= _thread_count * MAX_RECORDER_PER_THREAD) {
            shutdown(NMT_out_of_memory);
          }
        }

        // check _worker_thread with lock to avoid racing condition
        if (_worker_thread != NULL) {
          _worker_thread->at_sync_point(pending_recorders, InstanceKlass::number_of_instance_classes());
        }
        assert(SequenceGenerator::peek() == 1, "Should not have memory activities during sync-point");
      } else {
        _sync_point_skip_count ++;
      }
    }
  }

  // now, it is the time to shut whole things off
  if (_state == NMT_final_shutdown) {
    // walk all JavaThreads to delete all recorders
    SyncThreadRecorderClosure stc;
    Threads::threads_do(&stc);
    // delete global recorder
    {
      ThreadCritical tc;
      if (_global_recorder != NULL) {
        delete _global_recorder;
        _global_recorder = NULL;
      }
    }
    MemRecorder* pending_recorders = get_pending_recorders();
    if (pending_recorders != NULL) {
      delete pending_recorders;
    }
    // try at a later sync point to ensure MemRecorder instance drops to zero to
    // completely shutdown NMT
    if (MemRecorder::_instance_count == 0) {
      _state = NMT_shutdown;
      _tracking_level = NMT_off;
    }
  }
}

/*
 * Start worker thread.
 */
bool MemTracker::start_worker(MemSnapshot* snapshot) {
  assert(_worker_thread == NULL && _snapshot != NULL, "Just Check");
  _worker_thread = new (std::nothrow) MemTrackWorker(snapshot);
  if (_worker_thread == NULL) {
    return false;
  } else if (_worker_thread->has_error()) {
    delete _worker_thread;
    _worker_thread = NULL;
    return false;
  }
  _worker_thread->start();
  return true;
}

/*
 * We need to collect a JavaThread's per-thread recorder
 * before it exits.
 */
void MemTracker::thread_exiting(JavaThread* thread) {
  if (is_on()) {
    MemRecorder* rec = thread->get_recorder();
    if (rec != NULL) {
      enqueue_pending_recorder(rec);
      thread->set_recorder(NULL);
    }
  }
}

// baseline current memory snapshot
bool MemTracker::baseline() {
  MutexLocker lock(_query_lock);
  MemSnapshot* snapshot = get_snapshot();
  if (snapshot != NULL) {
    return _baseline.baseline(*snapshot, false);
  }
  return false;
}

// print memory usage from current snapshot
bool MemTracker::print_memory_usage(BaselineOutputer& out, size_t unit, bool summary_only) {
  MemBaseline  baseline;
  MutexLocker  lock(_query_lock);
  MemSnapshot* snapshot = get_snapshot();
  if (snapshot != NULL && baseline.baseline(*snapshot, summary_only)) {
    BaselineReporter reporter(out, unit);
    reporter.report_baseline(baseline, summary_only);
    return true;
  }
  return false;
}

// Whitebox API for blocking until the current generation of NMT data has been merged
bool MemTracker::wbtest_wait_for_data_merge() {
  // NMT can't be shutdown while we're holding _query_lock
  MutexLocker lock(_query_lock);
  assert(_worker_thread != NULL, "Invalid query");
  // the generation at query time, so NMT will spin till this generation is processed
  unsigned long generation_at_query_time = SequenceGenerator::current_generation();
  unsigned long current_processing_generation = _processing_generation;
  // if generation counter overflown
  bool generation_overflown = (generation_at_query_time < current_processing_generation);
  long generations_to_wrap = MAX_UNSIGNED_LONG - current_processing_generation;
  // spin
  while (!shutdown_in_progress()) {
    if (!generation_overflown) {
      if (current_processing_generation > generation_at_query_time) {
        return true;
      }
    } else {
      assert(generations_to_wrap >= 0, "Sanity check");
      long current_generations_to_wrap = MAX_UNSIGNED_LONG - current_processing_generation;
      assert(current_generations_to_wrap >= 0, "Sanity check");
      // to overflow an unsigned long should take long time, so to_wrap check should be sufficient
      if (current_generations_to_wrap > generations_to_wrap &&
          current_processing_generation > generation_at_query_time) {
        return true;
      }
    }

    // if worker thread is idle, but generation is not advancing, that means
    // there is not safepoint to let NMT advance generation, force one.
    if (_worker_thread_idle) {
      VM_ForceSafepoint vfs;
      VMThread::execute(&vfs);
    }
    MemSnapshot* snapshot = get_snapshot();
    if (snapshot == NULL) {
      return false;
    }
    snapshot->wait(1000);
    current_processing_generation = _processing_generation;
  }
  // We end up here if NMT is shutting down before our data has been merged
  return false;
}

// compare memory usage between current snapshot and baseline
bool MemTracker::compare_memory_usage(BaselineOutputer& out, size_t unit, bool summary_only) {
  MutexLocker lock(_query_lock);
  if (_baseline.baselined()) {
    MemBaseline baseline;
    MemSnapshot* snapshot = get_snapshot();
    if (snapshot != NULL && baseline.baseline(*snapshot, summary_only)) {
      BaselineReporter reporter(out, unit);
      reporter.diff_baselines(baseline, _baseline, summary_only);
      return true;
    }
  }
  return false;
}

#ifndef PRODUCT
void MemTracker::walk_stack(int toSkip, char* buf, int len) {
  int cur_len = 0;
  char tmp[1024];
  address pc;

  while (cur_len < len) {
    pc = os::get_caller_pc(toSkip + 1);
    if (pc != NULL && os::dll_address_to_function_name(pc, tmp, sizeof(tmp), NULL)) {
      jio_snprintf(&buf[cur_len], (len - cur_len), "%s\n", tmp);
      cur_len = (int)strlen(buf);
    } else {
      buf[cur_len] = '\0';
      break;
    }
    toSkip ++;
  }
}

void MemTracker::print_tracker_stats(outputStream* st) {
  st->print_cr("\nMemory Tracker Stats:");
  st->print_cr("\tMax sequence number = %d", SequenceGenerator::max_seq_num());
  st->print_cr("\tthead count = %d", _thread_count);
  st->print_cr("\tArena instance = %d", Arena::_instance_count);
  st->print_cr("\tpooled recorder count = %d", _pooled_recorder_count);
  st->print_cr("\tqueued recorder count = %d", _pending_recorder_count);
  st->print_cr("\tmemory recorder instance count = %d", MemRecorder::_instance_count);
  if (_worker_thread != NULL) {
    st->print_cr("\tWorker thread:");
    st->print_cr("\t\tSync point count = %d", _worker_thread->_sync_point_count);
    st->print_cr("\t\tpending recorder count = %d", _worker_thread->count_pending_recorders());
    st->print_cr("\t\tmerge count = %d", _worker_thread->_merge_count);
  } else {
    st->print_cr("\tWorker thread is not started");
  }
  st->print_cr(" ");

  if (_snapshot != NULL) {
    _snapshot->print_snapshot_stats(st);
  } else {
    st->print_cr("No snapshot");
  }
}
#endif


// Tracker Implementation

/*
 * Create a tracker.
 * This is a fairly complicated constructor, as it has to make two important decisions:
 *   1) Does it need to take ThreadCritical lock to write tracking record
 *   2) Does it need to pre-reserve a sequence number for the tracking record
 *
 * The rules to determine if ThreadCritical is needed:
 *   1. When nmt is in single-threaded bootstrapping mode, no lock is needed as VM
 *      still in single thread mode.
 *   2. For all threads other than JavaThread, ThreadCritical is needed
 *      to write to recorders to global recorder.
 *   3. For JavaThreads that are no longer visible by safepoint, also
 *      need to take ThreadCritical and records are written to global
 *      recorders, since these threads are NOT walked by Threads.do_thread().
 *   4. JavaThreads that are running in safepoint-safe states do not stop
 *      for safepoints, ThreadCritical lock should be taken to write
 *      memory records.
 *   5. JavaThreads that are running in VM state do not need any lock and
 *      records are written to per-thread recorders.
 *   6. For a thread has yet to attach VM 'Thread', they need to take
 *      ThreadCritical to write to global recorder.
 *
 *  The memory operations that need pre-reserve sequence numbers:
 *    The memory operations that "release" memory blocks and the
 *    operations can fail, need to pre-reserve sequence number. They
 *    are realloc, uncommit and release.
 *
 *  The reason for pre-reserve sequence number, is to prevent race condition:
 *    Thread 1                      Thread 2
 *    <release>
 *                                  <allocate>
 *                                  <write allocate record>
 *   <write release record>
 *   if Thread 2 happens to obtain the memory address Thread 1 just released,
 *   then NMT can mistakenly report the memory is free.
 *
 *  Noticeably, free() does not need pre-reserve sequence number, because the call
 *  does not fail, so we can alway write "release" record before the memory is actaully
 *  freed.
 *
 *  For realloc, uncommit and release, following coding pattern should be used:
 *
 *     MemTracker::Tracker tkr = MemTracker::get_realloc_tracker();
 *     ptr = ::realloc(...);
 *     if (ptr == NULL) {
 *       tkr.record(...)
 *     } else {
 *       tkr.discard();
 *     }
 *
 *     MemTracker::Tracker tkr = MemTracker::get_virtual_memory_uncommit_tracker();
 *     if (uncommit(...)) {
 *       tkr.record(...);
 *     } else {
 *       tkr.discard();
 *     }
 *
 *     MemTracker::Tracker tkr = MemTracker::get_virtual_memory_release_tracker();
 *     if (release(...)) {
 *       tkr.record(...);
 *     } else {
 *       tkr.discard();
 *     }
 *
 * Since pre-reserved sequence number is only good for the generation that it is acquired,
 * when there is pending Tracker that reserved sequence number, NMT sync-point has
 * to be skipped to prevent from advancing generation. This is done by inc and dec
 * MemTracker::_pending_op_count, when MemTracker::_pending_op_count > 0, NMT sync-point is skipped.
 * Not all pre-reservation of sequence number will increment pending op count. For JavaThreads
 * that honor safepoints, safepoint can not occur during the memory operations, so the
 * pre-reserved sequence number won't cross the generation boundry.
 */
MemTracker::Tracker::Tracker(MemoryOperation op, Thread* thr) {
  _op = NoOp;
  _seq = 0;
  if (MemTracker::is_on()) {
    _java_thread = NULL;
    _op = op;

    // figure out if ThreadCritical lock is needed to write this operation
    // to MemTracker
    if (MemTracker::is_single_threaded_bootstrap()) {
      thr = NULL;
    } else if (thr == NULL) {
      // don't use Thread::current(), since it is possible that
      // the calling thread has yet to attach to VM 'Thread',
      // which will result assertion failure
      thr = ThreadLocalStorage::thread();
    }

    if (thr != NULL) {
      // Check NMT load
      MemTracker::check_NMT_load(thr);

      if (thr->is_Java_thread() && ((JavaThread*)thr)->is_safepoint_visible()) {
        _java_thread = (JavaThread*)thr;
        JavaThreadState  state = _java_thread->thread_state();
        // JavaThreads that are safepoint safe, can run through safepoint,
        // so ThreadCritical is needed to ensure no threads at safepoint create
        // new records while the records are being gathered and the sequence number is changing
        _need_thread_critical_lock =
          SafepointSynchronize::safepoint_safe(_java_thread, state);
      } else {
        _need_thread_critical_lock = true;
      }
    } else {
       _need_thread_critical_lock
         = !MemTracker::is_single_threaded_bootstrap();
    }

    // see if we need to pre-reserve sequence number for this operation
    if (_op == Realloc || _op == Uncommit || _op == Release) {
      if (_need_thread_critical_lock) {
        ThreadCritical tc;
        MemTracker::inc_pending_op_count();
        _seq = SequenceGenerator::next();
      } else {
        // for the threads that honor safepoints, no safepoint can occur
        // during the lifespan of tracker, so we don't need to increase
        // pending op count.
        _seq = SequenceGenerator::next();
      }
    }
  }
}

void MemTracker::Tracker::discard() {
  if (MemTracker::is_on() && _seq != 0) {
    if (_need_thread_critical_lock) {
      ThreadCritical tc;
      MemTracker::dec_pending_op_count();
    }
    _seq = 0;
  }
}


void MemTracker::Tracker::record(address old_addr, address new_addr, size_t size,
  MEMFLAGS flags, address pc) {
  assert(old_addr != NULL && new_addr != NULL, "Sanity check");
  assert(_op == Realloc || _op == NoOp, "Wrong call");
  if (MemTracker::is_on() && NMT_CAN_TRACK(flags) && _op != NoOp) {
    assert(_seq > 0, "Need pre-reserve sequence number");
    if (_need_thread_critical_lock) {
      ThreadCritical tc;
      // free old address, use pre-reserved sequence number
      MemTracker::write_tracking_record(old_addr, MemPointerRecord::free_tag(),
        0, _seq, pc, _java_thread);
      MemTracker::write_tracking_record(new_addr, flags | MemPointerRecord::malloc_tag(),
        size, SequenceGenerator::next(), pc, _java_thread);
      // decrement MemTracker pending_op_count
      MemTracker::dec_pending_op_count();
    } else {
      // free old address, use pre-reserved sequence number
      MemTracker::write_tracking_record(old_addr, MemPointerRecord::free_tag(),
        0, _seq, pc, _java_thread);
      MemTracker::write_tracking_record(new_addr, flags | MemPointerRecord::malloc_tag(),
        size, SequenceGenerator::next(), pc, _java_thread);
    }
    _seq = 0;
  }
}

void MemTracker::Tracker::record(address addr, size_t size, MEMFLAGS flags, address pc) {
  // OOM already?
  if (addr == NULL) return;

  if (MemTracker::is_on() && NMT_CAN_TRACK(flags) && _op != NoOp) {
    bool pre_reserved_seq = (_seq != 0);
    address  pc = CALLER_CALLER_PC;
    MEMFLAGS orig_flags = flags;

    // or the tagging flags
    switch(_op) {
      case Malloc:
        flags |= MemPointerRecord::malloc_tag();
        break;
      case Free:
        flags = MemPointerRecord::free_tag();
        break;
      case Realloc:
        fatal("Use the other Tracker::record()");
        break;
      case Reserve:
      case ReserveAndCommit:
        flags |= MemPointerRecord::virtual_memory_reserve_tag();
        break;
      case Commit:
        flags = MemPointerRecord::virtual_memory_commit_tag();
        break;
      case Type:
        flags |= MemPointerRecord::virtual_memory_type_tag();
        break;
      case Uncommit:
        assert(pre_reserved_seq, "Need pre-reserve sequence number");
        flags = MemPointerRecord::virtual_memory_uncommit_tag();
        break;
      case Release:
        assert(pre_reserved_seq, "Need pre-reserve sequence number");
        flags = MemPointerRecord::virtual_memory_release_tag();
        break;
      case ArenaSize:
        // a bit of hack here, add a small postive offset to arena
        // address for its size record, so the size record is sorted
        // right after arena record.
        flags = MemPointerRecord::arena_size_tag();
        addr += sizeof(void*);
        break;
      case StackRelease:
        flags = MemPointerRecord::virtual_memory_release_tag();
        break;
      default:
        ShouldNotReachHere();
    }

    // write memory tracking record
    if (_need_thread_critical_lock) {
      ThreadCritical tc;
      if (_seq == 0) _seq = SequenceGenerator::next();
      MemTracker::write_tracking_record(addr, flags, size, _seq, pc, _java_thread);
      if (_op == ReserveAndCommit) {
        MemTracker::write_tracking_record(addr, orig_flags | MemPointerRecord::virtual_memory_commit_tag(),
          size, SequenceGenerator::next(), pc, _java_thread);
      }
      if (pre_reserved_seq) MemTracker::dec_pending_op_count();
    } else {
      if (_seq == 0) _seq = SequenceGenerator::next();
      MemTracker::write_tracking_record(addr, flags, size, _seq, pc, _java_thread);
      if (_op == ReserveAndCommit) {
        MemTracker::write_tracking_record(addr, orig_flags | MemPointerRecord::virtual_memory_commit_tag(),
          size, SequenceGenerator::next(), pc, _java_thread);
      }
    }
    _seq = 0;
  }
}

