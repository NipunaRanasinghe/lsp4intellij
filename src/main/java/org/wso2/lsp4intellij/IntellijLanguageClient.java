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
package org.wso2.lsp4intellij;

import com.intellij.AppTopics;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ApplicationComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.wso2.lsp4intellij.client.languageserver.serverdefinition.LanguageServerDefinition;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;
import org.wso2.lsp4intellij.extensions.LSPExtensionManager;
import org.wso2.lsp4intellij.listeners.LSPEditorListener;
import org.wso2.lsp4intellij.listeners.LSPFileDocumentManagerListener;
import org.wso2.lsp4intellij.listeners.LSPProjectManagerListener;
import org.wso2.lsp4intellij.listeners.VFSListener;
import org.wso2.lsp4intellij.requests.Timeout;
import org.wso2.lsp4intellij.requests.Timeouts;
import org.wso2.lsp4intellij.services.DefinitionRegistry;
import org.wso2.lsp4intellij.services.LspApplicationServerRegistry;
import org.wso2.lsp4intellij.services.LspServerManager;
import org.wso2.lsp4intellij.utils.FileUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.wso2.lsp4intellij.utils.ApplicationUtils.pool;
import static org.wso2.lsp4intellij.utils.FileUtils.reloadAllEditors;
import static org.wso2.lsp4intellij.utils.FileUtils.reloadEditors;

/**
 * The {@link ApplicationComponent}/{@link Disposable} implementation on this class only matters for
 * consumers still using the legacy {@code <application-components>} registration style documented
 * in the developer guide's "Appendix: Legacy components-based setup". Consumers following the
 * current setup (see {@code resources/plugin.xml.example}) register {@code LSPEditorListener},
 * {@code LSPFileDocumentManagerListener}, {@code VFSListener}, and {@code LSPProjectManagerListener}
 * declaratively instead, and {@link #initComponent()} never runs for them — {@code applicationService}
 * registration does not invoke {@link ApplicationComponent} lifecycle methods.
 */
public class IntellijLanguageClient implements ApplicationComponent, Disposable {

    private static final Logger LOG = Logger.getInstance(IntellijLanguageClient.class);

    /**
     * @deprecated only runs for consumers still using the legacy {@code <application-components>}
     * registration style; see the class javadoc. Prefer registering
     * {@code LSPEditorListener}/{@code LSPFileDocumentManagerListener}/{@code VFSListener}/
     * {@code LSPProjectManagerListener} declaratively, as {@code resources/plugin.xml.example} does.
     */
    @Deprecated
    @Override
    public void initComponent() {
        try {
            // Adds project listener.
            ApplicationManager.getApplication().getMessageBus().connect().subscribe(ProjectManager.TOPIC,
                    new LSPProjectManagerListener());
            // Adds editor listener.
            EditorFactory.getInstance().addEditorFactoryListener(new LSPEditorListener(), this);
            // Adds VFS listener.
            VirtualFileManager.getInstance().addVirtualFileListener(new VFSListener());
            // Adds document event listener.
            ApplicationManager.getApplication().getMessageBus().connect().subscribe(AppTopics.FILE_DOCUMENT_SYNC,
                    new LSPFileDocumentManagerListener());

            LOG.info("Intellij Language Client initialized successfully");
        } catch (Exception e) {
            LOG.warn("Fatal error occurred when initializing Intellij language client.", e);
        }
    }

    /**
     * Use it to initialize the server connection for the given project (useful if no editor is launched).
     */
    public void initProjectConnections(@NotNull Project project) {
        // Only project-scoped definitions are started here, matching the previous behavior:
        // application-level definitions (registered without a project) are started lazily instead,
        // when an editor for a matching file is opened.
        LspServerManager.getInstance(project).definitions().asMap().forEach((ext, definition) -> {
            LanguageServerWrapper wrapper = getOrCreateWrapper(project, ext, definition);
            if (wrapper != null) {
                wrapper.start();
            }
        });
    }

    /**
     * Adds a new server definition, attached to the given file extension.
     * This definition will be applicable for any project, since a specific project is not defined.
     * Plugin developers can register their application-level language server definitions using this API.
     *
     * @param definition The server definition
     */
    @SuppressWarnings("unused")
    public static void addServerDefinition(@NotNull LanguageServerDefinition definition) {
        addServerDefinition(definition, null);
    }

    /**
     * Adds a new server definition, attached to the given file extension and the project.
     * Plugin developers can register their project-level language server definitions using this API.
     *
     * @param definition The server definition
     */
    @SuppressWarnings("unused")
    public static void addServerDefinition(@NotNull LanguageServerDefinition definition, @Nullable Project project) {
        processDefinition(definition, project);
        if (project != null) {
            reloadEditors(project);
        } else {
            reloadAllEditors();
        }
        LOG.info("Added definition for " + definition);
    }

    /**
     * Adds a new LSP extension manager, attached to the given file extension.
     * Plugin developers should register their custom language server extensions using this API.
     *
     * @param ext     File extension type
     * @param manager LSP extension manager (Should be implemented by the developer)
     */
    @SuppressWarnings("unused")
    public static void addExtensionManager(@NotNull String ext, @NotNull LSPExtensionManager manager) {
        LspApplicationServerRegistry registry = LspApplicationServerRegistry.getInstance();
        if (registry.hasExtensionManager(ext)) {
            LOG.warn("An extension manager is already registered for \"" + ext + "\" extension");
        }
        registry.registerExtensionManager(ext, manager);
    }

    /**
     * @return All instantiated ServerWrappers
     */
    public static @NotNull Set<LanguageServerWrapper> getAllServerWrappersFor(String projectUri) {
        Project project = projectForUri(projectUri);
        LspServerManager manager = project != null ? LspServerManager.getInstanceIfCreated(project) : null;
        return manager != null ? manager.allWrappers() : new HashSet<>();
    }

    /**
     * @return All registered LSP protocol extension managers for the given file extension.
     */
    public static @Nullable LSPExtensionManager getExtensionManagerFor(String fileExt) {
        return LspApplicationServerRegistry.getInstance().extensionManagerFor(fileExt);
    }

    /**
     * @param virtualFile The virtual file instance to be validated
     * @return True if there is a LanguageServer supporting this extension, false otherwise
     */
    public static boolean isExtensionSupported(VirtualFile virtualFile) {
        String ext = virtualFile.getExtension();
        String fileName = virtualFile.getName();
        if (LspApplicationServerRegistry.getInstance().definitions().hasDefinitionMatching(ext, fileName)) {
            return true;
        }
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            LspServerManager manager = LspServerManager.getInstanceIfCreated(project);
            if (manager != null && manager.definitions().hasDefinitionMatching(ext, fileName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Called when an editor is opened. Instantiates a LanguageServerWrapper
     * if necessary, and adds the Editor to the Wrapper
     *
     * @param editor the editor
     */
    public static void editorOpened(Editor editor) {
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (!FileUtils.isFileSupported(file)) {
            LOG.debug("Handling open on a editor which host a LightVirtual/Null file");
            return;
        }

        Project project = editor.getProject();
        if (project == null) {
            LOG.debug("Opened an unsupported editor, which does not have an attached project.");
            return;
        }
        String projectUri = FileUtils.projectToUri(project);
        if (projectUri == null) {
            LOG.warn("File for editor " + editor.getDocument().getText() + " is null");
            return;
        }

        pool(() -> {
            String ext = file.getExtension();
            final String fileName = file.getName();
            LOG.info("Opened " + fileName);

            // The ext can either be a file extension or a file pattern(regex expression).
            // First try for the extension since it is the most comment usage, if not try to
            // match file name.
            DefinitionRegistry projectDefs = LspServerManager.getInstance(project).definitions();
            DefinitionRegistry appDefs = LspApplicationServerRegistry.getInstance().definitions();

            LanguageServerDefinition serverDefinition = projectDefs.definitionForExt(ext);
            if (serverDefinition == null) {
                // Fallback to file name pattern matching, where the map key is a regex.
                Map.Entry<String, LanguageServerDefinition> matched = projectDefs.matchByFileName(fileName);
                if (matched != null) {
                    serverDefinition = matched.getValue();
                    // ext must be the key since we are in file name mode.
                    ext = matched.getKey();
                }
            }

            // If cannot find a project-specific server definition for the given file and project, repeat the
            // above process to find an application level server definition for the given file extension/regex.
            if (serverDefinition == null) {
                serverDefinition = appDefs.definitionForExt(ext);
            }
            if (serverDefinition == null) {
                // Fallback to file name pattern matching, where the map key is a regex.
                Map.Entry<String, LanguageServerDefinition> matched = appDefs.matchByFileName(fileName);
                if (matched != null) {
                    serverDefinition = matched.getValue();
                    // ext must be the key since we are in file name mode.
                    ext = matched.getKey();
                }
            }

            if (serverDefinition == null) {
                LOG.warn("Could not find a server definition for " + ext);
                return;
            }
            // Update project mapping for language servers.
            LanguageServerWrapper wrapper = getOrCreateWrapper(project, ext, serverDefinition);
            if (wrapper == null) {
                LOG.debug("Could not create a wrapper for " + fileName + "; project may be closing");
                return;
            }

            LOG.info("Adding file " + fileName);
            // Connecting (which may start the server) runs on the wrapper's own dispatcher so that a slow
            // server start does not block editor events of other servers.
            wrapper.pool(() -> wrapper.connect(editor));
        });
    }

    @Nullable
    private static LanguageServerWrapper getOrCreateWrapper(
            Project project, String ext, LanguageServerDefinition serverDefinition) {
        LSPExtensionManager extManager = LspApplicationServerRegistry.getInstance().extensionManagerFor(ext);
        return LspServerManager.getInstance(project).getOrCreateWrapper(ext, serverDefinition, extManager);
    }

    /**
     * Called when an editor is closed. Notifies the LanguageServerWrapper if needed
     *
     * @param editor the editor.
     */
    public static void editorClosed(Editor editor) {
        VirtualFile file = FileUtils.virtualFileFromEditor(editor);
        if (!FileUtils.isFileSupported(file)) {
            LOG.debug("Handling close on a editor which host a LightVirtual/Null file");
            return;
        }

        pool(() -> {
            LanguageServerWrapper serverWrapper = LanguageServerWrapper.forEditor(editor);
            if (serverWrapper != null) {
                LOG.info("Disconnecting " + FileUtils.editorToURIString(editor));
                serverWrapper.pool(() -> serverWrapper.disconnect(editor));
            }
        });
    }

    /**
     * Returns current timeout values.
     *
     * @return A map of Timeout types and corresponding values(in milliseconds).
     */
    public static Map<Timeouts, Integer> getTimeouts() {
        return Timeout.getTimeouts();
    }

    /**
     * Returns current timeout value of a given timeout type.
     *
     * @return A map of Timeout types and corresponding values(in milliseconds).
     */
    @SuppressWarnings("unused")
    public static int getTimeout(Timeouts timeoutType) {
        return getTimeouts().get(timeoutType);
    }

    /**
     * Overrides default timeout values with a given set of timeouts.
     *
     * @param newTimeouts A map of Timeout types and corresponding values to be set.
     */
    public static void setTimeouts(Map<Timeouts, Integer> newTimeouts) {
        Timeout.setTimeouts(newTimeouts);
    }

    /**
     * @param timeout Timeout type
     * @param value   new timeout value to be set (in milliseconds).
     */
    @SuppressWarnings("unused")
    public static void setTimeout(Timeouts timeout, int value) {
        Map<Timeouts, Integer> newTimeout = new HashMap<>();
        newTimeout.put(timeout, value);
        setTimeouts(newTimeout);
    }

    public static void removeWrapper(LanguageServerWrapper wrapper) {
        Project project = wrapper.getProject();
        if (project == null) {
            LOG.error("No attached projects found for wrapper");
            return;
        }
        // Reached from LanguageServerWrapper.dispose(), which the platform can also drive during the
        // project's own disposal; the manager clears its own registry in that case.
        LspServerManager manager = LspServerManager.getInstanceIfCreated(project);
        if (manager == null) {
            return;
        }
        String[] extensions = wrapper.getServerDefinition().ext.split(LanguageServerDefinition.SPLIT_CHAR);
        manager.unregister(wrapper, extensions);
    }

    public static Map<String, Set<LanguageServerWrapper>> getProjectToLanguageWrappers() {
        Map<String, Set<LanguageServerWrapper>> result = new HashMap<>();
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            LspServerManager manager = LspServerManager.getInstanceIfCreated(project);
            if (manager == null) {
                continue;
            }
            Set<LanguageServerWrapper> wrappers = manager.allWrappers();
            if (!wrappers.isEmpty()) {
                result.put(FileUtils.projectToUri(project), wrappers);
            }
        }
        return result;
    }

    @SuppressWarnings("unused")
    public static void didChangeConfiguration(@NotNull DidChangeConfigurationParams params, @NotNull Project project) {
        LspServerManager manager = LspServerManager.getInstanceIfCreated(project);
        Set<LanguageServerWrapper> serverWrappers =
                manager != null ? manager.allWrappers() : Collections.emptySet();
        if (serverWrappers.isEmpty()) {
            LOG.warn("No language servers registered for project " + project.getName());
            return;
        }
        serverWrappers.forEach(s -> s.getRequestManager().didChangeConfiguration(params));
    }

    /**
     * Returns the registered extension manager for this language server.
     *
     * @param definition The LanguageServerDefinition
     */
    public static Optional<LSPExtensionManager> getExtensionManagerForDefinition(
            @NotNull LanguageServerDefinition definition) {
        return Optional.ofNullable(
                LspApplicationServerRegistry.getInstance().extensionManagerFor(definition.ext.split(",")[0]));
    }

    /**
     * @deprecated only runs for consumers still using the legacy {@code <application-components>}
     * registration style; see the class javadoc.
     */
    @Deprecated
    @Override
    public void disposeComponent() {
        Disposer.dispose(this);
    }

    @Override
    public void dispose() {
        Disposer.dispose(this);
    }

    @Nullable
    private static Project projectForUri(@Nullable String projectUri) {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (Objects.equals(projectUri, FileUtils.projectToUri(project))) {
                return project;
            }
        }
        return null;
    }

    private static void processDefinition(LanguageServerDefinition definition, @Nullable Project project) {
        String[] extensions = definition.ext.split(LanguageServerDefinition.SPLIT_CHAR);
        for (String ext : extensions) {
            DefinitionRegistry definitions = project != null
                    ? LspServerManager.getInstance(project).definitions()
                    : LspApplicationServerRegistry.getInstance().definitions();
            boolean existed = definitions.definitionForExt(ext) != null;
            definitions.register(ext, definition);
            LOG.info((existed ? "Updated" : "Added") + " server definition for " + ext);
        }
    }
}
