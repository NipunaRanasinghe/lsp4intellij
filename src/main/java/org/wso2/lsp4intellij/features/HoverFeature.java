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
package org.wso2.lsp4intellij.features;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.ui.Hint;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;
import org.wso2.lsp4intellij.requests.HoverHandler;
import org.wso2.lsp4intellij.utils.DocumentUtils;

import java.awt.Point;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.wso2.lsp4intellij.editor.EditorEventManagerBase.getIsCtrlDown;
import static org.wso2.lsp4intellij.requests.Timeouts.HOVER;
import static org.wso2.lsp4intellij.utils.ApplicationUtils.computableReadAction;
import static org.wso2.lsp4intellij.utils.ApplicationUtils.invokeLater;
import static org.wso2.lsp4intellij.utils.GUIUtils.createAndShowEditorHint;

/**
 * Requests hover documentation for a position and shows it as an editor hint.
 *
 * <p>Extracted from {@code EditorEventManager} as part of the feature-layer decomposition
 * (ARCHITECTURE.md section 2.4). {@code EditorEventManager} composes one instance per editor and
 * calls {@link #showHoverAt} from both {@code quickDoc} (the explicit "show documentation" action)
 * and {@code mouseMoved} (hover-on-dwell); those two callers keep their own bookkeeping
 * ({@code predTime}, ctrl-range navigation state) since that's mouse/navigation glue, not hover
 * logic itself.
 *
 * <p>The currently shown hint is still tracked by {@code EditorEventManager} (read by
 * {@code mouseMoved} to hide a stale one), so it's injected here as a setter callback rather than
 * owned by this feature — a narrower stand-in for the {@code EditorContext} shared session object
 * section 2.4 describes, which doesn't exist yet.
 */
public final class HoverFeature {

    private static final Logger LOG = Logger.getInstance(HoverFeature.class);

    private final Editor editor;
    private final LanguageServerWrapper wrapper;
    private final TextDocumentIdentifier identifier;
    private final Consumer<Hint> hintSetter;

    public HoverFeature(Editor editor, LanguageServerWrapper wrapper, TextDocumentIdentifier identifier,
            Consumer<Hint> hintSetter) {
        this.editor = editor;
        this.wrapper = wrapper;
        this.identifier = identifier;
        this.hintSetter = hintSetter;
    }

    /**
     * Requests hover documentation at the given editor position and, if any is returned, shows it
     * as a hint at the given point.
     *
     * @param editorPos The editor position
     * @param point     The point at which to show the hint
     */
    public void showHoverAt(LogicalPosition editorPos, Point point) {
        Position serverPos = computableReadAction(() -> DocumentUtils.logicalToLSPPos(editorPos, editor));
        CompletableFuture<Hover> request = wrapper.getRequestManager().hover(new HoverParams(identifier, serverPos));
        if (request == null) {
            return;
        }
        Hover hover = wrapper.getRequestExecutor().waitFor(request, HOVER);
        if (hover == null) {
            LOG.debug(String.format("Hover is null for file %s and pos (%d;%d)", identifier.getUri(),
                    serverPos.getLine(), serverPos.getCharacter()));
            return;
        }

        String string = HoverHandler.getHoverString(hover);
        if (StringUtils.isEmpty(string)) {
            LOG.warn(String.format("Hover string returned is empty for file %s and pos (%d;%d)",
                    identifier.getUri(), serverPos.getLine(), serverPos.getCharacter()));
            return;
        }

        if (getIsCtrlDown()) {
            invokeLater(() -> {
                if (!editor.isDisposed()) {
                    hintSetter.accept(createAndShowEditorHint(editor, string, point, HintManager.HIDE_BY_OTHER_HINT));
                }
            });
        } else {
            invokeLater(() -> {
                if (!editor.isDisposed()) {
                    hintSetter.accept(createAndShowEditorHint(editor, string, point));
                }
            });
        }
    }
}
