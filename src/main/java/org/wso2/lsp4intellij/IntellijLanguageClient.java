/*
 * Copyright (c) 2018-2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ApplicationComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.wso2.lsp4intellij.client.languageserver.serverdefinition.LanguageServerDefinition;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;
import org.wso2.lsp4intellij.editor.listeners.EditorListener;
import org.wso2.lsp4intellij.editor.listeners.FileDocumentManagerListenerImpl;
import org.wso2.lsp4intellij.editor.listeners.VFSListener;
import org.wso2.lsp4intellij.extensions.LSPExtensionManager;
import org.wso2.lsp4intellij.requests.Timeout;
import org.wso2.lsp4intellij.requests.Timeouts;
import org.wso2.lsp4intellij.utils.ApplicationUtils;
import org.wso2.lsp4intellij.utils.FileUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class IntellijLanguageClient implements ApplicationComponent {

    private static Logger LOG = Logger.getInstance(IntellijLanguageClient.class);

    private static final Map<Pair<String, String>, LanguageServerWrapper> extToLanguageWrapper = new ConcurrentHashMap<>();
    private static Map<String, Set<LanguageServerWrapper>> projectToLanguageWrappers = new ConcurrentHashMap<>();
    private static Map<Pair<String, String>, LanguageServerDefinition> extToServerDefinition = new ConcurrentHashMap<>();
    private static Map<String, LSPExtensionManager> extToExtManager = new ConcurrentHashMap<>();

    @Override
    public void initComponent() {
        // LSPState.getInstance.getState(); //Need that to trigger loadState
        EditorFactory.getInstance().addEditorFactoryListener(new EditorListener(), Disposer.newDisposable());
        VirtualFileManager.getInstance().addVirtualFileListener(new VFSListener());
        ApplicationManager.getApplication().getMessageBus().connect().subscribe(AppTopics.FILE_DOCUMENT_SYNC,
                new FileDocumentManagerListenerImpl());
        LOG.info("Language Client init finished");
    }

    /**
     * Adds a new server definition, attached to the given file extension.
     * This definition will be applicable for any project, since a specific project is not defined.
     * Plugin developers can register their application-level language server definitions using this API.
     *
     * @param definition The server definition
     */
    @SuppressWarnings("unused")
    public static void addServerDefinition(LanguageServerDefinition definition) {
        addServerDefinition(definition, null);
    }

    /**
     * Adds a new server definition, attached to the given file extension and the project.
     * Plugin developers can register their project-level language server definitions using this API.
     *
     * @param definition The server definition
     */
    @SuppressWarnings("unused")
    public static void addServerDefinition(LanguageServerDefinition definition, Project project) {
        if (definition != null) {
            if (project != null) {
                processDefinition(definition, FileUtils.projectToUri(project));
            } else {
                processDefinition(definition, "");
            }
            LOG.info("Added definition for " + definition);
        } else {
            LOG.warn("Trying to add a null definition");
        }
    }

    /**
     * Adds a new LSP extension manager, attached to the given file extension.
     * Plugin developers should register their custom language server extensions using this API.
     *
     * @param ext     File extension type
     * @param manager LSP extension manager (Should be implemented by the developer)
     */
    @SuppressWarnings("unused")
    public static void addExtensionManager(String ext, LSPExtensionManager manager) {
        if (extToExtManager.get(ext) != null) {
            LOG.warn("An extension manager is already registered for \"" + ext + "\" extension");
        }
        extToExtManager.put(ext, manager);
    }

    /**
     * @return All instantiated ServerWrappers
     */
    public static Set<LanguageServerWrapper> getAllServerWrappers(String projectUri) {
        return projectToLanguageWrappers.getOrDefault(projectUri, Collections.emptySet());
    }

    /**
     * @param virtualFile The virtual file instance to be validated
     * @return True if there is a LanguageServer supporting this extension, false otherwise
     */
    public static boolean isExtensionSupported(VirtualFile virtualFile) {
        return extToServerDefinition.keySet().stream().anyMatch(keyMap ->
                keyMap.getLeft().equals(virtualFile.getExtension()) || (virtualFile.getName().matches(keyMap.getLeft())));
    }

    /**
     * Called when an editor is opened. Instantiates a LanguageServerWrapper if necessary, and adds the Editor to the Wrapper
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
        if (projectUri != null) {
            ApplicationUtils.pool(() -> {
                String ext = file.getExtension();
                final String fileName = file.getName();
                LOG.info("Opened " + fileName);

                // The ext can either be a file extension or a file pattern(regex expression).
                // First try for the extension since it is the most comment usage, if not try to
                // match file name.
                LanguageServerDefinition serverDefinition = extToServerDefinition.get(new ImmutablePair<>(ext, projectUri));
                if (serverDefinition == null) {
                    // Fallback to file name pattern matching, where the map key is a regex.
                    Optional<Pair<String, String>> keyForFile = extToServerDefinition.keySet().stream().
                            filter(keyPair -> fileName.matches(keyPair.getLeft()) && keyPair.getRight().equals(projectUri))
                            .findFirst();
                    if (keyForFile.isPresent()) {
                        serverDefinition = extToServerDefinition.get(keyForFile.get());
                        // ext must be the key since we are in file name mode.
                        ext = keyForFile.get().getLeft();
                    }
                }

                // If cannot find a project-specific server definition for the given file and project, repeat the
                // above process to find an application level server definition for the given file extension/regex.
                if (serverDefinition == null) {
                    serverDefinition = extToServerDefinition.get(new ImmutablePair<>(ext, ""));
                }
                if (serverDefinition == null) {
                    // Fallback to file name pattern matching, where the map key is a regex.
                    Optional<Pair<String, String>> keyForFile = extToServerDefinition.keySet().stream().
                            filter(keyPair -> fileName.matches(keyPair.getLeft()) && keyPair.getRight().isEmpty())
                            .findFirst();
                    if (keyForFile.isPresent()) {
                        serverDefinition = extToServerDefinition.get(keyForFile.get());
                        // ext must be the key since we are in file name mode.
                        ext = keyForFile.get().getLeft();
                    }
                }

                if (serverDefinition != null) {
                    LanguageServerWrapper wrapper = extToLanguageWrapper.get(new MutablePair<>(ext, projectUri));
                    if (wrapper == null) {
                        LOG.info("Instantiating wrapper for " + ext + " : " + projectUri);
                        if (extToExtManager.get(ext) != null) {
                            wrapper = new LanguageServerWrapper(serverDefinition, project, extToExtManager.get(ext));
                        } else {
                            wrapper = new LanguageServerWrapper(serverDefinition, project);
                        }
                        String[] exts = serverDefinition.ext.split(LanguageServerDefinition.SPLIT_CHAR);
                        for (String ex : exts) {
                            extToLanguageWrapper.put(new ImmutablePair<>(ex, projectUri), wrapper);
                        }

                        // Update project mapping for language servers.
                        Set<LanguageServerWrapper> wrappers = projectToLanguageWrappers
                                .computeIfAbsent(projectUri, k -> new HashSet<>());
                        wrappers.add(wrapper);
                    } else {
                        LOG.info("Wrapper already existing for " + ext + " , " + projectUri);
                    }
                    LOG.info("Adding file " + fileName);
                    wrapper.connect(editor);
                } else {
                    LOG.warn("Could not find a server definition for " + ext);
                }
            });
        } else {
            LOG.warn("File for editor " + editor.getDocument().getText() + " is null");
        }
    }

    /**
     * Called when an editor is closed. Notifies the LanguageServerWrapper if needed
     *
     * @param editor the editor.
     */
    public static void editorClosed(Editor editor) {
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (!FileUtils.isFileSupported(file)) {
            LOG.debug("Handling close on a editor which host a LightVirtual/Null file");
            return;
        }

        ApplicationUtils.pool(() -> {
            LanguageServerWrapper serverWrapper = LanguageServerWrapper.forEditor(editor);
            if (serverWrapper != null) {
                LOG.info("Disconnecting " + FileUtils.editorToURIString(editor));
                serverWrapper.disconnect(editor);
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

    private static void processDefinition(LanguageServerDefinition definition, String projectUri) {
        String[] extensions = definition.ext.split(LanguageServerDefinition.SPLIT_CHAR);
        for (String ext : extensions) {
            Pair<String, String> keyPair = new ImmutablePair<>(ext, projectUri);
            if (extToServerDefinition.get(keyPair) == null) {
                extToServerDefinition.put(keyPair, definition);
                LOG.info("Added server definition for " + ext);
            } else {
                extToServerDefinition.replace(keyPair, definition);
                LOG.info("Updated server definition for " + ext);
            }
        }
    }

    public static void removeWrapper(LanguageServerWrapper wrapper) {
        if (wrapper.getProject() != null) {
            String[] extensions = wrapper.getServerDefinition().ext.split(LanguageServerDefinition.SPLIT_CHAR);
            for (String ext : extensions) {
                extToLanguageWrapper.remove(new MutablePair<>(ext,
                        FileUtils.projectToUri(wrapper.getProject())));
            }
        } else {
            LOG.error("No attached projects found for wrapper");
        }
    }

    public static Map<String, Set<LanguageServerWrapper>> getProjectToLanguageWrappers() {
        return projectToLanguageWrappers;
    }

    @SuppressWarnings("unused")
    public static void didChangeConfiguration(DidChangeConfigurationParams params, Project project) {
        final Set<LanguageServerWrapper> serverWrappers = IntellijLanguageClient.getProjectToLanguageWrappers()
                .get(FileUtils.projectToUri(project));
        serverWrappers.forEach(s -> s.getRequestManager().didChangeConfiguration(params));
    }
}
