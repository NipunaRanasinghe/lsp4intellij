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
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;
import org.wso2.lsp4intellij.contributors.rename.LSPRenameProcessor;
import org.wso2.lsp4intellij.requests.WorkspaceEditHandler;
import org.wso2.lsp4intellij.utils.DocumentUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.wso2.lsp4intellij.utils.ApplicationUtils.invokeLater;
import static org.wso2.lsp4intellij.utils.ApplicationUtils.writeAction;

/**
 * Renames a symbol: finds its references first (so files opened only to compute the workspace
 * edit can be closed again afterward), then sends the LSP rename request and applies the result.
 *
 * <p>Extracted from {@code EditorEventManager} as part of the feature-layer decomposition
 * (ARCHITECTURE.md section 2.4); {@code EditorEventManager} composes one instance per editor and
 * keeps {@code rename(String)} as a public delegating facade with an unchanged signature, since
 * {@code LSPRenameHandler} calls it directly.
 *
 * <p>Takes reference lookup as a {@link ReferenceLookup} rather than composing
 * {@link NavigationFeature} directly. {@code EditorEventManager.references(int, boolean, boolean)}
 * is public and was called unqualified — so virtually — from the {@code rename} this was extracted
 * from, and calling {@link NavigationFeature} here would skip an override of it supplied by an
 * extension's {@code EditorEventManager} subclass.
 */
public final class RenameFeature {

    private final Editor editor;
    private final Project project;
    private final LanguageServerWrapper wrapper;
    private final TextDocumentIdentifier identifier;
    private final ReferenceLookup referenceLookup;

    public RenameFeature(Editor editor, Project project, LanguageServerWrapper wrapper,
            TextDocumentIdentifier identifier, ReferenceLookup referenceLookup) {
        this.editor = editor;
        this.project = project;
        this.wrapper = wrapper;
        this.identifier = identifier;
        this.referenceLookup = referenceLookup;
    }

    public void rename(String renameTo) {
        rename(renameTo, editor.getCaretModel().getCurrentCaret().getOffset());
    }

    /**
     * Rename a symbol in the document.
     *
     * @param renameTo The new name
     */
    public void rename(String renameTo, int offset) {
        wrapper.pool(() -> {
            if (editor.isDisposed()) {
                return;
            }
            VirtualFile[] openedFiles = FileEditorManager.getInstance(project).getOpenFiles();
            Pair<List<PsiElement>, List<VirtualFile>> references = referenceLookup.references(offset, true, false);
            List<VirtualFile> toClose = new ArrayList<>();
            if (references.getSecond() != null) {
                for (VirtualFile file : references.getSecond()) {
                    if (!Arrays.asList(openedFiles).contains(file)) {
                        toClose.add(file);
                    }
                }
            }
            Position servPos = DocumentUtils.offsetToLSPPos(editor, offset);
            RenameParams params = new RenameParams(identifier, servPos, renameTo);
            CompletableFuture<WorkspaceEdit> request = wrapper.getRequestManager().rename(params);
            if (request != null) {
                request.thenAccept(res -> {
                    boolean isApplied = WorkspaceEditHandler.applyEdit(res, "Rename to " + renameTo, toClose);
                    LSPRenameProcessor.clearEditors();
                    if (!isApplied) {
                        for (VirtualFile file : toClose) {
                            invokeLater(() -> writeAction(
                                    () -> FileEditorManager.getInstance(project).closeFile(file)));
                        }
                    }
                });
            }
        });
    }
}
