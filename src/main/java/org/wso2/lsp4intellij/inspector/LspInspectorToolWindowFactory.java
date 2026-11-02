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

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;
import org.wso2.lsp4intellij.Lsp4IntellijBundle;

import javax.swing.Icon;

/**
 * Builds the LSP Inspector tool window content: one {@link LspInspectorPanel} per project, registered as
 * a child of {@link ToolWindow#getDisposable()} so it (and everything it in turn registers) is
 * disposed when the tool window's content is torn down.
 *
 * <p>Not registered by lsp4intellij itself: this library ships no {@code plugin.xml}, so a consumer
 * plugin registers a {@code <toolWindow>} extension pointing {@code factoryClass} at this class. With
 * no such registration, the platform never calls {@link #createToolWindowContent}, matching every
 * other UI-facing class in this library.
 */
public final class LspInspectorToolWindowFactory implements ToolWindowFactory, DumbAware {

    /**
     * The tool window id a consumer plugin's {@code plugin.xml} must register this factory under for
     * {@link org.wso2.lsp4intellij.statusbar.LSPServerStatusWidget}'s "Open LSP Inspector" action to
     * find it via {@link com.intellij.openapi.wm.ToolWindowManager#getToolWindow(String)}. That
     * lookup returns null for a consumer that used a different id (or never registered one), and the
     * widget treats null as "do not show the action" rather than throwing.
     */
    public static final String TOOL_WINDOW_ID = "LSP Inspector";

    /**
     * Sets the localized stripe title. {@link #TOOL_WINDOW_ID} stays fixed because it is the lookup
     * key consumers register under.
     */
    @Override
    public void init(@NotNull ToolWindow toolWindow) {
        toolWindow.setStripeTitle(Lsp4IntellijBundle.message("inspector.title"));
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        LspInspectorPanel panel = new LspInspectorPanel(project, toolWindow);
        Disposer.register(toolWindow.getDisposable(), panel);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        toolWindow.getContentManager().addContent(content);
    }

    @Override
    public Icon getIcon() {
        return AllIcons.Toolwindows.ToolWindowMessages;
    }
}
