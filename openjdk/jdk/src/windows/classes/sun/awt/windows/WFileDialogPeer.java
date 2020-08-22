/*
 * Copyright (c) 1996, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
 */
package sun.awt.windows;

import java.awt.*;
import java.awt.dnd.DropTarget;
import java.awt.peer.*;
import java.io.File;
import java.io.FilenameFilter;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ResourceBundle;
import java.util.MissingResourceException;
import java.util.Vector;
import sun.awt.AppContext;
import sun.awt.CausedFocusEvent;
import sun.awt.AWTAccessor;

public class WFileDialogPeer extends WWindowPeer implements FileDialogPeer {

    static {
        initIDs();
    }

    private WComponentPeer parent;
    private FilenameFilter fileFilter;

    private Vector<WWindowPeer> blockedWindows = new Vector<WWindowPeer>();

    //Needed to fix 4152317
    private static native void setFilterString(String allFilter);

    public void setFilenameFilter(FilenameFilter filter) {
        this.fileFilter = filter;
    }

    boolean checkFilenameFilter(String filename) {
        FileDialog fileDialog = (FileDialog)target;
        if (fileFilter == null) {
            return true;
        }
        File file = new File(filename);
        return fileFilter.accept(new File(file.getParent()), file.getName());
    }

    // Toolkit & peer internals
    WFileDialogPeer(FileDialog target) {
        super(target);
    }

    void create(WComponentPeer parent) {
        this.parent = parent;
    }

    // don't use checkCreation() from WComponentPeer to avoid hwnd check
    protected void checkCreation() {
    }

    void initialize() {
        setFilenameFilter(((FileDialog) target).getFilenameFilter());
    }

    private native void _dispose();
    protected void disposeImpl() {
        WToolkit.targetDisposedPeer(target, this);
        _dispose();
    }

    private native void _show();
    private native void _hide();

    public void show() {
        new Thread(new Runnable() {
            public void run() {
                _show();
            }
        }).start();
    }

    public void hide() {
        _hide();
    }

    // called from native code when the dialog is shown or hidden
    void setHWnd(long hwnd) {
        if (this.hwnd == hwnd) {
            return;
        }
        this.hwnd = hwnd;
        for (WWindowPeer window : blockedWindows) {
            if (hwnd != 0) {
                window.modalDisable((Dialog)target, hwnd);
            } else {
                window.modalEnable((Dialog)target);
            }
        }
    }

    /*
     * The function converts the file names (the buffer parameter)
     * in the Windows format into the Java format and saves the results
     * into the FileDialog instance.
     *
     * If it's the multi-select mode, the buffer contains the current
     * directory followed by the short names of the files.
     * The directory and file name strings are NULL separated.
     * If it's the single-select mode, the buffer doesn't have the NULL
     * separator between the path and the file name.
     *
     * NOTE: This method is called by privileged threads.
     *       DO NOT INVOKE CLIENT CODE ON THIS THREAD!
     */
    void handleSelected(final char[] buffer)
    {
        String[] wFiles = (new String(buffer)).split("\0"); // NULL is the delimiter
        boolean multiple = (wFiles.length > 1);

        String jDirectory = null;
        String jFile = null;
        File[] jFiles = null;

        if (multiple) {
            jDirectory = wFiles[0];
            int filesNumber = wFiles.length - 1;
            jFiles = new File[filesNumber];
            for (int i = 0; i < filesNumber; i++) {
                jFiles[i] = new File(jDirectory, wFiles[i + 1]);
        }
            jFile = wFiles[1]; // choose any file
        } else {
            int index = wFiles[0].lastIndexOf(java.io.File.separatorChar);
            if (index == -1) {
                jDirectory = "."+java.io.File.separator;
                jFile = wFiles[0];
            } else {
                jDirectory = wFiles[0].substring(0, index + 1);
                jFile = wFiles[0].substring(index + 1);
            }
            jFiles = new File[] { new File(jDirectory, jFile) };
        }

        final FileDialog fileDialog = (FileDialog)target;
        AWTAccessor.FileDialogAccessor fileDialogAccessor = AWTAccessor.getFileDialogAccessor();

        fileDialogAccessor.setDirectory(fileDialog, jDirectory);
        fileDialogAccessor.setFile(fileDialog, jFile);
        fileDialogAccessor.setFiles(fileDialog, jFiles);

        WToolkit.executeOnEventHandlerThread(fileDialog, new Runnable() {
             public void run() {
                 fileDialog.setVisible(false);
             }
        });
    } // handleSelected()

    // NOTE: This method is called by privileged threads.
    //       DO NOT INVOKE CLIENT CODE ON THIS THREAD!
    void handleCancel() {
        final FileDialog fileDialog = (FileDialog)target;

        AWTAccessor.getFileDialogAccessor().setFile(fileDialog, null);
        AWTAccessor.getFileDialogAccessor().setFiles(fileDialog, null);
        AWTAccessor.getFileDialogAccessor().setDirectory(fileDialog, null);

        WToolkit.executeOnEventHandlerThread(fileDialog, new Runnable() {
             public void run() {
                 fileDialog.setVisible(false);
             }
        });
    } // handleCancel()

    //This whole static block is a part of 4152317 fix
    static {
        String filterString = AccessController.doPrivileged(
            new PrivilegedAction<String>() {
                public String run() {
                    try {
                        ResourceBundle rb = ResourceBundle.getBundle("sun.awt.windows.awtLocalization");
                        return rb.getString("allFiles");
                    } catch (MissingResourceException e) {
                        return "All Files";
                    }
                }
            });
        setFilterString(filterString);
    }

    void blockWindow(WWindowPeer window) {
        blockedWindows.add(window);
        // if this dialog hasn't got an HWND, notification is
        // postponed until setHWnd() is called
        if (hwnd != 0) {
            window.modalDisable((Dialog)target, hwnd);
        }
    }
    void unblockWindow(WWindowPeer window) {
        blockedWindows.remove(window);
        // if this dialog hasn't got an HWND or has been already
        // closed, don't send notification
        if (hwnd != 0) {
            window.modalEnable((Dialog)target);
        }
    }

    public void blockWindows(java.util.List<Window> toBlock) {
        for (Window w : toBlock) {
            WWindowPeer wp = (WWindowPeer)AWTAccessor.getComponentAccessor().getPeer(w);
            if (wp != null) {
                blockWindow(wp);
            }
        }
    }

    public native void toFront();
    public native void toBack();

    // unused methods.  Overridden to disable this functionality as
    // it requires HWND which is not available for FileDialog
    public void updateAlwaysOnTopState() {}
    public void setDirectory(String dir) {}
    public void setFile(String file) {}
    public void setTitle(String title) {}

    public void setResizable(boolean resizable) {}
    public void enable() {}
    public void disable() {}
    public void reshape(int x, int y, int width, int height) {}
    public boolean handleEvent(Event e) { return false; }
    public void setForeground(Color c) {}
    public void setBackground(Color c) {}
    public void setFont(Font f) {}
    public void updateMinimumSize() {}
    public void updateIconImages() {}
    public boolean requestFocus(boolean temporary,
                                boolean focusedWindowChangeAllowed) {
        return false;
    }

    public boolean requestFocus
         (Component lightweightChild, boolean temporary,
          boolean focusedWindowChangeAllowed, long time, CausedFocusEvent.Cause cause)
    {
        return false;
    }

    void start() {}
    public void beginValidate() {}
    public void endValidate() {}
    void invalidate(int x, int y, int width, int height) {}
    public void addDropTarget(DropTarget dt) {}
    public void removeDropTarget(DropTarget dt) {}
    public void updateFocusableWindowState() {}
    public void setZOrder(ComponentPeer above) {}

    /**
     * Initialize JNI field and method ids
     */
    private static native void initIDs();

    // The effects are not supported for system dialogs.
    public void applyShape(sun.java2d.pipe.Region shape) {}
    public void setOpacity(float opacity) {}
    public void setOpaque(boolean isOpaque) {}
    public void updateWindow(java.awt.image.BufferedImage backBuffer) {}

    // the file/print dialogs are native dialogs and
    // the native system does their own rendering
    @Override
    public void createScreenSurface(boolean isResize) {}
    @Override
    public void replaceSurfaceData() {}

    public boolean isMultipleMode() {
        FileDialog fileDialog = (FileDialog)target;
        return AWTAccessor.getFileDialogAccessor().isMultipleMode(fileDialog);
    }
}
