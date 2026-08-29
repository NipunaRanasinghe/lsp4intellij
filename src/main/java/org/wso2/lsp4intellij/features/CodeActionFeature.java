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

import com.intellij.lang.annotation.Annotation;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import groovy.lang.Tuple3;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;
import org.wso2.lsp4intellij.contributors.fixes.LSPCodeActionFix;
import org.wso2.lsp4intellij.contributors.fixes.LSPCommandFix;
import org.wso2.lsp4intellij.utils.DocumentUtils;
import org.wso2.lsp4intellij.utils.FileUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static org.wso2.lsp4intellij.requests.Timeouts.CODEACTION;
import static org.wso2.lsp4intellij.requests.Timeouts.EXECUTE_COMMAND;
import static org.wso2.lsp4intellij.utils.ApplicationUtils.computableReadAction;
import static org.wso2.lsp4intellij.utils.ApplicationUtils.invokeLater;

/**
 * Requests code actions, resolves incomplete ones, registers them as quick fixes on the
 * annotations the diagnostics pass already created, and executes LSP commands (used both by code
 * actions and by completion items that carry a command).
 *
 * <p>Extracted from {@code EditorEventManager} as part of the feature-layer decomposition
 * (ARCHITECTURE.md section 2.4); {@code EditorEventManager} composes one instance per editor and
 * keeps every method here that {@code LSPAnnotator}, {@code LSPCommandFix}/{@code LSPCodeActionFix},
 * or {@code LSPCaretListenerImpl} call directly as a public delegating facade with an unchanged
 * signature.
 *
 * <p>Depends directly on this editor's {@link DiagnosticsFeature} (to read the diagnostic context
 * for a code-action request, and to force the re-annotation a newly attached fix needs) rather
 * than an injected callback — the two features are peers composed together, not a stand-in for the
 * {@code EditorContext} object other extractions in this decomposition needed one for.
 */
public final class CodeActionFeature {

    private final Editor editor;
    private final LanguageServerWrapper wrapper;
    private final TextDocumentIdentifier identifier;
    private final DiagnosticsFeature diagnosticsFeature;
    private final CodeActionOverrides overrides;

    private List<Annotation> annotations = new ArrayList<>();
    private AnnotationHolder anonHolder;
    private volatile boolean codeActionSyncRequired = false;
    private boolean isTriggerIntentionActions = false;
    private final List<Tuple3<HighlightSeverity, TextRange, LSPCodeActionFix>> silentAnnotations = new ArrayList<>();

    public CodeActionFeature(Editor editor, LanguageServerWrapper wrapper, TextDocumentIdentifier identifier,
            DiagnosticsFeature diagnosticsFeature, CodeActionOverrides overrides) {
        this.editor = editor;
        this.wrapper = wrapper;
        this.identifier = identifier;
        this.diagnosticsFeature = diagnosticsFeature;
        this.overrides = overrides;
    }

    /**
     * @return The current diagnostic annotations
     */
    public synchronized List<Annotation> getAnnotations() {
        this.codeActionSyncRequired = false;
        return this.annotations;
    }

    public synchronized void setAnnotations(List<Annotation> annotations) {
        this.annotations = annotations;
    }

    public synchronized void setAnonHolder(AnnotationHolder holder) {
        this.anonHolder = holder;
    }

    public synchronized boolean isCodeActionSyncRequired() {
        return this.codeActionSyncRequired;
    }

    /**
     * Sets the sync-required flag {@link #getAnnotations()} clears. Package-private and used only by
     * the test for that clearing: the production paths that raise this flag all live inside
     * {@code showCodeActions}, which needs a real editor and a server response, so there is
     * otherwise no way to observe the flag in anything other than its initial {@code false} state.
     */
    synchronized void markCodeActionSyncRequiredForTest() {
        this.codeActionSyncRequired = true;
    }

    /**
     * Retrieves the commands needed to apply a CodeAction.
     *
     * @param offset The cursor position(offset) which should be evaluated for code action request.
     * @return The list of commands, or null if none are given / the request times out
     */
    @SuppressWarnings("WeakerAccess")
    public List<Either<Command, CodeAction>> codeAction(int offset) {
        CodeActionParams params = new CodeActionParams();
        params.setTextDocument(identifier);
        Range range = new Range(DocumentUtils.offsetToLSPPos(editor, offset),
                DocumentUtils.offsetToLSPPos(editor, offset));
        params.setRange(range);

        // Calculates the diagnostic context.
        List<Diagnostic> diagnosticContext = new ArrayList<>();
        diagnosticsFeature.withDiagnostics(diags -> diags.forEach(diagnostic -> {
            int startOffset = DocumentUtils.lspPosToOffset(editor, diagnostic.getRange().getStart());
            int endOffset = DocumentUtils.lspPosToOffset(editor, diagnostic.getRange().getEnd());
            if (offset >= startOffset && offset <= endOffset) {
                diagnosticContext.add(diagnostic);
            }
        }));

        CodeActionContext context = new CodeActionContext(diagnosticContext);
        params.setContext(context);
        CompletableFuture<List<Either<Command, CodeAction>>> future = wrapper.getRequestManager().codeAction(params);
        return wrapper.getRequestExecutor().waitFor(future, CODEACTION);
    }

    public CodeAction resolvedCodeAction(CodeAction codeAction) {
        CompletableFuture<CodeAction> future = wrapper.getRequestManager().resolveCodeAction(codeAction);
        return wrapper.getRequestExecutor().waitFor(future, CODEACTION);
    }

    /**
     * Sends commands to execute to the server and applies the changes returned if the future returns a WorkspaceEdit.
     *
     * @param commands The commands to execute
     */
    public void executeCommands(List<Command> commands) {
        wrapper.pool(() -> {
            if (editor.isDisposed()) {
                return;
            }
            commands.stream().map(c -> {
                ExecuteCommandParams params = new ExecuteCommandParams();
                params.setArguments(c.getArguments());
                params.setCommand(c.getCommand());
                return wrapper.getRequestManager().executeCommand(params);
            }).filter(Objects::nonNull).forEach(f ->
                    wrapper.getRequestExecutor().waitFor(f, EXECUTE_COMMAND));
        });
    }

    public void requestAndShowCodeActions() {
        wrapper.pool(() -> {
            if (editor.isDisposed()) {
                return;
            }

            // Sends the code action request and resolves incomplete code actions while off the EDT;
            // only the annotation bookkeeping runs on the EDT.
            int caretPos = computableReadAction(() -> editor.getCaretModel().getCurrentCaret().getOffset());
            List<Either<Command, CodeAction>> codeActionResp = overrides.codeAction(caretPos);
            if (codeActionResp == null || codeActionResp.isEmpty()) {
                return;
            }
            List<Either<Command, CodeAction>> codeActions = new ArrayList<>();
            for (Either<Command, CodeAction> element : codeActionResp) {
                if (element == null) {
                    continue;
                }
                if (element.isRight() && element.getRight().getEdit() == null) {
                    CodeAction resolved = overrides.resolvedCodeAction(element.getRight());
                    if (resolved != null && resolved.getEdit() != null) {
                        codeActions.add(Either.forRight(resolved));
                        continue;
                    }
                }
                codeActions.add(element);
            }
            invokeLater(() -> showCodeActions(caretPos, codeActions));
        });
    }

    private void showCodeActions(int caretPos, List<Either<Command, CodeAction>> codeActions) {
        if (editor.isDisposed()) {
            return;
        }
        if (annotations == null) {
            annotations = new ArrayList<>();
        }

        codeActions.forEach(element -> {
                if (element.isLeft()) {
                    Command command = element.getLeft();
                    Annotation annotWithCodeAction = null;
                    for (Annotation annotation : annotations) {
                        int start = annotation.getStartOffset();
                        int end = annotation.getEndOffset();
                        if (start <= caretPos && end >= caretPos) {
                            if (annotation.getQuickFixes() == null || annotation.getQuickFixes().isEmpty()) {
                                isTriggerIntentionActions = true;
                            }
                            annotation.registerFix(new LSPCommandFix(FileUtils.editorToURIString(editor), command),
                                    new TextRange(start, end));
                            codeActionSyncRequired = true;
                            annotWithCodeAction = annotation;
                            break;
                        }
                    }
                    if (annotWithCodeAction != null) {
                        annotations.remove(annotWithCodeAction);
                        annotations.add(0, annotWithCodeAction);
                    }
                } else if (element.isRight()) {
                    CodeAction codeAction = element.getRight();
                    List<Diagnostic> diagnosticContext = codeAction.getDiagnostics();
                    Annotation annotWithCodeAction = null;
                    for (Annotation annotation : annotations) {
                        int start = annotation.getStartOffset();
                        int end = annotation.getEndOffset();
                        if (start <= caretPos && end >= caretPos) {
                            if (annotation.getQuickFixes() == null || annotation.getQuickFixes().isEmpty()) {
                                isTriggerIntentionActions = true;
                            }
                            annotation.registerFix(new LSPCodeActionFix(FileUtils.editorToURIString(editor),
                                    codeAction), new TextRange(start, end));
                            codeActionSyncRequired = true;
                            annotWithCodeAction = annotation;
                            break;
                        }
                    }
                    if (annotWithCodeAction != null) {
                        annotations.remove(annotWithCodeAction);
                        annotations.add(0, annotWithCodeAction);
                    }

                    // If the code actions does not have a diagnostics context, creates an intention action for
                    // the current line.
                    if ((diagnosticContext == null || diagnosticContext.isEmpty())
                            && anonHolder != null && !codeActionSyncRequired) {
                        // Calculates text range of the current line.
                        int line = editor.getCaretModel().getCurrentCaret().getLogicalPosition().line;
                        int startOffset = editor.getDocument().getLineStartOffset(line);
                        int endOffset = editor.getDocument().getLineEndOffset(line);
                        TextRange range = new TextRange(startOffset, endOffset);
                        CodeAction finalCodeAction = codeAction;
                        boolean found = silentAnnotations.stream()
                                .anyMatch(silentAnnotation ->
                                        silentAnnotation.getSecond().getStartOffset() == startOffset &&
                                        silentAnnotation.getSecond().getEndOffset() == endOffset &&
                                        silentAnnotation.getThird().getText().equals(finalCodeAction.getTitle())
                                 );
                        if (!found) {
                            Tuple3<HighlightSeverity, TextRange, LSPCodeActionFix> sAnnotation =
                                    new Tuple3<>(
                                            HighlightSeverity.INFORMATION,
                                            range,
                                            new LSPCodeActionFix(FileUtils.editorToURIString(editor), codeAction)
                                    );
                            silentAnnotations.add(sAnnotation);
                            isTriggerIntentionActions = true;
                        }
                        codeActionSyncRequired = true;
                    }
                }
        });
        // If code actions are updated, forcefully triggers the inspection tool.
        if (codeActionSyncRequired) {
            // double-delay the update to ensure that the code analyzer finishes.
            invokeLater(diagnosticsFeature::restartDaemonCodeAnalyzer);
        }
    }

    public List<Tuple3<HighlightSeverity, TextRange, LSPCodeActionFix>> getSilentAnnotations() {
        return silentAnnotations;
    }

    public void triggerIntentionActions() {
        if (isTriggerIntentionActions) {
            isTriggerIntentionActions = false;
            diagnosticsFeature.restartDaemonCodeAnalyzer();
        }
    }
}
