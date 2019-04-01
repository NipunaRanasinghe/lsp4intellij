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
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.wso2.lsp4intellij.client.languageserver.serverdefinition.LanguageServerDefinition;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;
import org.wso2.lsp4intellij.editor.listeners.EditorListener;
import org.wso2.lsp4intellij.editor.listeners.FileDocumentManagerListenerImpl;
import org.wso2.lsp4intellij.editor.listeners.VFSListener;
import org.wso2.lsp4intellij.extensions.LSPExtensionManager;
import org.wso2.lsp4intellij.utils.ApplicationUtils;
import org.wso2.lsp4intellij.utils.FileUtils;

import java.util.AbstractMap;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class IntellijLanguageClient implements ApplicationComponent {

    private static final Map<Pair<String, String>, LanguageServerWrapper> extToLanguageWrapper = new ConcurrentHashMap<>();
    private static Map<String, Set<LanguageServerWrapper>> projectToLanguageWrappers = new ConcurrentHashMap<>();
    private static Map<String, LanguageServerDefinition> extToServerDefinition = new ConcurrentHashMap<>();
    private static Map<String, LSPExtensionManager> extToExtManager = new ConcurrentHashMap<>();

    private static Logger LOG = Logger.getInstance(IntellijLanguageClient.class);

    @Override
    public void initComponent() {
        // LSPState.getInstance.getState(); //Need that to trigger loadState
        EditorFactory.getInstance().addEditorFactoryListener(new EditorListener(), Disposer.newDisposable());
        VirtualFileManager.getInstance().addVirtualFileListener(new VFSListener());
        ApplicationManager.getApplication().getMessageBus().connect()
                .subscribe(AppTopics.FILE_DOCUMENT_SYNC, new FileDocumentManagerListenerImpl());
        LOG.info("Language Client init finished");
    }

    /**
     * Adds a new server definition, attached to the given file extension.
     * Plugin developers should register their language server definitions using this API.
     *
     * @param definition The server definition
     * @throws IllegalArgumentException If the language server definition is null.
     */
    public static void addServerDefinition(LanguageServerDefinition definition) throws IllegalArgumentException {
        if (definition != null) {
            processDefinition(definition);
            LOG.info("Added definition for " + definition);
        } else {
            LOG.warn("Trying to add a null definition");
            throw new IllegalArgumentException("Trying to add a null definition");
        }
    }

    /**
     * Adds a new LSP extension manager, attached to the given file extension.
     * Plugin developers should register their custom language server extensions using this API.
     *
     * @param ext     File extension type
     * @param manager LSP extension manager (Should be implemented by the developer)
     * @throws IllegalArgumentException if an language server extensions manager is already registered for the given
     *                                  file extension
     */
    public static void addExtensionManager(String ext, LSPExtensionManager manager) throws IllegalArgumentException {
        if (extToExtManager.get(ext) == null) {
            extToExtManager.put(ext, manager);
        } else {
            LOG.warn("An extension manager is already registered for \"" + ext + "\" extension");
            throw new IllegalArgumentException(
                    "An extension manager has been already registered for \"" + ext + "\" extension");
        }
        extToExtManager.put(ext, manager);
    }

    /**
     * @return All instantiated ServerWrappers
     */
    public static Set<LanguageServerWrapper> getAllServerWrappers() {
        Set<LanguageServerWrapper> result = new HashSet<>();
        for (Set<LanguageServerWrapper> wrappers : projectToLanguageWrappers.values()) {
            result.addAll(wrappers);
        }
        return result;
    }

    /**
     * @param ext An extension
     * @return True if there is a LanguageServer supporting this extension, false otherwise
     */
    public static boolean isExtensionSupported(String ext) {
        return extToServerDefinition.keySet().contains(ext);
    }

    /**
     * Called when an editor is opened. Instantiates a LanguageServerWrapper if necessary, and adds the Editor to the Wrapper
     *
     * @param editor the editor
     */
    public static void editorOpened(Editor editor) {
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        Project project = editor.getProject();
        String rootPath = FileUtils.editorToProjectFolderPath(editor);
        String rootUri = FileUtils.pathToUri(rootPath);
        if (file != null && rootUri != null) {
            ApplicationUtils.pool(() -> {
                String ext = file.getExtension();
                LOG.info("Opened " + file.getName());
                LanguageServerDefinition serverDefinition = extToServerDefinition.get(ext);
                if (serverDefinition != null) {
                    LanguageServerWrapper wrapper = extToLanguageWrapper.get(new MutablePair<>(ext, rootUri));
                    if (wrapper == null) {
                        LOG.info("Instantiating wrapper for " + ext + " : " + rootUri);
                        if (extToExtManager.get(ext) != null) {
                            wrapper = new LanguageServerWrapper(serverDefinition, project, extToExtManager.get(ext));
                        } else {
                            wrapper = new LanguageServerWrapper(serverDefinition, project);
                        }
                        String[] exts = serverDefinition.ext.split(LanguageServerDefinition.SPLIT_CHAR);
                        for (String ex : exts) {
                            extToLanguageWrapper.put(new ImmutablePair<>(ex, rootUri), wrapper);
                        }
                    } else {
                        LOG.info("Wrapper already existing for " + ext + " , " + rootUri);
                    }
                    LOG.info("Adding file " + file.getName());
                    wrapper.connect(editor);
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
        if (file != null) {
            ApplicationUtils.pool(() -> {
                String ext = file.getExtension();
                LanguageServerWrapper serverWrapper = LanguageServerWrapper.forEditor(editor);
                if (serverWrapper != null) {
                    LOG.info("Disconnecting " + FileUtils.editorToURIString(editor));
                    serverWrapper.disconnect(editor);
                }
            });
        } else {
            LOG.warn("File for editor " + editor.getDocument().getText() + " is null");
        }
    }

    private static void processDefinition(LanguageServerDefinition definition) {
        String[] extensions = definition.ext.split(LanguageServerDefinition.SPLIT_CHAR);
        for (String ext : extensions) {
            if (extToServerDefinition.get(ext) == null) {
                extToServerDefinition.put(ext, definition);
                LOG.info("Added server definition for " + ext);
            } else {
                extToServerDefinition.replace(ext, definition);
                LOG.info("Updated server definition for " + ext);
            }
        }
    }

    /**
     * Sets the extensions to languageServer mapping.
     *
     * @param newExt a Java Map
     */
    public static void setExtToServerDefinition(Map<String, LanguageServerDefinition> newExt) {
        List<Map.Entry<String, LanguageServerDefinition>> nullDef = newExt.entrySet().stream()
                .filter(d -> d.getValue() == null).collect(Collectors.toList());
        Map<String, LanguageServerDefinition> oldServerDef = extToServerDefinition;
        Map<String, LanguageServerDefinition> flattened = newExt.entrySet().stream().filter(d -> d.getValue() != null)
                .flatMap(t -> Stream.of(t.getKey().split(LanguageServerDefinition.SPLIT_CHAR))
                        .map(ext -> new AbstractMap.SimpleEntry<>(ext, t.getValue())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        extToServerDefinition = flattened;
        flattenExtensions();
        nullDef.forEach(ext -> LOG.error("Definition for " + ext + " is null"));

        ApplicationUtils.pool(() -> {
            Set<String> added = flattened.keySet().stream().filter(e -> !oldServerDef.keySet().contains(e))
                    .collect(Collectors.toSet());
            Set<String> removed = oldServerDef.keySet().stream().filter(e -> !flattened.keySet().contains(e))
                    .collect(Collectors.toSet());

            extToLanguageWrapper.keySet().stream().filter(k -> removed.contains(k.getKey())).forEach(k -> {
                LanguageServerWrapper wrapper = extToLanguageWrapper.get(k);
                wrapper.stop(false);
                wrapper.removeWidget();
                extToLanguageWrapper.remove(k);
            });

            List<Editor> openedEditors = ApplicationUtils.computableReadAction(
                    () -> Arrays.stream(ProjectManager.getInstance().getOpenProjects())
                            .flatMap(proj -> Arrays.stream(FileEditorManager.getInstance(proj).getAllEditors()))
                            .filter(TextEditor.class::isInstance).map(TextEditor.class::cast).map(TextEditor::getEditor)
                            .collect(Collectors.toList()));

            List<VirtualFile> files = openedEditors.stream()
                    .map(e -> FileDocumentManager.getInstance().getFile(e.getDocument())).collect(Collectors.toList());

            IntStream.range(0, openedEditors.size()).forEach(i -> {
                if (added.contains(files.get(i).getExtension())) {
                    editorOpened(openedEditors.get(i));
                }
            });
        });
    }

    private static void flattenExtensions() {
        extToServerDefinition = extToServerDefinition.entrySet().stream().flatMap(p -> {
            String ext = p.getKey();
            LanguageServerDefinition sDef = p.getValue();
            List<String> split = Arrays.asList(ext.split(LanguageServerDefinition.SPLIT_CHAR));
            return split.stream().map(s -> new AbstractMap.SimpleEntry<>(s, sDef));
        }).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public static void removeWrapper(LanguageServerWrapper wrapper) {
        if (wrapper.getProject() != null) {
            extToLanguageWrapper.remove(new MutablePair<>(wrapper.getServerDefinition().ext,
                    FileUtils.pathToUri(wrapper.getProject().getBasePath())));
        } else {
            LOG.error("No attached projects found for wrapper");

        }
    }

    // Todo - Implement workspace symbols support
}
