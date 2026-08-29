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
package org.wso2.lsp4intellij.editor;

import com.intellij.openapi.editor.Editor;
import org.eclipse.lsp4j.Diagnostic;
import org.wso2.lsp4intellij.utils.FileUtils;
import org.wso2.lsp4intellij.utils.OSUtils;

import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class EditorEventManagerBase {

    private static final Map<String, Set<EditorEventManager>> uriToManagers = new ConcurrentHashMap<>();
    private static final Map<Editor, EditorEventManager> editorToManager = new ConcurrentHashMap<>();
    private static final int CTRL_KEY_CODE = OSUtils.isMac() ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;
    private static volatile boolean isKeyPressed = false;
    private static volatile boolean isCtrlDown = false;
    private static volatile CtrlRangeMarker ctrlRange;

    static {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher((KeyEvent e) -> {
            int eventId = e.getID();
            if (eventId == KeyEvent.KEY_PRESSED) {
                setIsKeyPressed(true);
                if (e.getKeyCode() == CTRL_KEY_CODE) {
                    setIsCtrlDown(true);
                }
            } else if (eventId == KeyEvent.KEY_RELEASED) {
                setIsKeyPressed(false);
                if (e.getKeyCode() == CTRL_KEY_CODE) {
                    setIsCtrlDown(false);
                    if (getCtrlRange() != null) {
                        getCtrlRange().dispose();
                        setCtrlRange(null);
                    }
                }
            }
            return false;
        });
    }

    static synchronized CtrlRangeMarker getCtrlRange() {
        return ctrlRange;
    }

    static synchronized void setCtrlRange(CtrlRangeMarker ctrlRange) {
        EditorEventManagerBase.ctrlRange = ctrlRange;
    }

    // Public: HoverFeature (a different package) also reads this to decide how to show a hover hint.
    public static synchronized boolean getIsCtrlDown() {
        return isCtrlDown;
    }

    static synchronized void setIsCtrlDown(boolean isCtrlDown) {
        EditorEventManagerBase.isCtrlDown = isCtrlDown;
    }

    static synchronized boolean getIsKeyPressed() {
        return isKeyPressed;
    }

    static synchronized void setIsKeyPressed(boolean isKeyPressed) {
        EditorEventManagerBase.isKeyPressed = isKeyPressed;
    }

    private static void prune() {
        pruneEditorManagerMap();
        pruneUriManagerMap();
    }

    private static void pruneUriManagerMap() {
        synchronized (uriToManagers) {
            new HashMap<>(uriToManagers).forEach((key, value) -> {
                new HashSet<>(value).forEach((manager) -> {
                    if (!manager.wrapper.isActive() || manager.editor.isDisposed()) {
                        uriToManagers.get(key).remove(manager);
                    }
                });
                if (value.isEmpty()) {
                    uriToManagers.remove(key);
                }
            });
        }
    }

    private static void pruneEditorManagerMap() {
        new HashMap<>(editorToManager).forEach((key, value) -> {
            if (!value.wrapper.isActive()) {
                editorToManager.remove(key);
            }
        });
    }

    public static void registerManager(EditorEventManager manager) {
        String uri = FileUtils.editorToURIString(manager.editor);
        synchronized (uriToManagers) {
            if (uriToManagers.containsKey(uri)) {
                uriToManagers.get(uri).add(manager);
            } else {
                HashSet<EditorEventManager> set = new HashSet<>();
                set.add(manager);
                uriToManagers.put(uri, set);
            }
        }

        editorToManager.put(manager.editor, manager);
    }

    public static void unregisterManager(EditorEventManager manager) {
        editorToManager.remove(manager.editor);

        String uri = FileUtils.editorToURIString(manager.editor);
        synchronized (uriToManagers) {
            Set<EditorEventManager> managers = uriToManagers.get(uri);
            if (managers != null) {
                managers.remove(manager);
                if (managers.isEmpty()) {
                    uriToManagers.remove(uri);
                }
            }
        }
    }

    /**
     * @param editor An editor
     * @return The manager for the given editor, or None
     */
    public static EditorEventManager forEditor(Editor editor) {
        prune();
        return editorToManager.get(editor);
    }

    /**
     * Tells the server that all the documents will be saved.
     */
    public static void willSaveAll() {
        prune();
        editorToManager.forEach((key, value) -> value.willSave());
    }

    public static void diagnostics(String uri, List<Diagnostic> diagnostics) {
        getEditorEventManagerCopy(uri).forEach(manager -> {
            manager.diagnostics(diagnostics);
        });
    }

    private static Set<EditorEventManager> getEditorEventManagerCopy(String uri)  {
        HashSet<EditorEventManager> result = new HashSet<>();
        synchronized (uriToManagers) {
            Set<EditorEventManager> managers = uriToManagers.get(uri);
            if (managers != null) {
                result.addAll(managers);
            }
        }
        return result;
    }

    public static void willSave(String uri) {
        EditorEventManager editorManager = forUri(uri);
        if (editorManager != null) {
            editorManager.willSave();
        }
    }

    public static Set<EditorEventManager> managersForUri(String uri) {
        return getEditorEventManagerCopy(uri);
    }

    /**
     * WARNING: avoid using this function! It only gives you one
     * editorEventManager, not all and not the one of the current editor.
     * Only use for operations which are file-level (save, open, close,...)
     * otherwise use {@link #managersForUri(String)}
     * or {@link #forEditor(Editor)}
     */
    public static EditorEventManager forUri(String uri) {
        prune();
        Set<EditorEventManager> managers = managersForUri(uri);
        if (managers.size() >= 1) {
            return managers.iterator().next();
        }
        return null;
    }

    public static void documentSaved(String uri) {
        EditorEventManager editorManager = forUri(uri);
        if (editorManager != null) {
            editorManager.documentSaved();
        }
    }
}
