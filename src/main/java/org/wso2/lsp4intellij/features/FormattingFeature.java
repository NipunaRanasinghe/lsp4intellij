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

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;
import org.wso2.lsp4intellij.utils.DocumentUtils;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.wso2.lsp4intellij.utils.ApplicationUtils.computableReadAction;
import static org.wso2.lsp4intellij.utils.ApplicationUtils.invokeLater;
import static org.wso2.lsp4intellij.utils.DocumentUtils.toEither;

/**
 * Requests formatting for the whole document or the current selection, and applies the resulting
 * text edits.
 *
 * <p>Extracted from {@code EditorEventManager} as part of the feature-layer decomposition
 * (ARCHITECTURE.md section 2.4); {@code EditorEventManager} composes one instance per editor and
 * keeps {@code reformat()}/{@code reformatSelection()} as public delegating facades with unchanged
 * signatures, since {@code ReformatHandler} and {@code LSPShowReformatDialogAction} call them
 * directly.
 *
 * <p>Text edit application is still owned by {@code EditorEventManager} (the future
 * {@code WorkspaceEditApplier}), so it's injected here as an {@link EditApplier} callback — the
 * same one {@link CompletionFeature} uses.
 */
public final class FormattingFeature {

    private final Editor editor;
    private final LanguageServerWrapper wrapper;
    private final TextDocumentIdentifier identifier;
    private final EditApplier editApplier;

    public FormattingFeature(Editor editor, LanguageServerWrapper wrapper, TextDocumentIdentifier identifier,
            EditApplier editApplier) {
        this.editor = editor;
        this.wrapper = wrapper;
        this.identifier = identifier;
        this.editApplier = editApplier;
    }

    /**
     * Reformat the whole document.
     */
    public void reformat() {
        wrapper.pool(() -> {
            if (editor.isDisposed()) {
                return;
            }
            DocumentFormattingParams params = new DocumentFormattingParams();
            params.setTextDocument(identifier);
            FormattingOptions options = new FormattingOptions();
            options.setTabSize(DocumentUtils.getTabSize(editor));
            options.setInsertSpaces(DocumentUtils.shouldUseSpaces(editor));
            params.setOptions(options);

            CompletableFuture<List<? extends TextEdit>> request = wrapper.getRequestManager().formatting(params);
            if (request == null) {
                return;
            }
            request.thenAccept(formatting -> {
                if (formatting != null) {
                    invokeLater(() -> editApplier.apply(Integer.MAX_VALUE, toEither((List<TextEdit>) formatting),
                            "Reformat document", false, false));
                }
            });
        });
    }

    /**
     * Reformat the text currently selected in the editor.
     */
    public void reformatSelection() {
        wrapper.pool(() -> {
            if (editor.isDisposed()) {
                return;
            }
            DocumentRangeFormattingParams params = new DocumentRangeFormattingParams();
            params.setTextDocument(identifier);
            SelectionModel selectionModel = editor.getSelectionModel();
            int start = computableReadAction(selectionModel::getSelectionStart);
            int end = computableReadAction(selectionModel::getSelectionEnd);
            Position startingPos = DocumentUtils.offsetToLSPPos(editor, start);
            Position endPos = DocumentUtils.offsetToLSPPos(editor, end);
            params.setRange(new Range(startingPos, endPos));
            // Todo - Make Formatting Options configurable
            FormattingOptions options = new FormattingOptions();
            options.setTabSize(DocumentUtils.getTabSize(editor));
            options.setInsertSpaces(DocumentUtils.shouldUseSpaces(editor));
            params.setOptions(options);

            CompletableFuture<List<? extends TextEdit>> request = wrapper.getRequestManager().rangeFormatting(params);
            if (request == null) {
                return;
            }
            request.thenAccept(formatting -> {
                if (formatting == null) {
                    return;
                }
                invokeLater(() -> {
                    if (!editor.isDisposed()) {
                        editApplier.apply(Integer.MAX_VALUE, toEither((List<TextEdit>) formatting),
                                "Reformat selection", false, false);
                    }
                });
            });
        });
    }
}
