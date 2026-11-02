/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
package org.wso2.lsp4intellij.statusbar;

import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.wso2.lsp4intellij.Lsp4IntellijBundle;
import org.wso2.lsp4intellij.client.languageserver.ServerStatus;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;
import org.wso2.lsp4intellij.contributors.icon.LSPDefaultIconProvider;
import org.wso2.lsp4intellij.inspector.LspInspectorPanel;
import org.wso2.lsp4intellij.inspector.LspInspectorToolWindowFactory;
import org.wso2.lsp4intellij.logging.LspLogEntry;
import org.wso2.lsp4intellij.logging.LspLogListener;
import org.wso2.lsp4intellij.logging.LspLogSource;
import org.wso2.lsp4intellij.logging.LspTraceLevel;
import org.wso2.lsp4intellij.services.LspServerManager;
import org.wso2.lsp4intellij.utils.GUIUtils;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.swing.Icon;

public class LSPServerStatusWidget implements StatusBarWidget {

    private final Project project;

    LSPServerStatusWidget(Project project) {
        this.project = project;
    }

    public IconPresentation getPresentation() {
        return new IconPresentation();
    }

    /**
     * Repaints the widget whenever any server's status changes. Every status change is recorded as a
     * {@link LspLogSource#LIFECYCLE} entry right after the wrapper's status is set, so listening here
     * means the wrappers never have to find this widget. The icon aggregates over every server each
     * time it repaints (see {@link IconPresentation}), so which server changed is not needed.
     */
    @Override
    public void install(@NotNull StatusBar statusBar) {
        project.getMessageBus().connect(this).subscribe(LspLogListener.TOPIC, new LspLogListener() {
            @Override
            public void entryAdded(@NotNull LanguageServerWrapper wrapper, @NotNull LspLogEntry entry) {
                if (entry.source() == LspLogSource.LIFECYCLE) {
                    updateWidget();
                }
            }

            @Override
            public void serverCleared(@NotNull LanguageServerWrapper wrapper) {
                // A cleared log does not change any server's status.
            }
        });
    }

    private void updateWidget() {
        WindowManager manager = WindowManager.getInstance();
        if (manager != null && project != null && !project.isDisposed()) {
            StatusBar statusBar = manager.getStatusBar(project);
            if (statusBar != null) {
                statusBar.updateWidget(ID());
            }
        }
    }

    @Override
    public void dispose() {}

    @NotNull
    @Override
    public String ID() {
        return "LSP";
    }

    /**
     * One line per server with its name, status and recent crash count, then a hint that clicking
     * shows actions. The number of waiting requests is left out: it changes every few milliseconds,
     * so on hover it is mostly noise. It is in the popup title and the inspector instead.
     */
    @NotNull
    static String tooltipText(@NotNull Collection<LanguageServerWrapper> wrappers) {
        if (wrappers.isEmpty()) {
            return Lsp4IntellijBundle.message("tooltip.language.server.none");
        }
        StringBuilder html = new StringBuilder("<html>");
        wrappers.stream()
                .sorted(Comparator.comparing(w -> w.getServerDefinition().getDisplayName()))
                .forEach(wrapper -> html.append(StringUtil.escapeXmlEntities(Lsp4IntellijBundle.message(
                                "tooltip.language.server.line", wrapper.getServerDefinition().getDisplayName(),
                                details(wrapper.getStatus(), 0, wrapper.getCrashCount()))))
                        .append("<br>"));
        return html.append(Lsp4IntellijBundle.message("tooltip.click.for.actions")).append("</html>").toString();
    }

    /**
     * The server's short name followed by its status details, for example
     * {@code bal: Initialized, 2 requests waiting}. Deliberately not
     * {@code getServerDefinition().toString()}, which a {@code RawCommandServerDefinition} renders as
     * its whole command line.
     */
    @NotNull
    static String serverLabel(@NotNull LanguageServerWrapper wrapper) {
        return wrapper.getServerDefinition().getDisplayName() + ": " + details(wrapper);
    }

    @NotNull
    static String details(@NotNull LanguageServerWrapper wrapper) {
        return details(wrapper.getStatus(), wrapper.getPendingRequests().size(), wrapper.getCrashCount());
    }

    /**
     * A status followed by the number of requests waiting for a response and of recent crashes, each
     * left out while it is zero, for example {@code Initialized, 2 requests waiting}.
     */
    @NotNull
    static String details(@NotNull ServerStatus status, int pending, int crashes) {
        String separator = Lsp4IntellijBundle.message("lsp.status.separator");
        StringBuilder text = new StringBuilder(statusText(status));
        if (pending > 0) {
            text.append(separator).append(Lsp4IntellijBundle.message("lsp.status.pending", pending));
        }
        if (crashes > 0) {
            text.append(separator).append(Lsp4IntellijBundle.message("lsp.status.crashes", crashes));
        }
        return text.toString();
    }

    /**
     * One {@link ServerStatus} as display text, for example {@code Initialized}. Not localized,
     * matching how the rest of the library renders these constants.
     */
    @NotNull
    static String statusText(@NotNull ServerStatus status) {
        String name = status.name();
        return name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT);
    }

    @NotNull
    private Set<LanguageServerWrapper> allWrappers() {
        LspServerManager manager = LspServerManager.getInstanceIfCreated(project);
        return manager == null ? Set.of() : manager.allWrappers();
    }

    private class IconPresentation implements StatusBarWidget.IconPresentation {
        @Nullable
        @Override
        public Icon getIcon() {
            Map<LanguageServerWrapper, ServerStatus> statuses = new LinkedHashMap<>();
            for (LanguageServerWrapper wrapper : allWrappers()) {
                statuses.put(wrapper, wrapper.getStatus());
            }
            if (statuses.isEmpty()) {
                return new LSPDefaultIconProvider().getStatusIcons().get(ServerStatus.STOPPED);
            }
            ServerStatus aggregate = ServerStatusAggregator.aggregate(statuses.values());
            LanguageServerWrapper representative = statuses.entrySet().stream()
                    .filter(entry -> entry.getValue() == aggregate)
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElseThrow();
            return GUIUtils.getIconProviderFor(representative.getServerDefinition())
                    .getStatusIcons().get(aggregate);
        }

        @Nullable
        @Override
        public Consumer<MouseEvent> getClickConsumer() {
            return (MouseEvent t) -> {
                JBPopupFactory.ActionSelectionAid mnemonics = JBPopupFactory.ActionSelectionAid.MNEMONICS;
                Component component = t.getComponent();
                Set<LanguageServerWrapper> wrappers = allWrappers();
                DefaultActionGroup group = wrappers.size() <= 1
                        ? flatActionGroup(wrappers.stream().findFirst().orElse(null))
                        : perServerActionGroup(wrappers);

                // A single server is named, with its status, by the popup's title.
                String title = wrappers.size() == 1
                        ? serverLabel(wrappers.iterator().next())
                        : Lsp4IntellijBundle.message("lsp.status.title");

                DataContext context = DataManager.getInstance().getDataContext(component);
                ListPopup popup = JBPopupFactory.getInstance()
                        .createActionGroupPopup(title, group, context, mnemonics, true);
                Dimension dimension = popup.getContent().getPreferredSize();
                Point at = new Point(0, -dimension.height);
                popup.show(new RelativePoint(t.getComponent(), at));
            };
        }

        /**
         * The popup for zero or one server: just the actions, flat, matching the widget's original
         * layout rather than wrapping the single server in its own submenu. The server it acts on is
         * named by the popup's title instead of by a header row inside it.
         */
        @NotNull
        private DefaultActionGroup flatActionGroup(@Nullable LanguageServerWrapper wrapper) {
            DefaultActionGroup group = new DefaultActionGroup();
            addServerActions(group, wrapper);
            return group;
        }

        /**
         * Adds the actions offered for one server. While requests are waiting or the server has
         * crashed recently, a shortcut to the matching inspector tab comes first. The inspector
         * actions are left out if the consumer plugin never registered the tool window.
         */
        private void addServerActions(@NotNull DefaultActionGroup group, @Nullable LanguageServerWrapper wrapper) {
            boolean inspector = inspectorRegistered();
            if (wrapper != null && inspector) {
                int pending = wrapper.getPendingRequests().size();
                int crashes = wrapper.getCrashCount();
                if (pending > 0) {
                    group.add(new OpenInspectorAction(wrapper,
                            Lsp4IntellijBundle.message("lsp.status.pending", pending) + "\u2026",
                            Lsp4IntellijBundle.message("action.show.pending.description"),
                            AllIcons.Vcs.History, LspInspectorPanel::showRequestsTab));
                }
                if (crashes > 0) {
                    group.add(new OpenInspectorAction(wrapper,
                            Lsp4IntellijBundle.message("lsp.status.crashes", crashes) + "\u2026",
                            Lsp4IntellijBundle.message("action.show.crashes.description"),
                            AllIcons.General.Error, LspInspectorPanel::showErrors));
                }
                if (pending > 0 || crashes > 0) {
                    group.addSeparator();
                }
            }
            group.add(new Restart(wrapper));
            if (inspector) {
                group.add(new OpenInspectorAction(wrapper, Lsp4IntellijBundle.message("action.open.inspector"),
                        Lsp4IntellijBundle.message("action.open.inspector.description"),
                        AllIcons.Toolwindows.ToolWindowMessages, panel -> { }));
            }
            if (wrapper != null) {
                addTraceSection(group, wrapper);
            }
        }

        /**
         * A titled section that sets the server's JSON-RPC trace level, the same setting as the
         * inspector's Trace dropdown, with the current level checked.
         */
        private void addTraceSection(@NotNull DefaultActionGroup group, @NotNull LanguageServerWrapper wrapper) {
            group.addSeparator(Lsp4IntellijBundle.message("inspector.trace"));
            for (LspTraceLevel level : LspTraceLevel.values()) {
                String name = level.name();
                group.add(new ToggleAction(name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT)) {
                    @NotNull
                    @Override
                    public ActionUpdateThread getActionUpdateThread() {
                        // isSelected() reads only a volatile field.
                        return ActionUpdateThread.BGT;
                    }

                    @Override
                    public boolean isSelected(@NotNull AnActionEvent e) {
                        return wrapper.getTraceLevel() == level;
                    }

                    @Override
                    public void setSelected(@NotNull AnActionEvent e, boolean state) {
                        if (state) {
                            wrapper.setTraceLevel(level);
                        }
                    }
                });
            }
        }

        /**
         * The status icon this server's definition provides for its current status, reused from the
         * same provider the widget icon itself uses.
         */
        @Nullable
        private Icon statusIcon(@NotNull LanguageServerWrapper wrapper) {
            return GUIUtils.getIconProviderFor(wrapper.getServerDefinition())
                    .getStatusIcons().get(wrapper.getStatus());
        }

        /**
         * The popup for more than one server: one submenu per server, each offering the same actions
         * the flat popup would for that server alone.
         */
        @NotNull
        private DefaultActionGroup perServerActionGroup(@NotNull Set<LanguageServerWrapper> wrappers) {
            DefaultActionGroup group = new DefaultActionGroup();
            wrappers.stream()
                    .sorted(Comparator.comparing(w -> w.getServerDefinition().toString()))
                    .forEach(wrapper -> {
                        DefaultActionGroup serverGroup =
                                new DefaultActionGroup(serverLabel(wrapper), true);
                        // The submenu carries its server's status icon, so an unhealthy server is
                        // visible without opening it.
                        serverGroup.getTemplatePresentation().setIcon(statusIcon(wrapper));
                        addServerActions(serverGroup, wrapper);
                        group.add(serverGroup);
                    });
            return group;
        }

        /**
         * Whether the consumer plugin registered a tool window under
         * {@link LspInspectorToolWindowFactory#TOOL_WINDOW_ID}. The inspector actions must not be shown
         * at all without it, never thrown for.
         */
        private boolean inspectorRegistered() {
            return ToolWindowManager.getInstance(project)
                    .getToolWindow(LspInspectorToolWindowFactory.TOOL_WINDOW_ID) != null;
        }

        class Restart extends AnAction implements DumbAware {

            private final LanguageServerWrapper wrapper;

            Restart(@Nullable LanguageServerWrapper wrapper) {
                super(Lsp4IntellijBundle.message("action.restart"),
                      Lsp4IntellijBundle.message("action.restart.description"),
                      AllIcons.Actions.Restart);
                this.wrapper = wrapper;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent anActionEvent) {
                if (wrapper != null) {
                    wrapper.restart();
                }
            }
        }

        /**
         * Opens the LSP Inspector tool window, selects the server this action was built for via
         * {@link LspInspectorPanel#selectServer(LanguageServerWrapper)}, then runs {@code then} on the
         * panel, for example to switch tabs. Only ever added by {@link #addServerActions}, which
         * already confirmed the tool window exists.
         */
        class OpenInspectorAction extends AnAction implements DumbAware {

            private final LanguageServerWrapper wrapper;
            private final java.util.function.Consumer<LspInspectorPanel> then;

            OpenInspectorAction(@Nullable LanguageServerWrapper wrapper, @NotNull String text,
                    @NotNull String description, @NotNull Icon icon,
                    @NotNull java.util.function.Consumer<LspInspectorPanel> then) {
                super(text, description, icon);
                this.wrapper = wrapper;
                this.then = then;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                        .getToolWindow(LspInspectorToolWindowFactory.TOOL_WINDOW_ID);
                if (toolWindow == null) {
                    return;
                }
                toolWindow.activate(() -> {
                    if (wrapper == null) {
                        return;
                    }
                    ContentManager contentManager = toolWindow.getContentManager();
                    Content content = contentManager.getSelectedContent();
                    if (content == null && contentManager.getContentCount() > 0) {
                        content = contentManager.getContent(0);
                    }
                    if (content != null && content.getComponent() instanceof LspInspectorPanel panel) {
                        panel.selectServer(wrapper);
                        then.accept(panel);
                    }
                });
            }
        }

        @Override
        public String getTooltipText() {
            return tooltipText(allWrappers());
        }
    }
}
