/*
 * Copyright (c) 2011, 2013, Oracle and/or its affiliates. All rights reserved.
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

package sun.lwawt;

import java.awt.*;
import java.awt.List;
import java.awt.datatransfer.*;
import java.awt.dnd.*;
import java.awt.dnd.peer.*;
import java.awt.image.*;
import java.awt.peer.*;
import java.security.*;
import java.util.*;

import sun.awt.*;
import sun.lwawt.macosx.*;
import sun.print.*;
import sun.security.util.SecurityConstants;

public abstract class LWToolkit extends SunToolkit implements Runnable {

    private final static int STATE_NONE = 0;
    private final static int STATE_INIT = 1;
    private final static int STATE_MESSAGELOOP = 2;
    private final static int STATE_SHUTDOWN = 3;
    private final static int STATE_CLEANUP = 4;
    private final static int STATE_DONE = 5;

    private int runState = STATE_NONE;

    private Clipboard clipboard;
    private MouseInfoPeer mouseInfoPeer;

    /**
     * Dynamic Layout Resize client code setting.
     */
    private volatile boolean dynamicLayoutSetting = true;

    protected LWToolkit() {
    }

    /*
     * This method is called by subclasses to start this toolkit
     * by launching the message loop.
     *
     * This method waits for the toolkit to be completely initialized
     * and returns before the message pump is started.
     */
    protected final void init() {
        AWTAutoShutdown.notifyToolkitThreadBusy();

        ThreadGroup mainTG = AccessController.doPrivileged(
            new PrivilegedAction<ThreadGroup>() {
                public ThreadGroup run() {
                    ThreadGroup currentTG = Thread.currentThread().getThreadGroup();
                    ThreadGroup parentTG = currentTG.getParent();
                    while (parentTG != null) {
                        currentTG = parentTG;
                        parentTG = currentTG.getParent();
                    }
                    return currentTG;
                }
            }
        );

        Runtime.getRuntime().addShutdownHook(
            new Thread(mainTG, new Runnable() {
                public void run() {
                    shutdown();
                    waitForRunState(STATE_CLEANUP);
                }
            })
        );

        Thread toolkitThread = new Thread(mainTG, this, "AWT-LW");
        toolkitThread.setDaemon(true);
        toolkitThread.setPriority(Thread.NORM_PRIORITY + 1);
        toolkitThread.start();

        waitForRunState(STATE_MESSAGELOOP);
    }

    /*
     * Implemented in subclasses to initialize platform-dependent
     * part of the toolkit (open X display connection, create
     * toolkit HWND, etc.)
     *
     * This method is called on the toolkit thread.
     */
    protected abstract void platformInit();

    /*
     * Sends a request to stop the message pump.
     */
    public void shutdown() {
        setRunState(STATE_SHUTDOWN);
        platformShutdown();
    }

    /*
     * Implemented in subclasses to release all the platform-
     * dependent resources. Called after the message loop is
     * terminated.
     *
     * Could be called (always called?) on a non-toolkit thread.
     */
    protected abstract void platformShutdown();

    /*
     * Implemented in subclasses to release all the platform
     * resources before the application is terminated.
     *
     * This method is called on the toolkit thread.
     */
    protected abstract void platformCleanup();

    private synchronized int getRunState() {
        return runState;
    }

    private synchronized void setRunState(int state) {
        runState = state;
        notifyAll();
    }

    public boolean isTerminating() {
        return getRunState() >= STATE_SHUTDOWN;
    }

    private void waitForRunState(int state) {
        while (getRunState() < state) {
            try {
                synchronized (this) {
                    wait();
                }
            } catch (InterruptedException z) {
                // TODO: log
                break;
            }
        }
    }

    public void run() {
        setRunState(STATE_INIT);
        platformInit();
        AWTAutoShutdown.notifyToolkitThreadFree();
        setRunState(STATE_MESSAGELOOP);
        while (getRunState() < STATE_SHUTDOWN) {
            try {
                platformRunMessage();
                if (Thread.currentThread().isInterrupted()) {
                    if (AppContext.getAppContext().isDisposed()) {
                        break;
                    }
                }
            } catch (ThreadDeath td) {
                //XXX: if there isn't native code on the stack, the VM just
                //kills the thread right away. Do we expect to catch it
                //nevertheless?
                break;
            } catch (Throwable t) {
                // TODO: log
                System.err.println("Exception on the toolkit thread");
                t.printStackTrace(System.err);
            }
        }
        //XXX: if that's a secondary loop, jump back to the STATE_MESSAGELOOP
        setRunState(STATE_CLEANUP);
        AWTAutoShutdown.notifyToolkitThreadFree();
        platformCleanup();
        setRunState(STATE_DONE);
    }

    /*
     * Process the next message(s) from the native event queue.
     *
     * Initially, all the LWToolkit implementations were supposed
     * to have the similar message loop sequence: check if any events
     * available, peek events, wait. However, the later analysis shown
     * that X11 and Windows implementations are really different, so
     * let the subclasses do whatever they require.
     */
    protected abstract void platformRunMessage();

    public static LWToolkit getLWToolkit() {
        return (LWToolkit)Toolkit.getDefaultToolkit();
    }

    // ---- TOPLEVEL PEERS ---- //

    /*
     * Note that LWWindowPeer implements WindowPeer, FramePeer
     * and DialogPeer interfaces.
     */
    private LWWindowPeer createDelegatedPeer(Window target, PlatformComponent platformComponent,
                                             PlatformWindow platformWindow, LWWindowPeer.PeerType peerType)
    {
        LWWindowPeer peer = new LWWindowPeer(target, platformComponent, platformWindow, peerType);
        targetCreatedPeer(target, peer);
        peer.initialize();
        return peer;
    }

    private LWLightweightFramePeer createDelegatedLwPeer(LightweightFrame target,
                                                         PlatformComponent platformComponent,
                                                         PlatformWindow platformWindow)
    {
        LWLightweightFramePeer peer = new LWLightweightFramePeer(target, platformComponent, platformWindow);
        targetCreatedPeer(target, peer);
        peer.initialize();
        return peer;
    }

    @Override
    public FramePeer createLightweightFrame(LightweightFrame target) {
        PlatformComponent platformComponent = createLwPlatformComponent();
        PlatformWindow platformWindow = createPlatformWindow(LWWindowPeer.PeerType.LW_FRAME);
        return createDelegatedLwPeer(target, platformComponent, platformWindow);
    }

    @Override
    public WindowPeer createWindow(Window target) {
        PlatformComponent platformComponent = createPlatformComponent();
        PlatformWindow platformWindow = createPlatformWindow(LWWindowPeer.PeerType.SIMPLEWINDOW);
        return createDelegatedPeer(target, platformComponent, platformWindow, LWWindowPeer.PeerType.SIMPLEWINDOW);
    }

    @Override
    public FramePeer createFrame(Frame target) {
        PlatformComponent platformComponent = createPlatformComponent();
        PlatformWindow platformWindow = createPlatformWindow(LWWindowPeer.PeerType.FRAME);
        return createDelegatedPeer(target, platformComponent, platformWindow, LWWindowPeer.PeerType.FRAME);
    }

    public LWWindowPeer createEmbeddedFrame(CEmbeddedFrame target) {
        PlatformComponent platformComponent = createPlatformComponent();
        PlatformWindow platformWindow = createPlatformWindow(LWWindowPeer.PeerType.EMBEDDED_FRAME);
        return createDelegatedPeer(target, platformComponent, platformWindow, LWWindowPeer.PeerType.EMBEDDED_FRAME);
    }

    public LWWindowPeer createEmbeddedFrame(CViewEmbeddedFrame target) {
        PlatformComponent platformComponent = createPlatformComponent();
        PlatformWindow platformWindow = createPlatformWindow(LWWindowPeer.PeerType.VIEW_EMBEDDED_FRAME);
        return createDelegatedPeer(target, platformComponent, platformWindow, LWWindowPeer.PeerType.VIEW_EMBEDDED_FRAME);
    }


    CPrinterDialogPeer createCPrinterDialog(CPrinterDialog target) {
        PlatformComponent platformComponent = createPlatformComponent();
        PlatformWindow platformWindow = createPlatformWindow(LWWindowPeer.PeerType.DIALOG);
        CPrinterDialogPeer peer = new CPrinterDialogPeer(target, platformComponent, platformWindow);
        targetCreatedPeer(target, peer);
        return peer;
    }

    @Override
    public DialogPeer createDialog(Dialog target) {
        if (target instanceof CPrinterDialog) {
            return createCPrinterDialog((CPrinterDialog)target);
        }

        PlatformComponent platformComponent = createPlatformComponent();
        PlatformWindow platformWindow = createPlatformWindow(LWWindowPeer.PeerType.DIALOG);
        return createDelegatedPeer(target, platformComponent, platformWindow, LWWindowPeer.PeerType.DIALOG);
    }

    @Override
    public FileDialogPeer createFileDialog(FileDialog target) {
        FileDialogPeer peer = createFileDialogPeer(target);
        targetCreatedPeer(target, peer);
        return peer;
    }

    // ---- LIGHTWEIGHT COMPONENT PEERS ---- //

    @Override
    public ButtonPeer createButton(Button target) {
        PlatformComponent platformComponent = createPlatformComponent();
        LWButtonPeer peer = new LWButtonPeer(target, platformComponent);
        targetCreatedPeer(target, peer);
        peer.initialize();
        return peer;
    }

    @Override
    public CheckboxPeer createCheckbox(Checkbox target) {
        PlatformComponent platformComponent = createPlatformComponent();
        LWCheckboxPeer peer = new LWCheckboxPeer(target, platformComponent);
        targetCreatedPeer(target, peer);
        peer.initialize();
        return peer;
    }

    @Override
    public CheckboxMenuItemPeer createCheckboxMenuItem(CheckboxMenuItem target) {
        throw new RuntimeException("not implemented");
    }

    @Override
    public ChoicePeer createChoice(Choice target) {
        PlatformComponent platformComponent = createPlatformComponent();
        LWChoicePeer peer = new LWChoicePeer(target, platformComponent);
        targetCreatedPeer(target, peer);
        peer.initialize();
        return peer;
    }

    @Override
    public LabelPeer createLabel(Label target) {
        PlatformComponent platformComponent = createPlatformComponent();
        LWLabelPeer peer = new LWLabelPeer(target, platformComponent);
        targetCreatedPeer(target, peer);
        peer.initialize();
        return peer;
    }

    @Override
    public CanvasPeer createCanvas(Canvas target) {
        PlatformComponent platformComponent = createPlatformComponent();
        LWCanvasPeer<?, ?> peer = new LWCanvasPeer<>(target, platformComponent);
        targetCreatedPeer(target, peer);
        peer.initialize();
        return peer;
    }

    @Override
    public ListPeer createList(List target) {
        PlatformComponent platformComponent = createPlatformComponent();
        LWListPeer peer = new LWListPeer(target, platformComponent);
        targetCreatedPeer(target, peer);
        peer.initialize();
        return peer;
    }

    @Override
    public MenuPeer createMenu(Menu target) {
        throw new RuntimeException("not implemented");
    }

    @Override
    public MenuBarPeer createMenuBar(MenuBar target) {
        throw new RuntimeException("not implemented");
    }

    @Override
    public MenuItemPeer createMenuItem(MenuItem target) {
        throw new RuntimeException("not implemented");
    }

    @Override
    public PanelPeer createPanel(Panel target) {
        PlatformComponent platformComponent = createPlatformComponent();
        LWPanelPeer peer = new LWPanelPeer(target, platformComponent);
        targetCreatedPeer(target, peer);
        peer.initialize();
        return peer;
    }

    @Override
    public PopupMenuPeer createPopupMenu(PopupMenu target) {
        throw new RuntimeException("not implemented");
    }

    @Override
    public ScrollPanePeer createScrollPane(ScrollPane target) {
        PlatformComponent platformComponent = createPlatformComponent();
        LWScrollPanePeer peer = new LWScrollPanePeer(target, platformComponent);
        targetCreatedPeer(target, peer);
        peer.initialize();
        return peer;
    }

    @Override
    public ScrollbarPeer createScrollbar(Scrollbar target) {
        PlatformComponent platformComponent = createPlatformComponent();
        LWScrollBarPeer peer = new LWScrollBarPeer(target, platformComponent);
        targetCreatedPeer(target, peer);
        peer.initialize();
        return peer;
    }

    @Override
    public TextAreaPeer createTextArea(TextArea target) {
        PlatformComponent platformComponent = createPlatformComponent();
        LWTextAreaPeer peer = new LWTextAreaPeer(target, platformComponent);
        targetCreatedPeer(target, peer);
        peer.initialize();
        return peer;
    }

    @Override
    public TextFieldPeer createTextField(TextField target) {
        PlatformComponent platformComponent = createPlatformComponent();
        LWTextFieldPeer peer = new LWTextFieldPeer(target, platformComponent);
        targetCreatedPeer(target, peer);
        peer.initialize();
        return peer;
    }

    // ---- NON-COMPONENT PEERS ---- //

    @Override
    public ColorModel getColorModel() throws HeadlessException {
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getColorModel();
    }

    @Override
    public boolean isDesktopSupported() {
        return true;
    }

    @Override
    protected DesktopPeer createDesktopPeer(Desktop target) {
       return new CDesktopPeer();
    }

    @Override
    public DragSourceContextPeer createDragSourceContextPeer(DragGestureEvent dge) {
        DragSourceContextPeer dscp = CDragSourceContextPeer.createDragSourceContextPeer(dge);

        return dscp;
    }

    @Override
    public KeyboardFocusManagerPeer getKeyboardFocusManagerPeer() {
        return LWKeyboardFocusManagerPeer.getInstance();
    }

    @Override
    public synchronized MouseInfoPeer getMouseInfoPeer() {
        if (mouseInfoPeer == null) {
            mouseInfoPeer = createMouseInfoPeerImpl();
        }
        return mouseInfoPeer;
    }

    protected MouseInfoPeer createMouseInfoPeerImpl() {
        return new LWMouseInfoPeer();
    }

    public PrintJob getPrintJob(Frame frame, String doctitle, Properties props) {
        return getPrintJob(frame, doctitle, null, null);
    }

    public PrintJob getPrintJob(Frame frame, String doctitle, JobAttributes jobAttributes, PageAttributes pageAttributes) {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalArgumentException();
        }

        PrintJob2D printJob = new PrintJob2D(frame, doctitle, jobAttributes, pageAttributes);

        if (printJob.printDialog() == false) {
            printJob = null;
        }

        return printJob;
    }

    @Override
    public RobotPeer createRobot(Robot target, GraphicsDevice screen) {
        throw new RuntimeException("not implemented");
    }

    @Override
    public boolean isTraySupported() {
        throw new RuntimeException("not implemented");
    }

    @Override
    public SystemTrayPeer createSystemTray(SystemTray target) {
        throw new RuntimeException("not implemented");
    }

    @Override
    public TrayIconPeer createTrayIcon(TrayIcon target) {
        throw new RuntimeException("not implemented");
    }

    @Override
    public Clipboard getSystemClipboard() {
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkPermission(SecurityConstants.AWT.ACCESS_CLIPBOARD_PERMISSION);
        }

        synchronized (this) {
            if (clipboard == null) {
                clipboard = createPlatformClipboard();
            }
        }
        return clipboard;
    }

    protected abstract SecurityWarningWindow createSecurityWarning(Window ownerWindow, LWWindowPeer ownerPeer);

    // ---- DELEGATES ---- //

    public abstract Clipboard createPlatformClipboard();

    /*
     * Creates a delegate for the given peer type (window, frame, dialog, etc.)
     */
    protected abstract PlatformWindow createPlatformWindow(LWWindowPeer.PeerType peerType);

    protected abstract PlatformComponent createPlatformComponent();

    protected abstract PlatformComponent createLwPlatformComponent();

    protected abstract FileDialogPeer createFileDialogPeer(FileDialog target);

    // ---- UTILITY METHODS ---- //

    /*
     * Expose non-public targetToPeer() method.
     */
    public final static Object targetToPeer(Object target) {
        return SunToolkit.targetToPeer(target);
    }

    /*
     * Expose non-public targetDisposedPeer() method.
     */
    public final static void targetDisposedPeer(Object target, Object peer) {
        SunToolkit.targetDisposedPeer(target, peer);
    }

    /*
     * Returns the current cursor manager.
     */
    public abstract LWCursorManager getCursorManager();

    public static void postEvent(AWTEvent event) {
        postEvent(targetToAppContext(event.getSource()), event);
    }

    @Override
    public void grab(final Window w) {
        final Object peer = AWTAccessor.getComponentAccessor().getPeer(w);
        if (peer != null) {
            ((LWWindowPeer) peer).grab();
        }
    }

    @Override
    public void ungrab(final Window w) {
        final Object peer = AWTAccessor.getComponentAccessor().getPeer(w);
        if (peer != null) {
            ((LWWindowPeer) peer).ungrab(false);
        }
    }

    @Override
    protected final Object lazilyLoadDesktopProperty(final String name) {
        if (name.equals("awt.dynamicLayoutSupported")) {
            return isDynamicLayoutSupported();
        }
        return super.lazilyLoadDesktopProperty(name);
    }

    @Override
    public final void setDynamicLayout(final boolean dynamic) {
        dynamicLayoutSetting = dynamic;
    }

    @Override
    protected final boolean isDynamicLayoutSet() {
        return dynamicLayoutSetting;
    }

    @Override
    public final boolean isDynamicLayoutActive() {
        // "Live resizing" is active by default and user's data is ignored.
        return isDynamicLayoutSupported();
    }

    /**
     * Returns true if dynamic layout of Containers on resize is supported by
     * the underlying operating system and/or window manager.
     */
    protected final boolean isDynamicLayoutSupported() {
        // "Live resizing" is supported by default.
        return true;
    }
}
