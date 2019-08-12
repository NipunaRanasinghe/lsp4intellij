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
package org.wso2.lsp4intellij.editor;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable;
import org.wso2.lsp4intellij.utils.OSUtils;

import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class EditorEventManagerBase {

    protected static int HOVER_TIME_THRES =
            EditorSettingsExternalizable.getInstance().getQuickDocOnMouseOverElementDelayMillis() * 1000000;
    protected static long SCHEDULE_THRES = 10000000; //Time before the Timer is scheduled
    protected static long POPUP_THRES = HOVER_TIME_THRES / 1000000 + 20;
    protected static long CTRL_THRES = 500000000; //Time between requests when ctrl is pressed (500ms)

    public static Map<String, EditorEventManager> uriToManager = new ConcurrentHashMap<>();
    public static Map<Editor, EditorEventManager> editorToManager = new ConcurrentHashMap<>();

    private static int CTRL_KEY_CODE = OSUtils.isMac() ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;
    private volatile static boolean isKeyPressed = false;
    private volatile static boolean isCtrlDown = false;
    private volatile static CtrlRangeMarker ctrlRange;

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

    static synchronized boolean getIsCtrlDown() {
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

    /**
     * @param uri A file uri
     * @return The manager for the given uri, or None
     */
    public static EditorEventManager forUri(String uri) {
        prune();
        return uriToManager.get(uri);
    }

    private static void prune() {
        editorToManager.forEach((key, value) -> {
            if (!value.wrapper.isActive()) {
                editorToManager.remove(key);
            }
        });
        uriToManager.forEach((key, value) -> {
            if (!value.wrapper.isActive()) {
                editorToManager.remove(key);
            }
        });
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
     * Tells the server that all the documents will be saved
     */
    public static void willSaveAll() {
        prune();
        editorToManager.forEach((key, value) -> value.willSave());
    }

}
