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

import com.intellij.codeHighlighting.Pass;
import com.intellij.codeHighlighting.TextEditorHighlightingPass;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.daemon.impl.HighlightInfoProcessor;
import com.intellij.codeInsight.daemon.impl.LocalInspectionsPass;
import com.intellij.codeInsight.daemon.impl.LocalInspectionsPassFactory;
import com.intellij.codeInsight.daemon.impl.UpdateHighlightersUtil;
import com.intellij.codeInsight.hint.HintManager;
import com.intellij.codeInsight.lookup.AutoCompletionPolicy;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.codeInspection.ex.InspectionManagerEx;
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper;
import com.intellij.lang.Language;
import com.intellij.lang.LanguageDocumentation;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.SelectionModel;
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
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileTypes.PlainTextLanguage;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.ui.Hint;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.TextDocumentSaveReason;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WillSaveTextDocumentParams;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.JsonRpcException;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.wso2.lsp4intellij.actions.LSPReferencesAction;
import org.wso2.lsp4intellij.client.languageserver.ServerOptions;
import org.wso2.lsp4intellij.client.languageserver.requestmanager.RequestManager;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;
import org.wso2.lsp4intellij.contributors.icon.LSPIconProvider;
import org.wso2.lsp4intellij.contributors.inspection.LSPInspection;
import org.wso2.lsp4intellij.contributors.psi.LSPPsiElement;
import org.wso2.lsp4intellij.contributors.rename.LSPRenameProcessor;
import org.wso2.lsp4intellij.requests.HoverHandler;
import org.wso2.lsp4intellij.requests.Timeouts;
import org.wso2.lsp4intellij.requests.WorkspaceEditHandler;
import org.wso2.lsp4intellij.utils.DocumentUtils;
import org.wso2.lsp4intellij.utils.FileUtils;
import org.wso2.lsp4intellij.utils.GUIUtils;

import java.awt.*;
import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.swing.*;

import static org.wso2.lsp4intellij.editor.EditorEventManagerBase.HOVER_TIME_THRES;
import static org.wso2.lsp4intellij.editor.EditorEventManagerBase.POPUP_THRES;
import static org.wso2.lsp4intellij.editor.EditorEventManagerBase.SCHEDULE_THRES;
import static org.wso2.lsp4intellij.editor.EditorEventManagerBase.getCtrlRange;
import static org.wso2.lsp4intellij.editor.EditorEventManagerBase.getIsCtrlDown;
import static org.wso2.lsp4intellij.editor.EditorEventManagerBase.getIsKeyPressed;
import static org.wso2.lsp4intellij.editor.EditorEventManagerBase.setCtrlRange;
import static org.wso2.lsp4intellij.requests.Timeout.getTimeout;
import static org.wso2.lsp4intellij.requests.Timeouts.CODEACTION;
import static org.wso2.lsp4intellij.requests.Timeouts.COMPLETION;
import static org.wso2.lsp4intellij.requests.Timeouts.DEFINITION;
import static org.wso2.lsp4intellij.requests.Timeouts.EXECUTE_COMMAND;
import static org.wso2.lsp4intellij.requests.Timeouts.HOVER;
import static org.wso2.lsp4intellij.requests.Timeouts.REFERENCES;
import static org.wso2.lsp4intellij.requests.Timeouts.WILLSAVE;
import static org.wso2.lsp4intellij.utils.ApplicationUtils.computableReadAction;
import static org.wso2.lsp4intellij.utils.ApplicationUtils.computableWriteAction;
import static org.wso2.lsp4intellij.utils.ApplicationUtils.invokeLater;
import static org.wso2.lsp4intellij.utils.ApplicationUtils.pool;
import static org.wso2.lsp4intellij.utils.ApplicationUtils.writeAction;

/**
 * Class handling events related to an Editor (a Document)
 * <p>
 * editor              The "watched" editor
 * mouseListener       A listener for mouse clicks
 * mouseMotionListener A listener for mouse movement
 * documentListener    A listener for keystrokes
 * selectionListener   A listener for selection changes in the editor
 * requestManager      The related RequestManager, connected to the right LanguageServer
 * serverOptions       The options of the server regarding completion, signatureHelp, syncKind, etc
 * wrapper             The corresponding LanguageServerWrapper
 */
public class EditorEventManager {

    protected Logger LOG = Logger.getInstance(EditorEventManager.class);

    public Editor editor;
    public LanguageServerWrapper wrapper;
    public List<String> completionTriggers;
    public Project project;
    public RequestManager requestManager;
    private ServerOptions serverOptions;
    public TextDocumentIdentifier identifier;
    private DocumentListener documentListener;
    private EditorMouseListener mouseListener;
    private EditorMouseMotionListener mouseMotionListener;

    private DidChangeTextDocumentParams changesParams;
    private TextDocumentSyncKind syncKind;
    private volatile boolean needSave = false;
    private Timer hoverThread = new Timer("Hover", true);
    private int version = -1;
    private long predTime = -1L;
    private long ctrlTime = -1L;
    private boolean isOpen = false;

    private boolean mouseInEditor = true;
    private Hint currentHint;

    protected final List<Diagnostic> diagnostics = new ArrayList<>();
    private final InspectionManagerEx inspectionManagerEx;
    private final List<LocalInspectionToolWrapper> inspectionToolWrapper;
    private final LocalInspectionsPassFactory inspectionsPassFactory;

    //Todo - Revisit arguments order and add remaining listeners
    public EditorEventManager(Editor editor, DocumentListener documentListener, EditorMouseListener mouseListener,
            EditorMouseMotionListener mouseMotionListener, RequestManager requestManager, ServerOptions serverOptions,
            LanguageServerWrapper wrapper) {

        this.editor = editor;
        this.documentListener = documentListener;
        this.mouseListener = mouseListener;
        this.mouseListener = mouseListener;
        this.mouseMotionListener = mouseMotionListener;
        this.requestManager = requestManager;
        this.serverOptions = serverOptions;
        this.wrapper = wrapper;

        this.identifier = new TextDocumentIdentifier(FileUtils.editorToURIString(editor));
        this.changesParams = new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(),
                Collections.singletonList(new TextDocumentContentChangeEvent()));
        this.syncKind = serverOptions.syncKind;

        this.completionTriggers = (serverOptions.completionOptions != null
                && serverOptions.completionOptions.getTriggerCharacters() != null) ?
                serverOptions.completionOptions.getTriggerCharacters() :
                new ArrayList<>();

        this.project = editor.getProject();

        EditorEventManagerBase.uriToManager.put(FileUtils.editorToURIString(editor), this);
        EditorEventManagerBase.editorToManager.put(editor, this);
        changesParams.getTextDocument().setUri(identifier.getUri());

        // Inspections
        this.inspectionManagerEx = (InspectionManagerEx) InspectionManagerEx.getInstance(project);
        this.inspectionToolWrapper = Collections.singletonList(new LocalInspectionToolWrapper(new LSPInspection()));
        this.inspectionsPassFactory = project.getComponent(LocalInspectionsPassFactory.class);
        this.currentHint = null;
    }

    /**
     * Calls onTypeFormatting or signatureHelp if the character typed was a trigger character
     *
     * @param c The character just typed
     */
    public void characterTyped(char c) {
        // Todo - Implement
    }

    /**
     * Tells the manager that the mouse is in the editor
     */
    public void mouseEntered() {
        mouseInEditor = true;

    }

    /**
     * Tells the manager that the mouse is not in the editor
     */
    public void mouseExited() {
        mouseInEditor = false;
    }

    /**
     * Will show documentation if the mouse doesn't move for a given time (Hover)
     *
     * @param e the event
     */
    public void mouseMoved(EditorMouseEvent e) {
        if (e.getEditor() == editor) {
            Language language = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument()).getLanguage();
            if ((LanguageDocumentation.INSTANCE.allForLanguage(language).isEmpty() || language
                    .equals(PlainTextLanguage.INSTANCE)) && (getIsCtrlDown() || EditorSettingsExternalizable
                    .getInstance().isShowQuickDocOnMouseOverElement())) {
                long curTime = System.nanoTime();
                if (predTime == (-1L) || ctrlTime == (-1L)) {
                    predTime = curTime;
                    ctrlTime = curTime;
                } else {
                    LogicalPosition lPos = getPos(e);
                    if (lPos != null) {
                        if (!getIsKeyPressed() || getIsCtrlDown()) {
                            int offset = editor.logicalPositionToOffset(lPos);
                            if (getIsCtrlDown() && curTime - ctrlTime > EditorEventManagerBase.CTRL_THRES) {
                                if (getCtrlRange() == null || !getCtrlRange().highlightContainsOffset(offset)) {
                                    if (currentHint != null) {
                                        currentHint.hide();
                                    }
                                    currentHint = null;
                                    if (getCtrlRange() != null) {
                                        getCtrlRange().dispose();
                                    }
                                    setCtrlRange(null);
                                    pool(() -> requestAndShowDoc(curTime, lPos, e.getMouseEvent().getPoint()));
                                } else if (getCtrlRange().definitionContainsOffset(offset)) {
                                    GUIUtils.createAndShowEditorHint(editor, "Click to show usages",
                                            editor.offsetToXY(offset));
                                } else {
                                    editor.getContentComponent()
                                            .setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                                }
                                ctrlTime = curTime;
                            } else {
                                scheduleDocumentation(curTime, lPos, e.getMouseEvent().getPoint());
                            }

                        }
                    }
                    predTime = curTime;
                }
            }
        } else {
            LOG.error("Wrong editor for EditorEventManager");
        }
    }

    /**
     * Called when the mouse is clicked
     * At the moment, is used by CTRL+click to see references / goto definition
     *
     * @param e The mouse event
     */
    public void mouseClicked(EditorMouseEvent e) {
        if (!getIsCtrlDown()) {
            return;
        }
        createCtrlRange(DocumentUtils.logicalToLSPPos(editor.xyToLogicalPosition(e.getMouseEvent().getPoint()), editor),
                null);
        final CtrlRangeMarker ctrlRange = getCtrlRange();

        if (ctrlRange == null) {
            int offset = editor.logicalPositionToOffset(editor.xyToLogicalPosition(e.getMouseEvent().getPoint()));
            LSPReferencesAction referencesAction = (LSPReferencesAction) ActionManager.getInstance()
                    .getAction("LSPFindUsages");
            if (referencesAction != null) {
                referencesAction.forManagerAndOffset(this, offset);
            }
            return;
        }

        Location loc = ctrlRange.location;
        invokeLater(() -> {
            if (editor.isDisposed()) {
                return;
            }
            int offset = editor.logicalPositionToOffset(editor.xyToLogicalPosition(e.getMouseEvent().getPoint()));
            String locUri = FileUtils.sanitizeURI(loc.getUri());
            if (identifier.getUri().equals(locUri) && offset >= DocumentUtils
                    .LSPPosToOffset(editor, loc.getRange().getStart()) && offset <= DocumentUtils
                    .LSPPosToOffset(editor, loc.getRange().getEnd())) {
                LSPReferencesAction referencesAction = (LSPReferencesAction) ActionManager.getInstance()
                        .getAction("LSPFindUsages");
                if (referencesAction != null) {
                    referencesAction.forManagerAndOffset(this, offset);
                }
            } else {
                VirtualFile file = null;
                try {
                    file = LocalFileSystem.getInstance().findFileByIoFile(new File(new URI(locUri)));
                } catch (URISyntaxException e1) {
                    LOG.warn("Syntax Exception occurred for uri: " + locUri);
                }
                if (file != null) {
                    OpenFileDescriptor descriptor = new OpenFileDescriptor(project, file);
                    writeAction(() -> {
                        Editor newEditor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true);
                        int startOffset = DocumentUtils.LSPPosToOffset(newEditor, loc.getRange().getStart());
                        if (newEditor != null) {
                            newEditor.getCaretModel().getCurrentCaret().moveToOffset(startOffset);
                            newEditor.getSelectionModel().setSelection(startOffset,
                                    DocumentUtils.LSPPosToOffset(newEditor, loc.getRange().getEnd()));
                        } else {
                            LOG.warn("editor is null");
                        }
                    });
                } else {
                    LOG.warn("Empty file for " + locUri);
                }
            }
            ctrlRange.dispose();
            setCtrlRange(null);
        });
    }

    private void createCtrlRange(Position logicalPos, Range range) {
        Location location = requestDefinition(logicalPos);
        if (location == null || editor.isDisposed()) {
            return;
        }
        Range corRange;
        if (range == null) {
            corRange = new Range(logicalPos, logicalPos);
        } else {
            corRange = range;
        }
        int startOffset = DocumentUtils.LSPPosToOffset(editor, corRange.getStart());
        int endOffset = DocumentUtils.LSPPosToOffset(editor, corRange.getEnd());
        boolean isDefinition = DocumentUtils.LSPPosToOffset(editor, location.getRange().getStart()) == startOffset;

        CtrlRangeMarker ctrlRange = getCtrlRange();
        if (!editor.isDisposed()) {
            if (ctrlRange != null) {
                ctrlRange.dispose();
            }
            setCtrlRange(new CtrlRangeMarker(location, editor, !isDefinition ?
                    (editor.getMarkupModel().addRangeHighlighter(startOffset, endOffset, HighlighterLayer.HYPERLINK,
                            editor.getColorsScheme().getAttributes(EditorColors.REFERENCE_HYPERLINK_COLOR),
                            HighlighterTargetArea.EXACT_RANGE)) :
                    null));
        }
    }

    /**
     * Returns the position of the definition given a position in the editor
     *
     * @param position The position
     * @return The location of the definition
     */
    private Location requestDefinition(Position position) {
        TextDocumentPositionParams params = new TextDocumentPositionParams(identifier, position);
        CompletableFuture<List<? extends Location>> request = requestManager.definition(params);

        if (request == null) {
            return null;
        }
        try {
            List<? extends Location> definition = request.get(getTimeout(DEFINITION), TimeUnit.MILLISECONDS);
            wrapper.notifySuccess(Timeouts.DEFINITION);
            if (definition != null && !definition.isEmpty()) {
                return definition.get(0);
            }
        } catch (TimeoutException e) {
            LOG.warn(e);
            wrapper.notifyFailure(Timeouts.DEFINITION);
            return null;
        } catch (InterruptedException | JsonRpcException | ExecutionException e) {
            LOG.warn(e);
            wrapper.crashed(e);
            return null;
        }
        return null;
    }

    public Pair<List<PsiElement>, List<VirtualFile>> references(int offset) {
        return references(offset, false, false);
    }

    /**
     * Returns the references given the position of the word to search for
     * Must be called from main thread
     *
     * @param offset The offset in the editor
     * @return An array of PsiElement
     */
    public Pair<List<PsiElement>, List<VirtualFile>> references(int offset, boolean getOriginalElement, boolean close) {
        Position lspPos = DocumentUtils.offsetToLSPPos(editor, offset);
        ReferenceParams params = new ReferenceParams(new ReferenceContext(getOriginalElement));
        params.setPosition(lspPos);
        params.setTextDocument(identifier);
        CompletableFuture<List<? extends Location>> request = requestManager.references(params);
        if (request != null) {
            try {
                List<? extends Location> res = request.get(getTimeout(REFERENCES), TimeUnit.MILLISECONDS);
                wrapper.notifySuccess(Timeouts.REFERENCES);
                if (res != null && res.size() > 0) {
                    List<VirtualFile> openedEditors = new ArrayList<>();
                    List<PsiElement> elements = new ArrayList<>();
                    res.forEach(l -> {
                        Position start = l.getRange().getStart();
                        Position end = l.getRange().getEnd();
                        String uri = FileUtils.sanitizeURI(l.getUri());
                        VirtualFile file = FileUtils.virtualFileFromURI(uri);
                        Editor curEditor = FileUtils.editorFromUri(uri, project);
                        if (curEditor == null) {
                            OpenFileDescriptor descriptor = new OpenFileDescriptor(project, file);
                            curEditor = computableWriteAction(
                                    () -> FileEditorManager.getInstance(project).openTextEditor(descriptor, false));
                            openedEditors.add(file);
                        }
                        int logicalStart = DocumentUtils.LSPPosToOffset(curEditor, start);
                        int logicalEnd = DocumentUtils.LSPPosToOffset(curEditor, end);
                        String name = curEditor.getDocument().getText(new TextRange(logicalStart, logicalEnd));
                        elements.add(new LSPPsiElement(name, project, logicalStart, logicalEnd,
                                PsiDocumentManager.getInstance(project).getPsiFile(curEditor.getDocument())));
                    });
                    if (close) {
                        writeAction(
                                () -> openedEditors.forEach(f -> FileEditorManager.getInstance(project).closeFile(f)));
                        openedEditors.clear();
                    }
                    return new Pair<>(elements, openedEditors);
                } else {
                    return new Pair<>(null, null);
                }
            } catch (TimeoutException e) {
                LOG.warn(e);
                wrapper.notifyFailure(Timeouts.REFERENCES);
                return new Pair<>(null, null);
            } catch (InterruptedException | JsonRpcException | ExecutionException e) {
                LOG.warn(e);
                wrapper.crashed(e);
                return new Pair<>(null, null);
            }
        }
        return new Pair<>(null, null);
    }

    /**
     * @return The current diagnostics highlights
     */
    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }

    /**
     * Applies the diagnostics to the document
     *
     * @param diagnostics The diagnostics to apply from the server
     */
    public void diagnostics(List<Diagnostic> diagnostics) {
        if (!editor.isDisposed()) {
            synchronized (this.diagnostics) {
                this.diagnostics.clear();
                this.diagnostics.addAll(diagnostics);
            }
            // Forcefully triggers local inspection tool.
            runInspection();
        }
    }

    /**
     * Triggers local inspections for a given PSI file.
     */
    private void runInspection() {
        PsiFile psiFile = computableReadAction(
                () -> PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument()));
        Document document = PsiDocumentManager.getInstance(project).getDocument(psiFile);
        if (document == null) {
            return;
        }

        DumbService.getInstance(project).smartInvokeLater(() -> {
            TextEditorHighlightingPass highlightingPass = this.inspectionsPassFactory
                    .createMainHighlightingPass(psiFile, document, HighlightInfoProcessor.getEmpty());
            if (highlightingPass instanceof LocalInspectionsPass) {
                ProgressManager.getInstance().runProcess(() -> {
                    ((LocalInspectionsPass) highlightingPass)
                            .doInspectInBatch(inspectionManagerEx.createNewGlobalContext(false), inspectionManagerEx,
                                    inspectionToolWrapper);
                    UpdateHighlightersUtil
                            .setHighlightersToEditor(psiFile.getProject(), document, 0, document.getTextLength(),
                                    highlightingPass.getInfos(), null, Pass.WHOLE_FILE_LOCAL_INSPECTIONS);
                }, new EmptyProgressIndicator());
            }
        });
    }

    /**
     * Retrieves the commands needed to apply a CodeAction
     *
     * @param element The element which needs the CodeAction
     * @return The list of commands, or null if none are given / the request times out
     */
    public List<Either<Command, CodeAction>> codeAction(LSPPsiElement element) {
        CodeActionParams params = new CodeActionParams();
        params.setTextDocument(identifier);
        Range range = new Range(DocumentUtils.offsetToLSPPos(editor, element.start),
                DocumentUtils.offsetToLSPPos(editor, element.end));
        params.setRange(range);
        CodeActionContext context = new CodeActionContext(diagnostics);
        params.setContext(context);
        CompletableFuture<List<Either<Command, CodeAction>>> future = requestManager.codeAction(params);
        if (future != null) {
            try {
                List<Either<Command, CodeAction>> res = future.get(getTimeout(CODEACTION), TimeUnit.MILLISECONDS);
                wrapper.notifySuccess(CODEACTION);
                return res;
            } catch (TimeoutException e) {
                LOG.warn(e);
                wrapper.notifyFailure(CODEACTION);
                return null;
            } catch (InterruptedException | JsonRpcException | ExecutionException e) {
                LOG.warn(e);
                wrapper.crashed(e);
                return null;
            }
        }
        return null;
    }

    /**
     * Reformat the whole document
     */
    public void reformat() {
        pool(() -> {
            if (editor.isDisposed()) {
                return;
            }
            DocumentFormattingParams params = new DocumentFormattingParams();
            params.setTextDocument(identifier);
            FormattingOptions options = new FormattingOptions();
            params.setOptions(options);

            CompletableFuture<List<? extends TextEdit>> request = requestManager.formatting(params);
            if (request == null) {
                return;
            }
            request.thenAccept(formatting -> {
                if (formatting != null) {
                    invokeLater(() -> applyEdit((List<TextEdit>) formatting, "Reformat document"));
                }
            });
        });
    }

    /**
     * Reformat the text currently selected in the editor
     */
    public void reformatSelection() {
        pool(() -> {
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
            params.setOptions(options);

            CompletableFuture<List<? extends TextEdit>> request = requestManager.rangeFormatting(params);
            if (request == null) {
                return;
            }
            request.thenAccept(formatting -> {
                if (formatting == null) {
                    return;
                }
                invokeLater(() -> {
                    if (!editor.isDisposed()) {
                        applyEdit((List<TextEdit>) formatting, "Reformat selection");
                    }
                });
            });
        });
    }

    public void rename(String renameTo) {
        rename(renameTo, editor.getCaretModel().getCurrentCaret().getOffset());
    }

    /**
     * Rename a symbol in the document
     *
     * @param renameTo The new name
     */
    public void rename(String renameTo, int offset) {
        pool(() -> {
            if (editor.isDisposed()) {
                return;
            }
            Position servPos = DocumentUtils.offsetToLSPPos(editor, offset);
            RenameParams params = new RenameParams(identifier, servPos, renameTo);
            CompletableFuture<WorkspaceEdit> request = requestManager.rename(params);
            if (request != null) {
                request.thenAccept(res -> {
                    WorkspaceEditHandler
                            .applyEdit(res, "Rename to " + renameTo, new ArrayList<>(LSPRenameProcessor.getEditors()));
                    LSPRenameProcessor.clearEditors();
                });
            }
        });
    }

    /**
     * Immediately requests the server for documentation at the current editor position
     *
     * @param editor The editor
     */
    public void quickDoc(Editor editor) {
        if (editor == this.editor) {
            LogicalPosition caretPos = editor.getCaretModel().getLogicalPosition();
            Point pointPos = editor.logicalPositionToXY(caretPos);
            long currentTime = System.nanoTime();
            pool(() -> requestAndShowDoc(currentTime, caretPos, pointPos));
            predTime = currentTime;
        } else {
            LOG.warn("Not same editor!");
        }
    }

    /**
     * Gets the hover request and shows it
     *
     * @param curTime   The current time
     * @param editorPos The editor position
     * @param point     The point at which to show the hint
     */
    private void requestAndShowDoc(long curTime, LogicalPosition editorPos, Point point) {
        Position serverPos = computableReadAction(() -> DocumentUtils.logicalToLSPPos(editorPos, editor));
        CompletableFuture<Hover> request = requestManager.hover(new TextDocumentPositionParams(identifier, serverPos));
        if (request == null) {
            return;
        }
        try {
            Hover hover = request.get(getTimeout(HOVER), TimeUnit.MILLISECONDS);
            wrapper.notifySuccess(Timeouts.HOVER);
            if (hover != null) {
                String string = HoverHandler.getHoverString(hover);
                if (string != null && !string.equals("")) {
                    if (getIsCtrlDown()) {
                        invokeLater(() -> {
                            if (!editor.isDisposed()) {
                                currentHint = GUIUtils
                                        .createAndShowEditorHint(editor, string, point, HintManager.HIDE_BY_OTHER_HINT);
                            }
                        });
                        // createCtrlRange(serverPos, hover.getRange());
                    } else {
                        invokeLater(() -> {
                            if (!editor.isDisposed()) {
                                currentHint = GUIUtils.createAndShowEditorHint(editor, string, point);
                            }
                        });
                    }
                } else {
                    LOG.warn("Hover string returned is null for file " + identifier.getUri() + " and pos (" + serverPos
                            .getLine() + ";" + serverPos.getCharacter() + ")");
                }
            } else {
                LOG.warn("Hover is null for file " + identifier.getUri() + " and pos (" + serverPos.getLine() + ";"
                        + serverPos.getCharacter() + ")");
            }
        } catch (TimeoutException e) {
            LOG.warn(e);
            wrapper.notifyFailure(Timeouts.HOVER);
        } catch (InterruptedException | JsonRpcException | ExecutionException e) {
            LOG.warn(e);
            wrapper.crashed(e);
        }
    }

    /**
     * Returns the completion suggestions given a position
     *
     * @param pos The LSP position
     * @return The suggestions
     */
    public Iterable<? extends LookupElement> completion(Position pos) {
        List<LookupElement> lookupItems = new ArrayList<>();
        CompletableFuture<Either<List<CompletionItem>, CompletionList>> request = requestManager
                .completion(new CompletionParams(identifier, pos));

        if (request == null) {
            return lookupItems;
        }
        try {
            Either<List<CompletionItem>, CompletionList> res = request
                    .get(getTimeout(COMPLETION), TimeUnit.MILLISECONDS);
            wrapper.notifySuccess(Timeouts.COMPLETION);
            if (res != null) {
                if (res.getLeft() != null) {
                    for (CompletionItem item : res.getLeft()) {
                        lookupItems.add(createLookupItem(item));
                    }
                } else if (res.getRight() != null) {
                    for (CompletionItem item : res.getRight().getItems()) {
                        lookupItems.add(createLookupItem(item));
                    }
                }
            }
        } catch (TimeoutException e) {
            LOG.warn(e);
            wrapper.notifyFailure(Timeouts.COMPLETION);
        } catch (JsonRpcException | ExecutionException | InterruptedException e) {
            LOG.warn(e);
            wrapper.crashed(e);
        }
        return lookupItems;
    }

    /**
     * Creates a LookupElement given a CompletionItem
     *
     * @param item The CompletionItem
     * @return The corresponding LookupElement
     */
    private LookupElement createLookupItem(CompletionItem item) {
        Command command = item.getCommand();
        Object data = item.getData();
        String detail = item.getDetail();
        Either<String, MarkupContent> doc = item.getDocumentation();
        String filterText = item.getFilterText();
        String insertText = item.getInsertText();
        InsertTextFormat insertFormat = item.getInsertTextFormat();
        CompletionItemKind kind = item.getKind();
        String label = item.getLabel();
        TextEdit textEdit = item.getTextEdit();
        List<TextEdit> addTextEdits = item.getAdditionalTextEdits();
        String sortText = item.getSortText();
        String presentableText = (label != null && label != "") ? label : (insertText != null) ? insertText : "";
        String tailText = (detail != null) ? detail : "";
        LSPIconProvider iconProvider = GUIUtils.getIconProviderFor(wrapper.getServerDefinition());
        Icon icon = iconProvider.getCompletionIcon(kind);
        LookupElementBuilder lookupElementBuilder;

        if (insertText != null && !insertText.equals("")) {
            lookupElementBuilder = LookupElementBuilder.create(insertText);
        } else if (label != null && !label.equals("")) {
            lookupElementBuilder = LookupElementBuilder.create(label);
        } else {
            return LookupElementBuilder.create((String) null);
        }

        if (textEdit != null) {
            if (addTextEdits != null) {
                addTextEdits.add(textEdit);
                lookupElementBuilder = setInsertHandler(lookupElementBuilder, addTextEdits, command, label);
            } else {
                lookupElementBuilder = setInsertHandler(lookupElementBuilder, Collections.singletonList(textEdit),
                        command, label);
            }
        } else if (addTextEdits != null) {
            lookupElementBuilder = setInsertHandler(lookupElementBuilder, addTextEdits, command, label);
        } else if (command != null) {
            lookupElementBuilder = lookupElementBuilder
                    .withInsertHandler((InsertionContext context, LookupElement lookupElement) -> {
                        context.commitDocument();
                        invokeLater(() -> executeCommands(command));
                    });
        }

        if (kind == CompletionItemKind.Keyword) {
            lookupElementBuilder = lookupElementBuilder.withBoldness(true);
        }

        return lookupElementBuilder.withPresentableText(presentableText).withTypeText(tailText, true).withIcon(icon)
                .withAutoCompletionPolicy(AutoCompletionPolicy.SETTINGS_DEPENDENT);
    }

    /**
     * Schedule the documentation using the Timer
     *
     * @param time      The current time
     * @param editorPos The position in the editor
     * @param point     The point where to show the doc
     */
    private void scheduleDocumentation(Long time, LogicalPosition editorPos, Point point) {
        if (editorPos == null || (time - predTime <= SCHEDULE_THRES)) {
            return;
        }
        try {
            hoverThread.schedule(new TimerTask() {
                @Override
                public void run() {
                    long curTime = System.nanoTime();
                    if (!editor.isDisposed() && (System.nanoTime() - predTime > HOVER_TIME_THRES) && mouseInEditor
                            && editor.getContentComponent().hasFocus() && (!getIsKeyPressed() || getIsCtrlDown())) {
                        requestAndShowDoc(curTime, editorPos, point);
                    }
                }
            }, POPUP_THRES);
        } catch (Exception e) {
            hoverThread = new Timer("Hover", true); //Restart Timer if it crashes
            LOG.warn(e);
            LOG.warn("Hover timer reset");
        }
    }

    /**
     * Returns the logical position given a mouse event
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
                    doc.getLineEndOffset(editorPos.line - 1) :
                    0);
            int maxY = doc.getLineEndOffset(editorPos.line) - (editorPos.line > 0 ?
                    doc.getLineEndOffset(editorPos.line - 1) :
                    0);
            return (editorPos.column > minY && editorPos.column < maxY) ? editorPos : null;
        }
    }

    private LookupElementBuilder setInsertHandler(LookupElementBuilder builder, List<TextEdit> edits, Command command,
            String label) {
        return builder.withInsertHandler((InsertionContext context, LookupElement lookupElement) -> {
            context.commitDocument();
            invokeLater(() -> {
                applyEdit(edits, "Completion : " + label);
                if (command != null) {
                    executeCommands(command);
                }
            });
        });
    }

    boolean applyEdit(TextEdit edit, String name) {
        List<TextEdit> textEdits = new ArrayList<>();
        textEdits.add(edit);
        return applyEdit(textEdits, name);
    }

    boolean applyEdit(List<TextEdit> edits, String name) {
        return applyEdit(Integer.MAX_VALUE, edits, name, false);
    }

    /**
     * Applies the given edits to the document
     *
     * @param version    The version of the edits (will be discarded if older than current version)
     * @param edits      The edits to apply
     * @param name       The name of the edits (Rename, for example)
     * @param closeAfter will close the file after edits if set to true
     * @return True if the edits were applied, false otherwise
     */
    boolean applyEdit(int version, List<TextEdit> edits, String name, boolean closeAfter) {
        Runnable runnable = getEditsRunnable(version, edits, name);
        writeAction(() -> {
            if (runnable != null) {
                CommandProcessor.getInstance()
                        .executeCommand(project, runnable, name, "LSPPlugin", editor.getDocument());
            }
            if (closeAfter) {
                FileEditorManager.getInstance(project).closeFile(
                        PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument()).getVirtualFile());
            }
        });
        return runnable != null;
    }

    /**
     * Returns a Runnable used to apply the given edits and save the document
     * Used by WorkspaceEditHandler (allows to revert a rename for example)
     *
     * @param version The edit version
     * @param edits   The edits
     * @param name    The name of the edit
     * @return The runnable
     */
    public Runnable getEditsRunnable(int version, Iterable<TextEdit> edits, String name) {
        if (version >= this.version) {
            Document document = editor.getDocument();
            if (document.isWritable()) {
                return () -> edits.forEach(edit -> {
                    String text = edit.getNewText();
                    Range range = edit.getRange();
                    // LSPPosToOffset() will return -1, if any IndexOutOfBoundException is occurred.
                    int start = DocumentUtils.LSPPosToOffset(editor, range.getStart());
                    int end = DocumentUtils.LSPPosToOffset(editor, range.getEnd());
                    if (text == null || text.isEmpty()) {
                        document.deleteString(start, end);
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
                    }
                    saveDocument();
                });
            } else {
                LOG.warn("Document is not writable");
                return null;
            }
        } else {
            LOG.warn("Edit version " + version + " is older than current version " + this.version);
            return null;
        }
    }

    private void executeCommands(Command command) {
        List<Command> commands = new ArrayList<>();
        commands.add(command);
        executeCommands(commands);
    }

    /**
     * Sends commands to execute to the server and applies the changes returned if the future returns a WorkspaceEdit
     *
     * @param commands The commands to execute
     */
    public void executeCommands(List<Command> commands) {
        pool(() -> {
            if (!editor.isDisposed()) {
                commands.stream().map(c -> {
                    ExecuteCommandParams params = new ExecuteCommandParams();
                    params.setArguments(c.getArguments());
                    params.setCommand(c.getCommand());
                    return requestManager.executeCommand(params);
                }).filter(Objects::nonNull).forEach(f -> {
                    try {
                        f.get(getTimeout(EXECUTE_COMMAND), TimeUnit.MILLISECONDS);
                        wrapper.notifySuccess(Timeouts.EXECUTE_COMMAND);
                    } catch (TimeoutException te) {
                        LOG.warn(te);
                        wrapper.notifyFailure(Timeouts.EXECUTE_COMMAND);
                    } catch (JsonRpcException | ExecutionException | InterruptedException e) {
                        LOG.warn(e);
                        wrapper.crashed(e);
                    }
                });
            }
        });
    }

    private void saveDocument() {
        FileDocumentManager.getInstance().saveDocument(editor.getDocument());
    }

    /**
     * Adds all the listeners
     */
    public void registerListeners() {
        editor.getDocument().addDocumentListener(documentListener);
        editor.addEditorMouseListener(mouseListener);
        editor.addEditorMouseMotionListener(mouseMotionListener);
        // Todo - Implement
        // editor.getSelectionModel.addSelectionListener(selectionListener)
    }

    /**
     * Removes all the listeners
     */
    public void removeListeners() {
        editor.getDocument().removeDocumentListener(documentListener);
        editor.removeEditorMouseListener(mouseListener);
        editor.removeEditorMouseMotionListener(mouseMotionListener);
        // Todo - Implement
        // editor.getSelectionModel.removeSelectionListener(selectionListener)
    }

    /**
     * Notifies the server that the corresponding document has been closed
     */
    public void documentClosed() {
        pool(() -> {
            if (this.isOpen) {
                requestManager.didClose(new DidCloseTextDocumentParams(identifier));
                isOpen = false;
                EditorEventManagerBase.editorToManager.remove(editor);
                EditorEventManagerBase.uriToManager.remove(FileUtils.editorToURIString(editor));
            } else {
                LOG.warn("Editor " + identifier.getUri() + " was already closed");
            }
        });
    }

    public void documentOpened() {
        pool(() -> {
            if (!editor.isDisposed()) {
                if (isOpen) {
                    LOG.warn("Editor " + editor + " was already open");
                } else {
                    requestManager.didOpen(new DidOpenTextDocumentParams(
                            new TextDocumentItem(identifier.getUri(), wrapper.serverDefinition.id, version++,
                                    editor.getDocument().getText())));
                    isOpen = true;
                }
            }
        });
    }

    public void documentChanged(DocumentEvent event) {
        if (editor.isDisposed()) {
            return;
        }
        if (event.getDocument() == editor.getDocument()) {
            //Todo - restore when adding hover support
            // long predTime = System.nanoTime(); //So that there are no hover events while typing
            changesParams.getTextDocument().setVersion(version++);

            if (syncKind == TextDocumentSyncKind.Incremental) {
                TextDocumentContentChangeEvent changeEvent = changesParams.getContentChanges().get(0);
                CharSequence newText = event.getNewFragment();
                int offset = event.getOffset();
                int newTextLength = event.getNewLength();
                Position lspPosition = DocumentUtils.offsetToLSPPos(editor, offset);
                int startLine = lspPosition.getLine();
                int startColumn = lspPosition.getCharacter();
                CharSequence oldText = event.getOldFragment();

                //if text was deleted/replaced, calculate the end position of inserted/deleted text
                int endLine, endColumn;
                if (oldText.length() > 0) {
                    endLine = startLine + StringUtil.countNewLines(oldText);
                    String[] oldLines = oldText.toString().split("\n");
                    int oldTextLength = oldLines.length == 0 ? 0 : oldLines[oldLines.length - 1].length();
                    endColumn = oldLines.length == 1 ? startColumn + oldTextLength : oldTextLength;
                } else { //if insert or no text change, the end position is the same
                    endLine = startLine;
                    endColumn = startColumn;
                }
                Range range = new Range(new Position(startLine, startColumn), new Position(endLine, endColumn));
                changeEvent.setRange(range);
                changeEvent.setRangeLength(newTextLength);
                changeEvent.setText(newText.toString());
            } else if (syncKind == TextDocumentSyncKind.Full) {
                changesParams.getContentChanges().get(0).setText(editor.getDocument().getText());
            }
            requestManager.didChange(changesParams);
        } else {
            LOG.error("Wrong document for the EditorEventManager");
        }
    }

    /**
     * Notifies the server that the corresponding document has been saved
     */
    public void documentSaved() {
        pool(() -> {
            if (!editor.isDisposed()) {
                DidSaveTextDocumentParams params = new DidSaveTextDocumentParams(identifier,
                        editor.getDocument().getText());
                requestManager.didSave(params);
            }
        });
    }

    /**
     * Indicates that the document will be saved
     */
    //TODO Manual
    public void willSave() {
        if (wrapper.isWillSaveWaitUntil() && !needSave) {
            willSaveWaitUntil();
        } else
            pool(() -> {
                if (!editor.isDisposed()) {
                    requestManager.willSave(new WillSaveTextDocumentParams(identifier, TextDocumentSaveReason.Manual));
                }
            });
    }

    /**
     * If the server supports willSaveWaitUntil, the LSPVetoer will check if  a save is needed
     * (needSave will basically alterate between true or false, so the document will always be saved)
     */
    private void willSaveWaitUntil() {
        if (wrapper.isWillSaveWaitUntil()) {
            pool(() -> {
                if (!editor.isDisposed()) {
                    WillSaveTextDocumentParams params = new WillSaveTextDocumentParams(identifier,
                            TextDocumentSaveReason.Manual);
                    CompletableFuture<List<TextEdit>> future = requestManager.willSaveWaitUntil(params);
                    if (future != null) {
                        try {
                            List<TextEdit> edits = future.get(getTimeout(WILLSAVE), TimeUnit.MILLISECONDS);
                            wrapper.notifySuccess(Timeouts.WILLSAVE);
                            if (edits != null) {
                                invokeLater(() -> applyEdit(edits, "WaitUntil edits"));
                            }
                        } catch (TimeoutException e) {
                            LOG.warn(e);
                            wrapper.notifyFailure(Timeouts.WILLSAVE);
                        } catch (JsonRpcException | ExecutionException | InterruptedException e) {
                            LOG.warn(e);
                            wrapper.crashed(e);
                        } finally {
                            needSave = true;
                            saveDocument();
                        }
                    } else {
                        needSave = true;
                        saveDocument();
                    }
                }
            });
        } else {
            LOG.error("Server doesn't support WillSaveWaitUntil");
            needSave = true;
            saveDocument();
        }
    }
}
