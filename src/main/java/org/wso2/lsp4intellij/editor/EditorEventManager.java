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

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.lang.Language;
import com.intellij.lang.LanguageDocumentation;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.event.EditorMouseListener;
import com.intellij.openapi.editor.event.EditorMouseMotionListener;
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileTypes.PlainTextLanguage;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.ui.Hint;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.InsertReplaceEdit;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentSaveReason;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WillSaveTextDocumentParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jetbrains.annotations.NotNull;
import org.wso2.lsp4intellij.actions.LSPReferencesAction;
import org.wso2.lsp4intellij.client.languageserver.ServerOptions;
import org.wso2.lsp4intellij.client.languageserver.requestmanager.RequestManager;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;
import org.wso2.lsp4intellij.features.CodeActionFeature;
import org.wso2.lsp4intellij.features.CodeActionOverrides;
import org.wso2.lsp4intellij.features.CompletionFeature;
import org.wso2.lsp4intellij.features.CompletionOverrides;
import org.wso2.lsp4intellij.features.DiagnosticsFeature;
import org.wso2.lsp4intellij.features.FormattingFeature;
import org.wso2.lsp4intellij.features.HoverFeature;
import org.wso2.lsp4intellij.features.LspAnnotation;
import org.wso2.lsp4intellij.features.NavigationFeature;
import org.wso2.lsp4intellij.features.ReferenceLookup;
import org.wso2.lsp4intellij.features.RenameFeature;
import org.wso2.lsp4intellij.features.SignatureHelpFeature;
import org.wso2.lsp4intellij.features.SilentAnnotation;
import org.wso2.lsp4intellij.listeners.LSPCaretListenerImpl;
import org.wso2.lsp4intellij.utils.DocumentUtils;
import org.wso2.lsp4intellij.utils.FileUtils;

import java.awt.Cursor;
import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.wso2.lsp4intellij.editor.EditorEventManagerBase.getCtrlRange;
import static org.wso2.lsp4intellij.editor.EditorEventManagerBase.getIsCtrlDown;
import static org.wso2.lsp4intellij.editor.EditorEventManagerBase.getIsKeyPressed;
import static org.wso2.lsp4intellij.editor.EditorEventManagerBase.setCtrlRange;
import static org.wso2.lsp4intellij.requests.Timeouts.WILLSAVE;
import static org.wso2.lsp4intellij.utils.ApplicationUtils.invokeLater;
import static org.wso2.lsp4intellij.utils.ApplicationUtils.writeAction;
import static org.wso2.lsp4intellij.utils.DocumentUtils.toEither;
import static org.wso2.lsp4intellij.utils.GUIUtils.createAndShowEditorHint;

/**
 * Class handling events related to an Editor (a Document).
 * <p>
 * editor              The "watched" editor
 * mouseListener       A listener for mouse clicks
 * mouseMotionListener A listener for mouse movement
 * documentListener    A listener for keystrokes
 * selectionListener   A listener for selection changes in the editor
 * wrapper.getRequestManager()      The related wrapper.getRequestManager(), connected to the right LanguageServer
 * serverOptions       The options of the server regarding completion, signatureHelp, syncKind, etc
 * wrapper             The corresponding LanguageServerWrapper
 */
public class EditorEventManager implements CompletionOverrides, CodeActionOverrides, ReferenceLookup {

    /**
     * @deprecated moved to {@link CompletionFeature#SNIPPET_PLACEHOLDER_REGEX}. Kept here so plugins
     *         recompiling against this class continue to resolve it; read it from
     *         {@code CompletionFeature} instead. No {@code since} is declared because the project
     *         version is derived from the git tag at build time, so the release this lands in is not
     *         known here.
     */
    @Deprecated(forRemoval = true)
    public static final String SNIPPET_PLACEHOLDER_REGEX = CompletionFeature.SNIPPET_PLACEHOLDER_REGEX;

    public final DocumentEventManager documentEventManager;
    protected static final Logger LOG = Logger.getInstance(EditorEventManager.class);

    public Editor editor;
    public LanguageServerWrapper wrapper;
    private Project project;
    private TextDocumentIdentifier identifier;
    private EditorMouseListener mouseListener;
    private EditorMouseMotionListener mouseMotionListener;
    private LSPCaretListenerImpl caretListener;

    public List<String> completionTriggers;
    private TextDocumentSyncKind syncKind;
    private volatile boolean needSave = false;
    private long predTime = -1L;
    private long ctrlTime = -1L;
    private boolean isOpen = false;

    private boolean mouseInEditor = true;
    private Hint currentHint;

    private final DiagnosticsFeature diagnosticsFeature;
    private final CompletionFeature completionFeature;
    private final HoverFeature hoverFeature;
    private final SignatureHelpFeature signatureHelpFeature;
    private final NavigationFeature navigationFeature;
    private final CodeActionFeature codeActionFeature;
    private final RenameFeature renameFeature;
    private final FormattingFeature formattingFeature;

    private static final long CTRL_THRESH = EditorSettingsExternalizable.getInstance().getTooltipsDelay() * 1000000;

    //Todo - Revisit arguments order and add remaining listeners
    public EditorEventManager(Editor editor, DocumentListener documentListener, EditorMouseListener mouseListener,
                              EditorMouseMotionListener mouseMotionListener, LSPCaretListenerImpl caretListener,
                              RequestManager requestmanager, ServerOptions serverOptions,
                              LanguageServerWrapper wrapper) {

        this.editor = editor;
        this.mouseListener = mouseListener;
        this.mouseMotionListener = mouseMotionListener;
        this.wrapper = wrapper;
        this.caretListener = caretListener;
        this.identifier = new TextDocumentIdentifier(FileUtils.editorToURIString(editor));
        this.syncKind = serverOptions.syncKind;
        this.completionTriggers = (serverOptions.completionOptions != null
                && serverOptions.completionOptions.getTriggerCharacters() != null) ?
                serverOptions.completionOptions.getTriggerCharacters() :
                new ArrayList<>();

        List<String> signatureTriggers = (serverOptions.signatureHelpOptions != null
                && serverOptions.signatureHelpOptions.getTriggerCharacters() != null) ?
                serverOptions.signatureHelpOptions.getTriggerCharacters() :
                new ArrayList<>();

        this.project = editor.getProject();
        this.diagnosticsFeature = new DiagnosticsFeature(editor, project);
        this.completionFeature = new CompletionFeature(editor, project, wrapper, identifier, completionTriggers,
                this::applyEdit, this::executeCommands, this::signatureHelp, this);
        this.hoverFeature = new HoverFeature(editor, wrapper, identifier, hint -> this.currentHint = hint);
        this.signatureHelpFeature = new SignatureHelpFeature(editor, wrapper, identifier, signatureTriggers,
                hint -> this.currentHint = hint);
        this.navigationFeature = new NavigationFeature(editor, project, wrapper, identifier);
        this.codeActionFeature = new CodeActionFeature(editor, wrapper, identifier, diagnosticsFeature, this);
        this.renameFeature = new RenameFeature(editor, project, wrapper, identifier, this);
        this.formattingFeature = new FormattingFeature(editor, wrapper, identifier, this::applyEdit);

        EditorEventManagerBase.registerManager(this);

        this.currentHint = null;

        this.documentEventManager = new DocumentEventManager(editor.getDocument(), documentListener, syncKind, wrapper);
    }

    @SuppressWarnings("unused")
    public Project getProject() {
        return project;
    }

    @SuppressWarnings("unused")
    public RequestManager getRequestManager() {
        return wrapper.getRequestManager();
    }

    @SuppressWarnings("unused")
    public TextDocumentIdentifier getIdentifier() {
        return identifier;
    }

    /**
     * Calls onTypeFormatting or signatureHelp if the character typed was a trigger character.
     *
     * @param c The character just typed
     */
    public void characterTyped(char c) {
        signatureHelpFeature.characterTyped(c);
    }

    /**
     * Tells the manager that the mouse is in the editor.
     */
    public void mouseEntered() {
        mouseInEditor = true;
    }

    /**
     * Tells the manager that the mouse is not in the editor.
     */
    public void mouseExited() {
        mouseInEditor = false;
    }

    /**
     * Will show documentation if the mouse doesn't move for a given time (Hover).
     *
     * @param e the event
     */
    public void mouseMoved(EditorMouseEvent e) {

        if (e.getEditor() != editor) {
            LOG.error("Wrong editor for EditorEventManager");
            return;
        }

        PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        if (psiFile == null) {
            return;
        }
        Language language = psiFile.getLanguage();
        if ((!LanguageDocumentation.INSTANCE.allForLanguage(language).isEmpty() && !isSupportedLanguageFile(psiFile))
                || (!getIsCtrlDown()
                && !EditorSettingsExternalizable.getInstance().isShowQuickDocOnMouseOverElement())) {
            return;
        }

        long curTime = System.nanoTime();
        if (predTime == (-1L) || ctrlTime == (-1L)) {
            predTime = curTime;
            ctrlTime = curTime;
        } else {
            LogicalPosition lPos = getPos(e);
            if (lPos == null || getIsKeyPressed() && !getIsCtrlDown()) {
                return;
            }

            int offset = editor.logicalPositionToOffset(lPos);
            if ((getIsCtrlDown() || EditorSettingsExternalizable.getInstance().isShowQuickDocOnMouseOverElement())
                    && curTime - ctrlTime > CTRL_THRESH) {
                if (getCtrlRange() == null || !getCtrlRange().highlightContainsOffset(offset)) {
                    if (currentHint != null) {
                        currentHint.hide();
                    }
                    currentHint = null;
                    if (getCtrlRange() != null) {
                        getCtrlRange().dispose();
                    }
                    setCtrlRange(null);
                    wrapper.pool(() -> hoverFeature.showHoverAt(lPos, e.getMouseEvent().getPoint()));
                } else if (getCtrlRange().definitionContainsOffset(offset)) {
                    createAndShowEditorHint(editor, "Click to show usages", editor.offsetToXY(offset));
                } else {
                    editor.getContentComponent().setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                }
                ctrlTime = curTime;
            }
            predTime = curTime;
        }
    }

    private boolean isSupportedLanguageFile(PsiFile file) {
        return file.getLanguage().isKindOf(PlainTextLanguage.INSTANCE)
                || FileUtils.isFileSupported(file.getVirtualFile());
    }

    /**
     * Called when the mouse is clicked.
     * At the moment, is used by CTRL+click to see references / goto definition
     *
     * @param e The mouse event
     */
    public void mouseClicked(EditorMouseEvent e) {
        if (e.getEditor() != editor) {
            LOG.error("Wrong editor for EditorEventManager");
            return;
        }
        if (getIsCtrlDown()) {
            // If CTRL/CMD key is pressed, triggers goto definition/references and hover.
            try {
                trySourceNavigationAndHover(e);
            } catch (Exception err) {
                LOG.warn("Error occurred when trying source navigation", err);
            }
        }
    }

    private void createCtrlRange(Position logicalPos, Range range, Location location) {
        if (location == null || location.getRange() == null || editor.isDisposed()) {
            return;
        }
        Range corRange;
        if (range == null) {
            corRange = new Range(logicalPos, logicalPos);
        } else {
            corRange = range;
        }
        int startOffset = DocumentUtils.lspPosToOffset(editor, corRange.getStart());
        int endOffset = DocumentUtils.lspPosToOffset(editor, corRange.getEnd());
        boolean isDefinition = DocumentUtils.lspPosToOffset(editor, location.getRange().getStart()) == startOffset;

        CtrlRangeMarker ctrlRange = getCtrlRange();
        if (!editor.isDisposed()) {
            if (ctrlRange != null) {
                ctrlRange.dispose();
            }
            setCtrlRange(new CtrlRangeMarker(location, editor, !isDefinition ?
                    (editor.getMarkupModel().addRangeHighlighter(startOffset, endOffset, HighlighterLayer.HYPERLINK,
                            editor.getColorsScheme().getAttributes(EditorColors.REFERENCE_HYPERLINK_COLOR),
                            HighlighterTargetArea.EXACT_RANGE)) : null));
        }
    }

    public Pair<List<PsiElement>, List<VirtualFile>> references(int offset) {
        return navigationFeature.references(offset, false, false);
    }

    /**
     * Returns the references given the position of the word to search for.
     * May be called from any thread; opening and closing editors for reference locations is marshaled
     * to the event dispatch thread. When called from a background thread, the read lock must not be held.
     *
     * @param offset The offset in the editor
     * @return An array of PsiElement
     */
    public Pair<List<PsiElement>, List<VirtualFile>> references(int offset, boolean getOriginalElement, boolean close) {
        return navigationFeature.references(offset, getOriginalElement, close);
    }

    /**
     * @return The current diagnostics highlights
     */
    public List<Diagnostic> getDiagnostics() {
        return diagnosticsFeature.diagnostics();
    }

    /**
     * @return The current diagnostic annotations
     */
    public List<LspAnnotation> getAnnotations() {
        return codeActionFeature.getAnnotations();
    }

    public void setAnnotations(List<LspAnnotation> annotations) {
        codeActionFeature.setAnnotations(annotations);
    }

    public void markAnnotated() {
        codeActionFeature.markAnnotated();
    }

    public boolean isDiagnosticSyncRequired() {
        return diagnosticsFeature.isSyncRequired();
    }

    public boolean isCodeActionSyncRequired() {
        return codeActionFeature.isCodeActionSyncRequired();
    }

    /**
     * Applies the diagnostics to the document.
     *
     * @param diagnostics The diagnostics to apply from the server
     */
    public void diagnostics(List<Diagnostic> diagnostics) {
        diagnosticsFeature.publish(diagnostics);
    }

    /**
     * Retrieves the commands needed to apply a CodeAction.
     *
     * @param offset The cursor position(offset) which should be evaluated for code action request.
     * @return The list of commands, or null if none are given / the request times out
     */
    @SuppressWarnings("WeakerAccess")
    public List<Either<Command, CodeAction>> codeAction(int offset) {
        return codeActionFeature.codeAction(offset);
    }

    public CodeAction resolvedCodeAction(CodeAction codeAction) {
        return codeActionFeature.resolvedCodeAction(codeAction);
    }

    /**
     * Calls signatureHelp at the current editor caret position.
     */
    @SuppressWarnings("WeakerAccess")
    public void signatureHelp() {
        signatureHelpFeature.signatureHelp();
    }

    /**
     * Reformat the whole document.
     */
    public void reformat() {
        formattingFeature.reformat();
    }

    /**
     * Reformat the text currently selected in the editor.
     */
    public void reformatSelection() {
        formattingFeature.reformatSelection();
    }

    public void rename(String renameTo) {
        renameFeature.rename(renameTo);
    }

    /**
     * Rename a symbol in the document.
     *
     * @param renameTo The new name
     */
    public void rename(String renameTo, int offset) {
        renameFeature.rename(renameTo, offset);
    }

    /**
     * Immediately requests the server for documentation at the current editor position.
     *
     * @param editor The editor
     */
    public void quickDoc(Editor editor) {
        if (editor == this.editor) {
            LogicalPosition caretPos = editor.getCaretModel().getLogicalPosition();
            Point pointPos = editor.logicalPositionToXY(caretPos);
            long currentTime = System.nanoTime();
            wrapper.pool(() -> hoverFeature.showHoverAt(caretPos, pointPos));
            predTime = currentTime;
        } else {
            LOG.warn("Not same editor!");
        }
    }

    /**
     * Returns the completion suggestions given a position.
     *
     * @param pos The LSP position
     * @return The suggestions
     */
    public Iterable<? extends LookupElement> completion(Position pos) {
        return completionFeature.completion(pos);
    }

    /**
     * Creates a LookupElement given a CompletionItem.
     *
     * @param item The CompletionItem
     * @return The corresponding LookupElement
     */
    @SuppressWarnings("WeakerAccess")
    public LookupElement createLookupItem(CompletionItem item) {
        return completionFeature.createLookupItem(item);
    }

    @SuppressWarnings("WeakerAccess")
    public LookupElementBuilder addCompletionInsertHandlers(
            CompletionItem item, LookupElementBuilder builder,
            String lookupString) {
        return completionFeature.addCompletionInsertHandlers(item, builder, lookupString);
    }

    @NotNull
    public String getCompletionPrefix(Editor editor, int offset) {
        return completionFeature.getCompletionPrefix(editor, offset);
    }

    @SuppressWarnings("WeakerAccess")
    public void prepareAndRunSnippet(String insertText) {
        completionFeature.prepareAndRunSnippet(insertText);
    }

    /**
     * Returns the logical position given a mouse event.
     *
     * @param e The event
     * @return The position (or null if out of bounds)
     */
    private LogicalPosition getPos(EditorMouseEvent e) {
        Point mousePos = e.getMouseEvent().getPoint();
        LogicalPosition editorPos = editor.xyToLogicalPosition(mousePos);
        Document doc = e.getEditor().getDocument();
        int maxLines = doc.getLineCount();
        if (editorPos.line >= maxLines) {
            return null;
        } else {
            int minY = doc.getLineStartOffset(editorPos.line) - (editorPos.line > 0 ?
                    doc.getLineEndOffset(editorPos.line - 1) : 0);
            int maxY = doc.getLineEndOffset(editorPos.line) - (editorPos.line > 0 ?
                    doc.getLineEndOffset(editorPos.line - 1) : 0);
            return (editorPos.column > minY && editorPos.column < maxY) ? editorPos : null;
        }
    }

    boolean applyEdit(List<Either<TextEdit, InsertReplaceEdit>> edits, String name, boolean setCaret) {
        return applyEdit(Integer.MAX_VALUE, edits, name, false, setCaret);
    }

    /**
     * Applies the given edits to the document.
     *
     * @param version    The version of the edits (will be discarded if older than current version)
     * @param edits      The edits to apply
     * @param name       The name of the edits (Rename, for example)
     * @param closeAfter will close the file after edits if set to true
     * @return True if the edits were applied, false otherwise
     */
    boolean applyEdit(int version, List<Either<TextEdit, InsertReplaceEdit>> edits,
            String name, boolean closeAfter, boolean setCaret) {
        Runnable runnable = getEditsRunnable(version, edits, name, setCaret);
        writeAction(() -> {
            if (runnable != null) {
                CommandProcessor.getInstance()
                        .executeCommand(project, runnable, name, "LSPPlugin", editor.getDocument());
            }
            if (closeAfter) {
                PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
                if (file != null) {
                    FileEditorManager.getInstance(project).closeFile(file.getVirtualFile());
                }
            }
        });
        return runnable != null;
    }

    /**
     * Returns a Runnable used to apply the given edits and save the document.
     * Used by WorkspaceEditHandler (allows to revert a rename for example)
     *
     * @param version The edit version
     * @param edits   The edits
     * @param name    The name of the edit
     * @return The runnable
     */
    public Runnable getEditsRunnable(int version,
            List<Either<TextEdit, InsertReplaceEdit>> edits,
            String name, boolean setCaret) {
        if (version < this.documentEventManager.getDocumentVersion()) {
            LOG.warn(String.format("Edit version %d is older than current version %d",
                    version, this.documentEventManager.getDocumentVersion()));
            return null;
        }
        if (edits == null) {
            LOG.warn("Received edits list is null.");
            return null;
        }
        if (editor.isDisposed()) {
            LOG.warn("Text edits couldn't be applied as the editor is already disposed.");
            return null;
        }
        Document document = editor.getDocument();
        if (!document.isWritable()) {
            LOG.warn("Document is not writable");
            return null;
        }

        return () -> {
            // Creates a sorted edit list based on the insertion position and the edits will be applied from the bottom
            // to the top of the document. Otherwise all the other edit ranges
            // will be invalid after the very first edit,
            // since the document is changed.
            List<LSPTextEdit> lspEdits = new ArrayList<>();
            edits.forEach(edit -> {
                if (edit.isLeft()) {
                    String text = edit.getLeft().getNewText();
                    Range range = edit.getLeft().getRange();
                    if (range != null) {
                        int start = DocumentUtils.lspPosToOffset(editor, range.getStart());
                        int end = DocumentUtils.lspPosToOffset(editor, range.getEnd());
                        lspEdits.add(new LSPTextEdit(text, start, end));
                    }
                } else if (edit.isRight()) {
                    String text = edit.getRight().getNewText();
                    Range range = edit.getRight().getInsert();

                    if (range != null) {
                        int start = DocumentUtils.lspPosToOffset(editor, range.getStart());
                        int end = DocumentUtils.lspPosToOffset(editor, range.getEnd());
                        lspEdits.add(new LSPTextEdit(text, start, end));
                    } else if ((range = edit.getRight().getReplace()) != null) {
                        int start = DocumentUtils.lspPosToOffset(editor, range.getStart());
                        int end = DocumentUtils.lspPosToOffset(editor, range.getEnd());
                        lspEdits.add(new LSPTextEdit(text, start, end));
                    }
                }
            });

            // Sort according to the start offset, in descending order.
            Collections.sort(lspEdits);

            lspEdits.forEach(edit -> {
                String text = edit.getText();
                int start = edit.getStartOffset();
                int end = edit.getEndOffset();
                if (StringUtils.isEmpty(text)) {
                    document.deleteString(start, end);
                    if (setCaret) {
                        editor.getCaretModel().moveToOffset(start);
                    }
                } else {
                    text = text.replace(DocumentUtils.WIN_SEPARATOR, DocumentUtils.LINUX_SEPARATOR);
                    if (end >= 0) {
                        if (end - start <= 0) {
                            document.insertString(start, text);
                        } else {
                            document.replaceString(start, end, text);
                        }
                    } else if (start == 0) {
                        document.setText(text);
                    } else if (start > 0) {
                        document.insertString(start, text);
                    }
                    if (setCaret) {
                        editor.getCaretModel().moveToOffset(start + text.length());
                    }
                }
                saveDocument();
            });
        };
    }

    /**
     * Sends commands to execute to the server and applies the changes returned if the future returns a WorkspaceEdit.
     *
     * @param commands The commands to execute
     */
    public void executeCommands(List<Command> commands) {
        codeActionFeature.executeCommands(commands);
    }

    private void saveDocument() {
        FileDocumentManager.getInstance().saveDocument(editor.getDocument());
    }

    /**
     * Adds all the listeners.
     */
    public void registerListeners() {
        editor.addEditorMouseListener(mouseListener);
        editor.addEditorMouseMotionListener(mouseMotionListener);
        editor.getCaretModel().addCaretListener(caretListener);
        // Todo - Implement
        // editor.getSelectionModel.addSelectionListener(selectionListener)
    }

    /**
     * Removes all the listeners.
     */
    public void removeListeners() {
        editor.removeEditorMouseListener(mouseListener);
        editor.removeEditorMouseMotionListener(mouseMotionListener);
        editor.getCaretModel().removeCaretListener(caretListener);
        // Todo - Implement
        // editor.getSelectionModel.removeSelectionListener(selectionListener)
    }

    /**
     * Notifies the server that the corresponding document has been closed.
     */
    public void documentClosed() {
        wrapper.pool(() -> {
            if (this.isOpen) {
                isOpen = false;

                documentEventManager.documentClosed();
                EditorEventManagerBase.unregisterManager(this);
            } else {
                LOG.warn("Editor " + identifier.getUri() + " was already closed");
            }
        });
    }

    public void documentOpened() {
        wrapper.pool(() -> {
            if (editor.isDisposed()) {
                return;
            }
            if (isOpen) {
                LOG.warn("Editor " + editor + " was already open");
            } else {
                documentEventManager.documentOpened();

                isOpen = true;
            }
        });
    }

    public void documentChanged(DocumentEvent event) {
        if (editor.isDisposed()) {
            return;
        }
        if (event.getDocument() == editor.getDocument()) {
            codeActionFeature.getSilentAnnotations().clear();
            documentEventManager.documentChanged(event);
        } else {
            LOG.error("Wrong document for the EditorEventManager");
        }
    }

    /**
     * Notifies the server that the corresponding document has been saved.
     */
    public void documentSaved() {
        wrapper.pool(() -> {
            if (!editor.isDisposed()) {
                DidSaveTextDocumentParams params = new DidSaveTextDocumentParams(
                        identifier, editor.getDocument().getText());
                wrapper.getRequestManager().didSave(params);
            }
        });
    }

    /**
     * Indicates that the document will be saved.
     */
    //TODO Manual
    public void willSave() {
        if (wrapper.isWillSaveWaitUntil() && !needSave) {
            willSaveWaitUntil();
        } else {
            wrapper.pool(() -> {
                if (!editor.isDisposed()) {
                    wrapper.getRequestManager().willSave(
                            new WillSaveTextDocumentParams(
                                    identifier, TextDocumentSaveReason.Manual));
                }
            });
        }
    }

    /**
     * If the server supports willSaveWaitUntil, the LSPVetoer will check if  a save is needed.
     * (needSave will basically alternate between true or false, so the document will always be saved)
     */
    private void willSaveWaitUntil() {
        if (wrapper.isWillSaveWaitUntil()) {
            wrapper.pool(() -> {
                if (editor.isDisposed()) {
                    return;
                }
                WillSaveTextDocumentParams params = new WillSaveTextDocumentParams(identifier,
                        TextDocumentSaveReason.Manual);
                CompletableFuture<List<TextEdit>> future = wrapper.getRequestManager().willSaveWaitUntil(params);
                if (future != null) {
                    List<TextEdit> edits = wrapper.getRequestExecutor().waitFor(future, WILLSAVE);
                    if (edits != null) {
                        invokeLater(() -> applyEdit(toEither(edits), "WaitUntil edits", false));
                    }
                }
                needSave = true;
                saveDocument();
            });
        } else {
            LOG.error("Server doesn't support WillSaveWaitUntil");
            needSave = true;
            saveDocument();
        }
    }

    /** 
     * Tries to go to definition / show usages based on the element which is under the cursor.
     */
    private void trySourceNavigationAndHover(EditorMouseEvent e) {
        if (editor.isDisposed()) {
            return;
        }

        Position position = DocumentUtils.logicalToLSPPos(
                editor.xyToLogicalPosition(e.getMouseEvent().getPoint()), editor);
        wrapper.pool(() -> {
            // Resolves the definition off the EDT; range markup and navigation run on the EDT afterwards.
            Location definitionLocation = navigationFeature.definition(position);
            invokeLater(() -> {
                if (editor.isDisposed()) {
                    return;
                }

                createCtrlRange(position, null, definitionLocation);
                final CtrlRangeMarker ctrlRange = getCtrlRange();

                if (ctrlRange == null) {
                    int offset = editor.logicalPositionToOffset(
                            editor.xyToLogicalPosition(e.getMouseEvent().getPoint()));
                    LSPReferencesAction referencesAction = (LSPReferencesAction) ActionManager.getInstance()
                            .getAction("LSPFindUsages");
                    if (referencesAction != null) {
                        referencesAction.forManagerAndOffset(this, offset);
                    }
                    return;
                }

                Location loc = ctrlRange.location;
                int offset = editor.logicalPositionToOffset(editor.xyToLogicalPosition(e.getMouseEvent().getPoint()));
                String locUri = FileUtils.sanitizeURI(loc.getUri());

                if (identifier.getUri().equals(locUri)
                        && offset >= DocumentUtils.lspPosToOffset(editor, loc.getRange().getStart())
                        && offset <= DocumentUtils.lspPosToOffset(editor, loc.getRange().getEnd())) {
                    LSPReferencesAction referencesAction = (LSPReferencesAction) ActionManager.getInstance()
                            .getAction("LSPFindUsages");
                    if (referencesAction != null) {
                        referencesAction.forManagerAndOffset(this, offset);
                    }
                } else {
                    navigationFeature.gotoLocation(loc);
                }

                ctrlRange.dispose();
                setCtrlRange(null);
            });
        });
    }

    public void gotoLocation(Location loc) {
        navigationFeature.gotoLocation(loc);
    }

    public void requestAndShowCodeActions() {
        codeActionFeature.requestAndShowCodeActions();
    }

    public List<SilentAnnotation> getSilentAnnotations() {
        return codeActionFeature.getSilentAnnotations();
    }

    public void triggerIntentionActions() {
        codeActionFeature.triggerIntentionActions();
    }

    public static class LSPTextEdit implements Comparable<LSPTextEdit> {
        private String text;
        private int startOffset;
        private int endOffset;

        public LSPTextEdit(String text, int start, int end) {
            this.text = text;
            this.startOffset = start;
            this.endOffset = end;
        }

        public String getText() {
            return text;
        }

        public int getStartOffset() {
            return startOffset;
        }

        public int getEndOffset() {
            return endOffset;
        }

        @Override
        public int compareTo(@NotNull LSPTextEdit te) {
            return Integer.compare(te.getStartOffset(), getStartOffset());
        }
    }

}
