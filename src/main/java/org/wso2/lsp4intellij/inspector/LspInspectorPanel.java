/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wso2.lsp4intellij.inspector;

import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ex.ComboBoxAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.ex.ToolWindowManagerListener;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.JBColor;
import com.intellij.ui.ListSpeedSearch;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.table.JBTable;
import com.intellij.util.Alarm;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.MessageType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;
import org.wso2.lsp4intellij.Lsp4IntellijBundle;
import org.wso2.lsp4intellij.client.languageserver.ServerStatus;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;
import org.wso2.lsp4intellij.logging.LspLogEntry;
import org.wso2.lsp4intellij.logging.LspLogListener;
import org.wso2.lsp4intellij.logging.LspLogSource;
import org.wso2.lsp4intellij.logging.LspLogStore;
import org.wso2.lsp4intellij.logging.LspTraceLevel;
import org.wso2.lsp4intellij.requests.TimeoutStats;
import org.wso2.lsp4intellij.requests.Timeouts;
import org.wso2.lsp4intellij.services.LspServerManager;
import org.wso2.lsp4intellij.utils.FileUtils;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.Box;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;

/**
 * The Inspector's content: a toolbar whose first control picks which of this project's servers is
 * being inspected, over tabs showing that server's log, timeout statistics and connected files. One
 * {@link ConsoleView} per server is created lazily and cached for as long as this panel lives.
 *
 * <p>The server picker is a toolbar dropdown rather than a tree, because a project has exactly one
 * wrapper per server definition ({@code LspServerManager.getOrCreateWrapper} files one wrapper under
 * each of its definition's extensions), so a tree of servers would be one flat row deep and would
 * spend a quarter of the tool window's width saying what one dropdown says in a line.
 *
 * <p>{@link LspLogListener#entryAdded(LspLogEntry)} fires synchronously on whichever thread produced
 * the entry, which for a traced message is an lsp4j dispatch thread, not the EDT. This panel never
 * touches Swing from that callback: it only offers the entry onto a {@link ConcurrentLinkedQueue}, and
 * a recurring {@link Alarm} request — always run on the EDT, since the alarm is created with
 * {@link Alarm.ThreadToUse#SWING_THREAD} — drains the queue in a batch every
 * {@value #FLUSH_INTERVAL_MILLIS}ms. The same tick also re-checks
 * {@link LspServerManager#allWrappers()}, so a server that starts after this panel is already open
 * still appears in the picker without a separate poller.
 *
 * <p>That timer only runs while the tool window is visible; hiding it cancels the alarm, so a console
 * nobody is looking at costs nothing. Entries published while it is hidden are discarded rather than
 * queued, because {@link #pendingEntries} would otherwise grow without limit during a verbose trace.
 * Nothing is lost: {@link LspLogStore} is the bounded source of truth, and every open console is
 * re-printed from it when the tool window becomes visible again.
 *
 * <p>Every {@link ConsoleView} created here, the {@link MessageBusConnection} and the {@link Alarm}
 * are registered as children of this panel's own {@link Disposable}, so
 * {@link LspInspectorToolWindowFactory} disposing this panel tears down all three.
 */
public final class LspInspectorPanel extends JPanel implements Disposable {

    private static final int FLUSH_INTERVAL_MILLIS = 100;
    /**
     * The most entries queued between two flushes. Past it, the queue is dropped and the consoles are
     * re-printed from the bounded {@link LspLogStore}, so a burst of traffic neither grows the queue
     * without limit nor keeps the EDT busy printing it.
     */
    static final int MAX_PENDING_ENTRIES = 2_000;
    private static final JBColor SUCCESS_COLOR =
            JBColor.namedColor("Label.successForeground", new JBColor(0x368746, 0x5FB865));
    static final JBColor ERROR_COLOR =
            JBColor.namedColor("Label.errorForeground", new JBColor(0xC7222D, 0xFF5261));
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String NO_SERVERS_TEXT = Lsp4IntellijBundle.message("inspector.empty.no.servers");
    private static final String ALL_SOURCES_TEXT = Lsp4IntellijBundle.message("inspector.filter.source.all");

    private final Project project;
    private final JPanel logPanel = new JPanel(new BorderLayout());
    private final ConnectedFilesPanel connectedFilesPanel;
    private final TimeoutsPanel timeoutsPanel = new TimeoutsPanel();
    private final transient ServerInfoPanel serverInfoPanel = new ServerInfoPanel();
    private final RequestsPanel requestsPanel = new RequestsPanel();
    private final JTabbedPane tabs = new JTabbedPane();
    private final Map<LanguageServerWrapper, ConsoleView> consoles = new HashMap<>();
    private final Set<LanguageServerWrapper> knownWrappers = new LinkedHashSet<>();
    private final ConcurrentLinkedQueue<PendingEntry> pendingEntries = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingEntryCount = new AtomicInteger();
    private volatile boolean pendingEntriesOverflowed;
    private final ConcurrentLinkedQueue<LanguageServerWrapper> pendingClears = new ConcurrentLinkedQueue<>();
    private final Alarm flushAlarm;
    private final ToolWindow toolWindow;

    private transient LspInspectorFilter filter = LspInspectorFilter.ALL;
    // The fields below are read by the toolbar actions' update() on a background thread.
    private volatile String levelFilterLabel = Lsp4IntellijBundle.message("inspector.filter.level.all");
    private volatile String sourceFilterLabel = ALL_SOURCES_TEXT;
    // Read by the message bus listener on JSON-RPC threads, so events are dropped while hidden.
    private volatile boolean flushScheduled = false;
    @Nullable
    private transient volatile LanguageServerWrapper selectedWrapper;
    // knownWrappers.size(), kept separately because the set itself is only safe on the EDT.
    private volatile int knownWrapperCount = 0;

    public LspInspectorPanel(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        super(new BorderLayout());
        this.project = project;
        this.toolWindow = toolWindow;
        this.connectedFilesPanel = new ConnectedFilesPanel(project);

        tabs.addTab(Lsp4IntellijBundle.message("inspector.tab.log"), logPanel);
        tabs.addTab(Lsp4IntellijBundle.message("inspector.tab.server.info"), serverInfoPanel);
        tabs.addTab(Lsp4IntellijBundle.message("inspector.tab.requests"), requestsPanel);
        tabs.addTab(Lsp4IntellijBundle.message("inspector.tab.timeouts"), timeoutsPanel);
        tabs.addTab(Lsp4IntellijBundle.message("inspector.tab.connected.files"), connectedFilesPanel);
        tabs.addChangeListener(e -> refreshSelectedTab());

        add(buildToolbar().getComponent(), BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        showEmptyState(NO_SERVERS_TEXT);

        flushAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
        MessageBusConnection connection = project.getMessageBus().connect(this);
        connection.subscribe(LspLogListener.TOPIC, new LspLogListener() {
            @Override
            public void entryAdded(@NotNull LanguageServerWrapper wrapper, @NotNull LspLogEntry entry) {
                // While hidden nothing drains the queue; showing the window reprints from LspLogStore.
                if (!flushScheduled) {
                    return;
                }
                if (pendingEntryCount.incrementAndGet() > MAX_PENDING_ENTRIES) {
                    pendingEntryCount.decrementAndGet();
                    pendingEntriesOverflowed = true;
                    return;
                }
                pendingEntries.offer(new PendingEntry(wrapper, entry));
            }

            @Override
            public void serverCleared(@NotNull LanguageServerWrapper wrapper) {
                if (flushScheduled) {
                    pendingClears.offer(wrapper);
                }
            }
        });

        connection.subscribe(ToolWindowManagerListener.TOPIC, new ToolWindowManagerListener() {
            @Override
            public void stateChanged(@NotNull ToolWindowManager toolWindowManager) {
                updateFlushState();
            }
        });

        refreshKnownWrappers();
        refreshServers();
        updateFlushState();
    }

    @Override
    public void dispose() {
        // Every ConsoleView, the message bus connection and the flush alarm are registered as
        // children of this Disposable and are torn down by the platform's Disposer.
    }

    /**
     * Starts or stops the flush timer to match the tool window's visibility, so a hidden console costs
     * nothing. Runs on EDT, both from the constructor and from {@link ToolWindowManagerListener}.
     *
     * <p>While stopped, entries published by {@link LspLogStore} are discarded rather than queued: the
     * store is the source of truth and is bounded, so on becoming visible again every open console is
     * re-printed from it. Queueing instead would let {@link #pendingEntries} grow without limit behind
     * a hidden tool window during a verbose trace.
     */
    void updateFlushState() {
        boolean visible = toolWindow.isVisible();
        if (visible == flushScheduled) {
            return;
        }
        if (visible) {
            flushScheduled = true;
            discardPendingEntries();
            pendingClears.clear();
            refreshKnownWrappers();
            pruneReplacedWrappers();
            refreshServers();
            reprintAllConsoles();
            refreshSelectedTab();
            scheduleFlush();
        } else {
            flushScheduled = false;
            flushAlarm.cancelAllRequests();
            discardPendingEntries();
            pendingClears.clear();
        }
    }

    private void scheduleFlush() {
        flushAlarm.addRequest(this::flush, FLUSH_INTERVAL_MILLIS);
    }

    void flush() {
        if (!flushScheduled) {
            return;
        }
        drainPendingClears();
        drainPendingEntries();
        if (refreshKnownWrappers()) {
            refreshServers();
        }
        refreshSelectedTab();
        scheduleFlush();
    }

    private void drainPendingClears() {
        LanguageServerWrapper wrapper;
        while ((wrapper = pendingClears.poll()) != null) {
            if (isLive(wrapper)) {
                ConsoleView console = consoles.get(wrapper);
                if (console != null) {
                    console.clear();
                }
            } else {
                forgetWrapper(wrapper);
            }
        }
    }

    private void drainPendingEntries() {
        if (pendingEntriesOverflowed) {
            pendingEntriesOverflowed = false;
            discardPendingEntries();
            reprintAllConsoles();
            return;
        }
        PendingEntry pending;
        while ((pending = pendingEntries.poll()) != null) {
            pendingEntryCount.decrementAndGet();
            // A wrapper without a console yet is skipped: its console is primed from the full
            // LspLogStore buffer when it is first shown.
            ConsoleView console = consoles.get(pending.wrapper());
            if (console != null && filter.matches(pending.entry())) {
                printEntry(console, pending.entry());
            }
        }
    }

    private void discardPendingEntries() {
        while (pendingEntries.poll() != null) {
            pendingEntryCount.decrementAndGet();
        }
    }

    /**
     * Adds every wrapper {@link LspServerManager} currently knows about to {@link #knownWrappers}.
     * Never removes one, so a server stays in the server selector after it stops: a server that has
     * just died is the one whose log someone is most likely to want to read, matching how
     * {@link LspLogStore} keeps a stopped server's buffer around. {@link #forgetWrapper} removes it
     * once its buffer is gone. Returns whether the set grew.
     */
    private boolean refreshKnownWrappers() {
        LspServerManager manager = LspServerManager.getInstanceIfCreated(project);
        Set<LanguageServerWrapper> current = manager == null ? Set.of() : manager.allWrappers();
        boolean grew = knownWrappers.addAll(current);
        knownWrapperCount = knownWrappers.size();
        return grew;
    }

    /**
     * Forgets disposed wrappers whose buffer is gone. Their {@code serverCleared} event is dropped
     * while this window is hidden, so this catches up when it is shown again.
     */
    private void pruneReplacedWrappers() {
        LspLogStore store = LspLogStore.getInstanceIfCreated(project);
        for (LanguageServerWrapper wrapper : List.copyOf(knownWrappers)) {
            if (!isLive(wrapper) && (store == null || store.entries(wrapper).isEmpty())) {
                forgetWrapper(wrapper);
            }
        }
    }

    private boolean isLive(@NotNull LanguageServerWrapper wrapper) {
        LspServerManager manager = LspServerManager.getInstanceIfCreated(project);
        return manager != null && manager.allWrappers().contains(wrapper);
    }

    /**
     * Drops a disposed wrapper whose log buffer was removed, either because a newer wrapper for the
     * same server replaced it or because it was cleared explicitly.
     */
    private void forgetWrapper(@NotNull LanguageServerWrapper wrapper) {
        knownWrappers.remove(wrapper);
        knownWrapperCount = knownWrappers.size();
        ConsoleView console = consoles.remove(wrapper);
        if (console != null) {
            Disposer.dispose(console);
        }
        if (wrapper == selectedWrapper) {
            selectedWrapper = null;
        }
        refreshServers();
    }

    /**
     * Makes sure something is selected: the first of {@link #knownWrappers}, if this panel has no
     * selection yet. Without that the tabs would render their
     * empty states even though a server is running, since the dropdown starts empty.
     */
    private void refreshServers() {
        if (selectedWrapper == null || !knownWrappers.contains(selectedWrapper)) {
            selectServer(knownWrappers.stream().findFirst().orElse(null));
        }
    }

    /**
     * Shows {@code wrapper}'s log, timeouts and connected files, and makes the toolbar dropdown read
     * its name. Passing null returns the panel to its empty state.
     *
     * <p>Also the one entry point {@link org.wso2.lsp4intellij.statusbar.LSPServerStatusWidget} uses to
     * jump here for a specific server, so it never has to reach into this panel's internals. Adds
     * {@code wrapper} to {@link #knownWrappers} first, in case this panel has not ticked since that
     * server started. Must be called on the EDT, like every other method on this Swing component.
     */
    public void selectServer(@Nullable LanguageServerWrapper wrapper) {
        if (wrapper != null) {
            knownWrappers.add(wrapper);
            knownWrapperCount = knownWrappers.size();
        }
        selectedWrapper = wrapper;
        showConsoleFor(wrapper);
        refreshSelectedTab();
    }

    /**
     * Switches to the Requests tab, for the status bar widget's waiting-requests shortcut. Must be
     * called on the EDT.
     */
    public void showRequestsTab() {
        tabs.setSelectedComponent(requestsPanel);
    }

    /**
     * Switches to the Log tab showing only errors from every source, for the status bar widget's
     * recent-crashes shortcut. Crashes are logged at error level. Must be called on the EDT.
     */
    public void showErrors() {
        levelFilterLabel = Lsp4IntellijBundle.message("inspector.filter.level.error");
        sourceFilterLabel = ALL_SOURCES_TEXT;
        filter = new LspInspectorFilter(MessageType.Error, null);
        reprintSelected();
        tabs.setSelectedComponent(logPanel);
    }

    /**
     * Refreshes the Server Info, Requests, Timeouts or Connected Files tab if it is the one showing. They are
     * polled on every flush, so hidden ones are skipped and catch up when selected.
     */
    private void refreshSelectedTab() {
        Component selected = tabs.getSelectedComponent();
        if (selected == serverInfoPanel) {
            serverInfoPanel.refresh(getSelectedWrapper());
        } else if (selected == requestsPanel) {
            requestsPanel.refresh(getSelectedWrapper());
        } else if (selected == timeoutsPanel) {
            timeoutsPanel.refresh(getSelectedWrapper());
        } else if (selected == connectedFilesPanel) {
            connectedFilesPanel.refresh(getSelectedWrapper());
        }
    }

    @Nullable
    private LanguageServerWrapper getSelectedWrapper() {
        return selectedWrapper;
    }

    private void showConsoleFor(@Nullable LanguageServerWrapper wrapper) {
        if (wrapper == null) {
            showEmptyState(NO_SERVERS_TEXT);
            return;
        }
        ConsoleView console = consoles.computeIfAbsent(wrapper, this::createConsole);
        showComponent(console.getComponent());
    }

    @NotNull
    private ConsoleView createConsole(@NotNull LanguageServerWrapper wrapper) {
        ConsoleView console = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
        Disposer.register(this, console);
        LspLogStore store = LspLogStore.getInstanceIfCreated(project);
        if (store != null) {
            for (LspLogEntry entry : store.entries(wrapper)) {
                if (filter.matches(entry)) {
                    printEntry(console, entry);
                }
            }
        }
        return console;
    }

    /**
     * Re-prints the currently selected server's console from {@link LspLogStore#entries}, applying
     * the current filter. Called whenever the level or source filter changes; affordable because the
     * buffer is bounded at {@link LspLogStore#CAPACITY}.
     */
    private void reprintSelected() {
        LanguageServerWrapper wrapper = getSelectedWrapper();
        ConsoleView console = wrapper == null ? null : consoles.get(wrapper);
        if (wrapper != null && console != null) {
            reprintConsole(wrapper, console);
        }
    }

    /**
     * Re-prints every console this panel has already created. Used when the tool window becomes
     * visible again, since entries published while it was hidden were discarded rather than queued.
     */
    private void reprintAllConsoles() {
        consoles.forEach(this::reprintConsole);
    }

    private void reprintConsole(@NotNull LanguageServerWrapper wrapper, @NotNull ConsoleView console) {
        console.clear();
        LspLogStore store = LspLogStore.getInstanceIfCreated(project);
        if (store == null) {
            return;
        }
        for (LspLogEntry entry : store.entries(wrapper)) {
            if (filter.matches(entry)) {
                printEntry(console, entry);
            }
        }
    }

    private void printEntry(@NotNull ConsoleView console, @NotNull LspLogEntry entry) {
        console.print(LspLogText.line(entry, TIMESTAMP_FORMAT, ZoneId.systemDefault()) + "\n",
                contentTypeFor(entry.level()));
        String detail = LspLogText.detail(entry);
        if (detail != null) {
            console.print(detail + "\n", ConsoleViewContentType.SYSTEM_OUTPUT);
        }
    }

    @NotNull
    private static ConsoleViewContentType contentTypeFor(@NotNull MessageType level) {
        return switch (level) {
            case Error -> ConsoleViewContentType.ERROR_OUTPUT;
            case Warning -> ConsoleViewContentType.LOG_WARNING_OUTPUT;
            case Info -> ConsoleViewContentType.LOG_INFO_OUTPUT;
            case Log -> ConsoleViewContentType.NORMAL_OUTPUT;
        };
    }

    private void showEmptyState(@NotNull String message) {
        JBLabel label = new JBLabel(message, SwingConstants.CENTER);
        showComponent(label);
    }

    private void showComponent(@NotNull JComponent component) {
        BorderLayout layout = (BorderLayout) logPanel.getLayout();
        Component current = layout.getLayoutComponent(BorderLayout.CENTER);
        if (current != null) {
            logPanel.remove(current);
        }
        logPanel.add(component, BorderLayout.CENTER);
        logPanel.revalidate();
        logPanel.repaint();
    }

    @NotNull
    private ActionToolbar buildToolbar() {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new ServerSelectorAction());
        group.addSeparator();
        group.add(new LevelFilterAction());
        group.add(new SourceFilterAction());
        group.add(new TraceLevelAction());
        group.add(new ClearAction());
        group.add(new ExportLogAction());
        group.add(new ScrollToEndAction());
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("LspInspector", group, true);
        toolbar.setTargetComponent(this);
        return toolbar;
    }

    /**
     * "Connected Files" tab content: the files connected to whichever server is selected in the
     * server selector, replacing what used to be the {@code ShowConnectedFiles} modal dialog on the
     * status bar widget. Refreshed via {@link #refresh(LanguageServerWrapper)} when the server
     * selection or the selected tab changes, and on every {@link #flush()} tick while this tab is
     * showing, so the list does not go stale while the selection has not changed.
     *
     * <p>Each entry is rendered as its file-type icon and file name with the containing directory as
     * dimmed secondary text, rather than the full URI {@link LanguageServerWrapper#getConnectedFiles()}
     * returns - the same primary/secondary split the IDE's own file lists use. Double-click or Enter
     * opens the file in the editor. Entries are sorted so the list does not reorder itself on every
     * refresh, and a {@link ListSpeedSearch} lets someone jump to a file by typing part of its name.
     */
    private static final class ConnectedFilesPanel extends JPanel {

        private static final String NO_SERVER_TEXT = Lsp4IntellijBundle.message("inspector.files.no.server");
        private static final String NO_FILES_TEXT = Lsp4IntellijBundle.message("inspector.files.none");

        private final transient Project project;
        private final DefaultListModel<String> model = new DefaultListModel<>();
        private final JBList<String> list;
        private List<String> shown = List.of();
        // Resolved once per change of the file set, not on every paint; null for a URI with no file.
        private transient Map<String, VirtualFile> resolved = Map.of();

        ConnectedFilesPanel(@NotNull Project project) {
            super(new BorderLayout());
            this.project = project;
            list = new JBList<>(model);
            list.setCellRenderer(new ConnectedFileRenderer());
            list.getEmptyText().setText(NO_SERVER_TEXT);
            ListSpeedSearch.installOn(list);
            new DoubleClickListener() {
                @Override
                protected boolean onDoubleClick(@NotNull MouseEvent event) {
                    return openSelected();
                }
            }.installOn(list);
            list.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent event) {
                    if (event.getKeyCode() == KeyEvent.VK_ENTER && openSelected()) {
                        event.consume();
                    }
                }
            });
            add(new JBScrollPane(list), BorderLayout.CENTER);
        }

        private boolean openSelected() {
            String uri = list.getSelectedValue();
            VirtualFile file = uri == null ? null : resolved.get(uri);
            if (file == null || !file.isValid()) {
                return false;
            }
            FileEditorManager.getInstance(project).openFile(file, true);
            return true;
        }

        /**
         * Re-reads the selected server's connected files. Called on every flush tick, so the model is
         * only rebuilt when the file set actually changed: rebuilding it unconditionally would discard
         * the user's list selection and any in-progress speed search ten times a second.
         */
        void refresh(@Nullable LanguageServerWrapper wrapper) {
            list.getEmptyText().setText(wrapper == null ? NO_SERVER_TEXT : NO_FILES_TEXT);
            List<String> files = wrapper == null
                    ? List.of()
                    : wrapper.getConnectedFiles().stream().sorted().toList();
            if (files.equals(shown)) {
                return;
            }
            shown = files;
            Map<String, VirtualFile> byUri = new HashMap<>();
            files.forEach(uri -> byUri.put(uri, FileUtils.virtualFileFromURI(uri)));
            resolved = byUri;
            model.clear();
            files.forEach(model::addElement);
        }

        private final class ConnectedFileRenderer extends ColoredListCellRenderer<String> {

            @Override
            protected void customizeCellRenderer(@NotNull JList<? extends String> list, String value, int index,
                    boolean selected, boolean hasFocus) {
                VirtualFile file = resolved.get(value);
                if (file != null) {
                    setIcon(file.getFileType().getIcon());
                    append(file.getName());
                    VirtualFile parent = file.getParent();
                    if (parent != null) {
                        append("  " + parent.getPresentableUrl(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    }
                    return;
                }
                int slash = value.lastIndexOf('/');
                String name = slash < 0 ? value : value.substring(slash + 1);
                String directory = slash < 0 ? null : value.substring(0, slash);
                append(name);
                if (directory != null && !directory.isEmpty()) {
                    append("  " + directory, SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
            }
        }
    }

    /**
     * "Server Info" tab content, split in two. Left: the server's name, status and reported version,
     * its {@link ServerInfo#details}, its definition, and buttons to copy the definition or restart
     * the server. Right: the requests lsp4intellij can send grouped by area, each marked supported or
     * not, with a toggle to show only the unsupported ones. Polled on every {@link #flush()} tick
     * while showing, since status, crash count and trace level change at runtime; each side is only
     * rebuilt when something it shows changed.
     */
    private static final class ServerInfoPanel extends OnePixelSplitter {

        private final JBScrollPane summaryScroll = new JBScrollPane();
        private final JBScrollPane capabilitiesScroll = new JBScrollPane();
        private final JBLabel supportedCount = greyLabel("");
        private final JBCheckBox unsupportedOnly =
                new JBCheckBox(Lsp4IntellijBundle.message("inspector.info.unsupported.only"));
        @Nullable
        private transient LanguageServerWrapper wrapper;
        private transient State shown;
        private boolean built = false;

        /**
         * Everything the tab shows, compared on each tick to decide whether to rebuild it.
         *
         * @param title        the server's display name
         * @param version      the version it reported, or {@link ServerInfo#NONE}
         * @param status       its current status
         * @param definition   its definition's {@code toString()}, which includes the launch command
         * @param details      {@link ServerInfo#details}
         * @param capabilities {@link ServerInfo#capabilities}, empty before initialization
         */
        private record State(@NotNull String title, @NotNull String version, @NotNull ServerStatus status,
                @NotNull String definition, @NotNull List<ServerInfo.Row> details,
                @NotNull List<ServerInfo.CapabilityRow> capabilities) {
        }

        ServerInfoPanel() {
            super(false, 0.35f);
            summaryScroll.setBorder(JBUI.Borders.empty());
            capabilitiesScroll.setBorder(JBUI.Borders.empty());
            unsupportedOnly.addActionListener(e -> showCapabilities());

            JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
            JBLabel title = new JBLabel(Lsp4IntellijBundle.message("inspector.info.capabilities"));
            title.setFont(JBFont.label().asBold());
            bar.add(title);
            bar.add(supportedCount);
            bar.add(unsupportedOnly);
            bar.setBorder(JBUI.Borders.empty(6, 4));
            JPanel right = new JPanel(new BorderLayout());
            right.add(bar, BorderLayout.NORTH);
            right.add(capabilitiesScroll, BorderLayout.CENTER);

            setFirstComponent(summaryScroll);
            setSecondComponent(right);
        }

        void refresh(@Nullable LanguageServerWrapper selected) {
            State state = selected == null ? null : stateOf(selected);
            if (built && selected == wrapper && Objects.equals(state, shown)) {
                return;
            }
            built = true;
            wrapper = selected;
            shown = state;
            summaryScroll.setViewportView(state == null ? centeredGreyLabel(NO_SERVERS_TEXT) : summary(state));
            showCapabilities();
        }

        @NotNull
        private static State stateOf(@NotNull LanguageServerWrapper wrapper) {
            InitializeResult result = wrapper.getInitializeResult();
            return new State(wrapper.getServerDefinition().getDisplayName(), ServerInfo.version(wrapper),
                    wrapper.getStatus(), wrapper.getServerDefinition().toString(), ServerInfo.details(wrapper),
                    ServerInfo.capabilities(result == null ? null : result.getCapabilities()));
        }

        @NotNull
        private JComponent summary(@NotNull State state) {
            JBLabel name = new JBLabel(state.title());
            name.setFont(JBFont.h3().asBold());
            JBLabel status = new JBLabel("● " + ServerInfo.statusText(state.status()));
            status.setForeground(state.status() == ServerStatus.INITIALIZED
                    ? SUCCESS_COLOR : SimpleTextAttributes.GRAYED_ATTRIBUTES.getFgColor());
            JPanel title = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(10), 0));
            title.add(name);
            title.add(status);

            FormBuilder form = FormBuilder.createFormBuilder().addComponent(title);
            if (!ServerInfo.NONE.equals(state.version())) {
                form.addComponent(greyLabel(state.version()));
            }
            form.addVerticalGap(JBUI.scale(8));
            for (ServerInfo.Row row : state.details()) {
                JBLabel value = new JBLabel(row.value());
                value.setCopyable(true);
                form.addLabeledComponent(greyLabel(row.name()), value);
            }
            form.addVerticalGap(JBUI.scale(8));
            form.addComponent(greyLabel(Lsp4IntellijBundle.message("inspector.info.definition")));
            JBTextArea definition = new JBTextArea(state.definition());
            definition.setEditable(false);
            definition.setLineWrap(true);
            definition.setOpaque(false);
            definition.setBorder(JBUI.Borders.empty());
            definition.setFont(JBFont.label());
            form.addComponent(definition);

            JButton copy = new JButton(Lsp4IntellijBundle.message("inspector.info.copy"));
            copy.addActionListener(e -> CopyPasteManager.getInstance()
                    .setContents(new StringSelection(state.definition())));
            JButton restart = new JButton(Lsp4IntellijBundle.message("inspector.info.restart"));
            LanguageServerWrapper target = wrapper;
            restart.addActionListener(e -> {
                if (target != null) {
                    target.restart();
                }
            });
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            buttons.add(copy);
            buttons.add(Box.createHorizontalStrut(JBUI.scale(6)));
            buttons.add(restart);
            form.addVerticalGap(JBUI.scale(4));
            form.addComponent(buttons);
            return topAligned(form.getPanel());
        }

        /**
         * Rebuilds the right side from {@link #shown}: also called when the toggle changes, which does
         * not change the state.
         */
        private void showCapabilities() {
            List<ServerInfo.CapabilityRow> rows = shown == null ? List.of() : shown.capabilities();
            long supported = rows.stream().filter(ServerInfo.CapabilityRow::supported).count();
            supportedCount.setText(rows.isEmpty() ? ""
                    : Lsp4IntellijBundle.message("inspector.info.supported.count", supported, rows.size()));
            unsupportedOnly.setEnabled(!rows.isEmpty());
            if (shown == null) {
                capabilitiesScroll.setViewportView(null);
                return;
            }
            if (rows.isEmpty()) {
                capabilitiesScroll.setViewportView(
                        centeredGreyLabel(Lsp4IntellijBundle.message("inspector.info.not.initialized")));
                return;
            }
            FormBuilder form = FormBuilder.createFormBuilder();
            String group = null;
            for (ServerInfo.CapabilityRow row : rows) {
                if (unsupportedOnly.isSelected() && row.supported()) {
                    continue;
                }
                if (!row.group().equals(group)) {
                    group = row.group();
                    form.addComponent(new TitledSeparator(group));
                }
                JBLabel feature = new JBLabel(row.label(), row.supported()
                        ? AllIcons.RunConfigurations.TestPassed
                        : AllIcons.RunConfigurations.TestIgnored, SwingConstants.LEFT);
                feature.setToolTipText(row.method());
                if (!row.supported()) {
                    feature.setForeground(SimpleTextAttributes.GRAYED_ATTRIBUTES.getFgColor());
                }
                feature.setBorder(JBUI.Borders.emptyLeft(8));
                form.addComponent(feature);
            }
            if (group == null) {
                capabilitiesScroll.setViewportView(
                        centeredGreyLabel(Lsp4IntellijBundle.message("inspector.info.all.supported")));
                return;
            }
            capabilitiesScroll.setViewportView(topAligned(form.getPanel()));
        }

        @NotNull
        private static JComponent topAligned(@NotNull JPanel content) {
            content.setBorder(JBUI.Borders.empty(8, 12));
            // Keeps the content at its preferred height instead of stretching it over the viewport.
            JPanel top = new JPanel(new BorderLayout());
            top.add(content, BorderLayout.NORTH);
            return top;
        }

        @NotNull
        private static JBLabel greyLabel(@NotNull String text) {
            JBLabel label = new JBLabel(text);
            label.setForeground(SimpleTextAttributes.GRAYED_ATTRIBUTES.getFgColor());
            return label;
        }

        @NotNull
        private static JComponent centeredGreyLabel(@NotNull String text) {
            JBLabel label = new JBLabel(text, SwingConstants.CENTER);
            label.setForeground(SimpleTextAttributes.GRAYED_ATTRIBUTES.getFgColor());
            return label;
        }
    }

    /**
     * Centres the header text of every column from {@code firstColumn} on, matching the centred
     * cell renderers below them. Wraps whatever renderer the look and feel already installed
     * rather than replacing it, so the header keeps its native painting and only the alignment
     * changes.
     */
    static void centerHeadersFromColumn(@NotNull JBTable table, int firstColumn) {
        TableCellRenderer original = table.getTableHeader().getDefaultRenderer();
        table.getTableHeader().setDefaultRenderer((tbl, value, selected, focused, row, column) -> {
            Component component =
                    original.getTableCellRendererComponent(tbl, value, selected, focused, row, column);
            if (component instanceof JLabel label) {
                label.setHorizontalAlignment(column >= firstColumn ? SwingConstants.CENTER : SwingConstants.LEFT);
            }
            return component;
        });
    }

    /**
     * "Timeouts" tab content: the {@link TimeoutStats} counters for whichever server is selected in
     * the server selector, replacing what used to be the {@code ShowTimeouts} modal dialog on the status bar
     * widget. A table rather than formatted text so the failure rate can be colored per row - gray
     * for a request kind nothing has exercised yet, green once every call has succeeded, red once any
     * has failed - the same at-a-glance signal the old dialog gave with inline HTML, without building
     * HTML by hand.
     *
     * <p>Refreshed via {@link #refresh(LanguageServerWrapper)} when the server selection or the
     * selected tab changes, and on every {@link #flush()} tick while this tab is showing, so the
     * counters do not go stale while the selection has not changed.
     */
    private static final class TimeoutsPanel extends JPanel {

        private static final String[] COLUMNS = {
                Lsp4IntellijBundle.message("inspector.timeouts.column.request"),
                Lsp4IntellijBundle.message("inspector.timeouts.column.total"),
                Lsp4IntellijBundle.message("inspector.timeouts.column.success"),
                Lsp4IntellijBundle.message("inspector.timeouts.column.failures"),
                Lsp4IntellijBundle.message("inspector.timeouts.column.failure.rate")};

        private static final int TOTAL_COLUMN = 1;
        private static final int SUCCESS_COLUMN = 2;
        private static final int FAILURES_COLUMN = 3;

        private final List<Timeouts> rows = List.of(Timeouts.values());
        private final transient Object[][] data = new Object[rows.size()][COLUMNS.length];
        private final AbstractTableModel model = new AbstractTableModel() {
            @Override
            public int getRowCount() {
                return rows.size();
            }

            @Override
            public int getColumnCount() {
                return COLUMNS.length;
            }

            @Override
            public String getColumnName(int column) {
                return COLUMNS[column];
            }

            @Override
            public Object getValueAt(int row, int column) {
                return data[row][column];
            }

            @Override
            public Class<?> getColumnClass(int column) {
                return column == 0 ? String.class : Integer.class;
            }
        };

        TimeoutsPanel() {
            super(new BorderLayout());
            for (int row = 0; row < rows.size(); row++) {
                data[row][0] = displayName(rows.get(row));
                data[row][1] = 0;
                data[row][2] = 0;
                data[row][3] = 0;
            }
            JBTable table = new JBTable(model);
            table.setStriped(true);
            table.setShowGrid(false);
            table.getColumnModel().getColumn(0).setPreferredWidth(140);
            DefaultTableCellRenderer centered = new DefaultTableCellRenderer();
            centered.setHorizontalAlignment(SwingConstants.CENTER);
            table.getColumnModel().getColumn(1).setCellRenderer(centered);
            table.getColumnModel().getColumn(2).setCellRenderer(centered);
            table.getColumnModel().getColumn(3).setCellRenderer(centered);
            table.getColumnModel().getColumn(4).setCellRenderer(new FailureRateRenderer());
            centerHeadersFromColumn(table, 1);
            add(new JBScrollPane(table), BorderLayout.CENTER);
        }

        void refresh(@Nullable LanguageServerWrapper wrapper) {
            TimeoutStats stats = wrapper == null ? null : wrapper.getTimeoutStats();
            for (int row = 0; row < rows.size(); row++) {
                Timeouts timeout = rows.get(row);
                int success = stats == null ? 0 : stats.getSuccessCount(timeout);
                int failures = stats == null ? 0 : stats.getFailureCount(timeout);
                if (data[row][SUCCESS_COLUMN].equals(success) && data[row][FAILURES_COLUMN].equals(failures)) {
                    continue;
                }
                data[row][TOTAL_COLUMN] = success + failures;
                data[row][SUCCESS_COLUMN] = success;
                data[row][FAILURES_COLUMN] = failures;
                // Per row rather than fireTableDataChanged(), which would clear the table's selection.
                model.fireTableRowsUpdated(row, row);
            }
        }

        @NotNull
        private static String displayName(@NotNull Timeouts timeout) {
            String name = timeout.name();
            return name.charAt(0) + name.substring(1).toLowerCase();
        }

        /**
         * The "Failure Rate" column has no backing data of its own - it is computed from the
         * "Success"/"Failures" columns of the same row at paint time - so it is rendered rather than
         * modeled, coloring the text to draw the eye to whichever request kind is actually failing.
         */
        private static final class FailureRateRenderer extends DefaultTableCellRenderer {


            FailureRateRenderer() {
                setHorizontalAlignment(SwingConstants.CENTER);
            }

            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                    boolean hasFocus, int row, int column) {
                int success = (Integer) table.getModel().getValueAt(row, SUCCESS_COLUMN);
                int failures = (Integer) table.getModel().getValueAt(row, FAILURES_COLUMN);
                int total = success + failures;
                String text = total == 0 ? "—" : String.format("%.0f%%", 100.0 * failures / total);
                Component component = super.getTableCellRendererComponent(table, text, isSelected, hasFocus, row,
                        column);
                if (!isSelected) {
                    component.setForeground(foregroundFor(total, failures));
                }
                return component;
            }

            @NotNull
            private static Color foregroundFor(int total, int failures) {
                if (total == 0) {
                    return JBColor.GRAY;
                }
                return failures == 0 ? SUCCESS_COLOR : ERROR_COLOR;
            }
        }
    }

    /**
     * Picks which server the tabs below show. Replaces what used to be a server tree down the left
     * side of the panel: a project has one wrapper per server definition, so that tree was never more
     * than a flat list, and usually a list of one.
     */
    private final class ServerSelectorAction extends ComboBoxAction implements DumbAware {

        ServerSelectorAction() {
            getTemplatePresentation().setIcon(AllIcons.Nodes.Console);
        }

        @NotNull
        @Override
        public ActionUpdateThread getActionUpdateThread() {
            // update() reads only volatile fields.
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            LanguageServerWrapper wrapper = getSelectedWrapper();
            e.getPresentation().setText(wrapper == null
                    ? Lsp4IntellijBundle.message("inspector.server.none")
                    : wrapper.getServerDefinition().getDisplayName());
            e.getPresentation().setDescription(wrapper == null ? null : wrapper.getServerDefinition().toString());
            // Nothing to choose between while this project runs a single server.
            e.getPresentation().setEnabled(knownWrapperCount > 1);
        }

        @NotNull
        @Override
        protected DefaultActionGroup createPopupActionGroup(@NotNull JComponent button, @NotNull DataContext context) {
            DefaultActionGroup popup = new DefaultActionGroup();
            for (LanguageServerWrapper wrapper : knownWrappers) {
                popup.add(new AnAction(wrapper.getServerDefinition().getDisplayName()) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        selectServer(wrapper);
                    }
                });
            }
            return popup;
        }
    }

    private final class LevelFilterAction extends ComboBoxAction implements DumbAware {

        private final List<Option> options = List.of(
                new Option(Lsp4IntellijBundle.message("inspector.filter.level.all"), null),
                new Option(Lsp4IntellijBundle.message("inspector.filter.level.error"), MessageType.Error),
                new Option(Lsp4IntellijBundle.message("inspector.filter.level.warning"), MessageType.Warning),
                new Option(Lsp4IntellijBundle.message("inspector.filter.level.info"), MessageType.Info));

        private record Option(@NotNull String label, @Nullable MessageType minLevel) {
        }

        LevelFilterAction() {
            getTemplatePresentation().setIcon(AllIcons.General.Filter);
        }

        @NotNull
        @Override
        public ActionUpdateThread getActionUpdateThread() {
            // update() reads only volatile fields.
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setText(levelFilterLabel);
        }

        @NotNull
        @Override
        protected DefaultActionGroup createPopupActionGroup(@NotNull JComponent button, @NotNull DataContext context) {
            DefaultActionGroup popup = new DefaultActionGroup();
            for (Option option : options) {
                popup.add(new AnAction(option.label()) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        levelFilterLabel = option.label();
                        filter = new LspInspectorFilter(option.minLevel(), filter.source());
                        reprintSelected();
                    }
                });
            }
            return popup;
        }
    }

    private final class SourceFilterAction extends ComboBoxAction implements DumbAware {

        private final List<Option> options = List.of(
                new Option(ALL_SOURCES_TEXT, null),
                new Option(Lsp4IntellijBundle.message("inspector.filter.source.server.log"), LspLogSource.SERVER_LOG),
                new Option(Lsp4IntellijBundle.message("inspector.filter.source.server.message"),
                        LspLogSource.SERVER_MESSAGE),
                new Option(Lsp4IntellijBundle.message("inspector.filter.source.lifecycle"), LspLogSource.LIFECYCLE),
                new Option(Lsp4IntellijBundle.message("inspector.filter.source.trace.out"), LspLogSource.TRACE_OUT),
                new Option(Lsp4IntellijBundle.message("inspector.filter.source.trace.in"), LspLogSource.TRACE_IN));

        private record Option(@NotNull String label, @Nullable LspLogSource source) {
        }

        SourceFilterAction() {
            getTemplatePresentation().setIcon(AllIcons.General.Filter);
        }

        @NotNull
        @Override
        public ActionUpdateThread getActionUpdateThread() {
            // update() reads only volatile fields.
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setText(sourceFilterLabel);
        }

        @NotNull
        @Override
        protected DefaultActionGroup createPopupActionGroup(@NotNull JComponent button, @NotNull DataContext context) {
            DefaultActionGroup popup = new DefaultActionGroup();
            for (Option option : options) {
                popup.add(new AnAction(option.label()) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        sourceFilterLabel = option.label();
                        filter = new LspInspectorFilter(filter.minLevel(), option.source());
                        reprintSelected();
                    }
                });
            }
            return popup;
        }
    }

    /**
     * Sets the JSON-RPC trace level for the selected server via
     * {@link LanguageServerWrapper#setTraceLevel(LspTraceLevel)}, and reflects that server's current
     * {@link LanguageServerWrapper#getTraceLevel()} in its own label. Tracks the server selector since
     * the trace level is per server.
     */
    private final class TraceLevelAction extends ComboBoxAction implements DumbAware {

        TraceLevelAction() {
            getTemplatePresentation().setIcon(AllIcons.Debugger.Console);
        }

        @NotNull
        @Override
        public ActionUpdateThread getActionUpdateThread() {
            // update() reads only volatile fields.
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            LanguageServerWrapper wrapper = getSelectedWrapper();
            e.getPresentation().setEnabled(wrapper != null);
            e.getPresentation().setText(wrapper == null
                    ? Lsp4IntellijBundle.message("inspector.trace")
                    : Lsp4IntellijBundle.message("inspector.trace.level", wrapper.getTraceLevel().name()));
        }

        @NotNull
        @Override
        protected DefaultActionGroup createPopupActionGroup(@NotNull JComponent button, @NotNull DataContext context) {
            DefaultActionGroup popup = new DefaultActionGroup();
            for (LspTraceLevel level : LspTraceLevel.values()) {
                popup.add(new AnAction(level.name()) {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        LanguageServerWrapper wrapper = getSelectedWrapper();
                        if (wrapper != null) {
                            wrapper.setTraceLevel(level);
                        }
                    }
                });
            }
            return popup;
        }
    }

    private final class ClearAction extends AnAction implements DumbAware {

        ClearAction() {
            super(Lsp4IntellijBundle.message("inspector.action.clear"),
                    Lsp4IntellijBundle.message("inspector.action.clear.description"), AllIcons.Actions.GC);
        }

        @NotNull
        @Override
        public ActionUpdateThread getActionUpdateThread() {
            // update() reads only volatile fields.
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(getSelectedWrapper() != null);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            LanguageServerWrapper wrapper = getSelectedWrapper();
            if (wrapper == null) {
                return;
            }
            LspLogStore store = LspLogStore.getInstanceIfCreated(project);
            if (store != null) {
                store.removeBuffer(wrapper);
            }
            ConsoleView console = consoles.get(wrapper);
            if (console != null) {
                console.clear();
            }
        }
    }

    /**
     * Saves the selected server's properties, capabilities and log, with the current filter applied,
     * to a file the user picks.
     */
    private final class ExportLogAction extends AnAction implements DumbAware {

        ExportLogAction() {
            super(Lsp4IntellijBundle.message("inspector.action.export"),
                    Lsp4IntellijBundle.message("inspector.action.export.description"),
                    AllIcons.ToolbarDecorator.Export);
        }

        @NotNull
        @Override
        public ActionUpdateThread getActionUpdateThread() {
            // update() reads only volatile fields.
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(getSelectedWrapper() != null);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            LanguageServerWrapper wrapper = getSelectedWrapper();
            if (wrapper == null) {
                return;
            }
            FileSaverDescriptor descriptor = new FileSaverDescriptor(
                    Lsp4IntellijBundle.message("inspector.export.title"),
                    Lsp4IntellijBundle.message("inspector.export.description"), "log");
            VirtualFileWrapper target = FileChooserFactory.getInstance()
                    .createSaveFileDialog(descriptor, project)
                    .save(wrapper.getServerDefinition().getDisplayName() + "-lsp.log");
            if (target == null) {
                return;
            }
            InitializeResult result = wrapper.getInitializeResult();
            LspLogStore store = LspLogStore.getInstanceIfCreated(project);
            String text = LspLogText.export(ServerInfo.properties(wrapper),
                    ServerInfo.capabilities(result == null ? null : result.getCapabilities()),
                    store == null ? List.of() : store.entries(wrapper), filter, ZoneId.systemDefault());
            Path path = target.getFile().toPath();
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    Files.writeString(path, text);
                } catch (IOException ex) {
                    ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(project,
                            Lsp4IntellijBundle.message("inspector.export.failed", path, ex.getMessage()),
                            Lsp4IntellijBundle.message("inspector.export.title")));
                }
            });
        }
    }

    private final class ScrollToEndAction extends AnAction implements DumbAware {

        ScrollToEndAction() {
            super(Lsp4IntellijBundle.message("inspector.action.scroll.to.end"),
                    Lsp4IntellijBundle.message("inspector.action.scroll.to.end.description"),
                    AllIcons.RunConfigurations.Scroll_down);
        }

        @NotNull
        @Override
        public ActionUpdateThread getActionUpdateThread() {
            // update() reads only volatile fields.
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(getSelectedWrapper() != null);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            LanguageServerWrapper wrapper = getSelectedWrapper();
            ConsoleView console = wrapper == null ? null : consoles.get(wrapper);
            if (console != null) {
                console.requestScrollingToEnd();
            }
        }
    }

    @TestOnly
    @NotNull
    Set<LanguageServerWrapper> knownWrappersForTest() {
        return Set.copyOf(knownWrappers);
    }

    @TestOnly
    int pendingEntryCountForTest() {
        return pendingEntries.size();
    }

    private record PendingEntry(@NotNull LanguageServerWrapper wrapper, @NotNull LspLogEntry entry) {
    }
}
