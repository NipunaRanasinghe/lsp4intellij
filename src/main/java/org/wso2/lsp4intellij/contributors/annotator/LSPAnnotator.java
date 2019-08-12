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
package org.wso2.lsp4intellij.contributors.annotator;

import com.google.common.base.Predicates;
import com.intellij.lang.annotation.Annotation;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.wso2.lsp4intellij.IntellijLanguageClient;
import org.wso2.lsp4intellij.contributors.fixes.LSPCodeActionFix;
import org.wso2.lsp4intellij.contributors.fixes.LSPCommandFix;
import org.wso2.lsp4intellij.contributors.psi.LSPPsiElement;
import org.wso2.lsp4intellij.editor.EditorEventManager;
import org.wso2.lsp4intellij.editor.EditorEventManagerBase;
import org.wso2.lsp4intellij.utils.DocumentUtils;
import org.wso2.lsp4intellij.utils.FileUtils;

import java.util.ConcurrentModificationException;
import java.util.List;

public class LSPAnnotator extends ExternalAnnotator {
    private static final Object RESULT = new Object();

    @Nullable
    @Override
    public Object collectInformation(@NotNull PsiFile file, @NotNull Editor editor, boolean hasErrors) {

        try {
            VirtualFile virtualFile = file.getVirtualFile();

            // If the file is not supported, we skips the annotation by returning null.
            if (!FileUtils.isFileSupported(virtualFile) || !IntellijLanguageClient.isExtensionSupported(virtualFile)) {
                return null;
            }
            String uri = FileUtils.VFSToURI(virtualFile);
            EditorEventManager eventManager = EditorEventManagerBase.forUri(uri);

            // If the diagnostics list is locked, we need to skip annotating the file.
            if (eventManager == null || eventManager.isDiagnosticsLocked()) {
                return null;
            }
            return RESULT;
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    @Override
    public Object doAnnotate(Object collectedInfo) {
        return RESULT;
    }

    @Override
    public void apply(@NotNull PsiFile file, Object annotationResult, @NotNull AnnotationHolder holder) {

        VirtualFile virtualFile = file.getVirtualFile();
        if (FileUtils.isFileSupported(virtualFile) && IntellijLanguageClient.isExtensionSupported(virtualFile)) {
            String uri = FileUtils.VFSToURI(virtualFile);
            EditorEventManager eventManager = EditorEventManagerBase.forUri(uri);

            if (eventManager == null || eventManager.isDiagnosticsLocked()) {
                return;
            }
            try {
                createAnnotations(file, holder, eventManager);
            } catch (ConcurrentModificationException e) {
                // Todo
            }
        }
    }

    private void createAnnotations(PsiFile file, AnnotationHolder holder, EditorEventManager eventManager) {
        final List<Diagnostic> diagnostics = eventManager.getDiagnostics();
        final Editor editor = eventManager.editor;
        final String uri = FileUtils.VFSToURI(file.getVirtualFile());

        diagnostics.forEach(d -> {
            final int start = DocumentUtils.LSPPosToOffset(editor, d.getRange().getStart());
            final int end = DocumentUtils.LSPPosToOffset(editor, d.getRange().getEnd());

            if (start >= end) {
                return;
            }

            Annotation annotation;
            final TextRange textRange = new TextRange(start, end);
            switch (d.getSeverity()) {
                case Error:
                    annotation = holder.createErrorAnnotation(textRange, d.getMessage());
                    break;
                case Warning:
                    annotation = holder.createWarningAnnotation(textRange, d.getMessage());
                    break;
                case Information:
                    annotation = holder.createInfoAnnotation(textRange, d.getMessage());
                    break;
                default:
                    annotation = holder.createWeakWarningAnnotation(textRange, d.getMessage());
                    break;
            }

            final String name = editor.getDocument().getText(textRange);
            final LSPPsiElement element = new LSPPsiElement(name, editor.getProject(), start, end, file);
            final List<Either<Command, CodeAction>> codeAction = eventManager.codeAction(element);
            if (codeAction != null) {
                codeAction.stream().filter(Predicates.notNull()).forEach(e -> {
                    if (e.isLeft()) {
                        annotation.registerFix(new LSPCommandFix(uri, e.getLeft()), textRange);
                    } else if (e.isRight()) {
                        annotation.registerFix(new LSPCodeActionFix(uri, e.getRight()), textRange);
                    }
                });
            }
        });
    }
}
