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

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;
import org.wso2.lsp4intellij.contributors.psi.LSPPsiElement;
import org.wso2.lsp4intellij.utils.DocumentUtils;
import org.wso2.lsp4intellij.utils.FileUtils;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.wso2.lsp4intellij.requests.Timeouts.DEFINITION;
import static org.wso2.lsp4intellij.requests.Timeouts.REFERENCES;
import static org.wso2.lsp4intellij.utils.ApplicationUtils.computableReadAction;
import static org.wso2.lsp4intellij.utils.ApplicationUtils.computableWriteAction;
import static org.wso2.lsp4intellij.utils.ApplicationUtils.invokeAndWait;
import static org.wso2.lsp4intellij.utils.ApplicationUtils.writeAction;

/**
 * Resolves go-to-definition and find-references requests, and opens/navigates to their locations.
 *
 * <p>Extracted from {@code EditorEventManager} as part of the feature-layer decomposition
 * (ARCHITECTURE.md section 2.4); {@code EditorEventManager} composes one instance per editor and
 * keeps {@code references()}/{@code gotoLocation()} as public delegating facades with unchanged
 * signatures, since {@code LSPReferencesAction}, {@code LSPRenameHandler},
 * {@code LSPRenameProcessor}, and {@code LSPInplaceRenamer} call {@code references()} directly on
 * an {@code EditorEventManager} instance.
 *
 * <p>Unlike the other extractions so far, this one needed no injected callbacks:
 * {@code EditorEventManager} keeps ctrl+click/ctrl-hover's navigation-preview highlight
 * ({@code createCtrlRange}, {@code trySourceNavigationAndHover}) to itself, since that logic reads
 * and writes the ctrl-range global slot on {@code EditorEventManagerBase} (mouse/UI glue, not
 * navigation logic), and calls into {@link #definition} and {@link #gotoLocation} for the actual
 * requests.
 */
public final class NavigationFeature {

    private static final Logger LOG = Logger.getInstance(NavigationFeature.class);

    private final Editor editor;
    private final Project project;
    private final LanguageServerWrapper wrapper;
    private final TextDocumentIdentifier identifier;

    public NavigationFeature(Editor editor, Project project, LanguageServerWrapper wrapper,
            TextDocumentIdentifier identifier) {
        this.editor = editor;
        this.project = project;
        this.wrapper = wrapper;
        this.identifier = identifier;
    }

    /**
     * Returns the position of the definition given a position in the editor.
     *
     * @param position The position
     * @return The location of the definition
     */
    public Location definition(Position position) {
        DefinitionParams params = new DefinitionParams(identifier, position);
        CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> request =
                wrapper.getRequestManager().definition(params);

        Either<List<? extends Location>, List<? extends LocationLink>> definition =
                wrapper.getRequestExecutor().waitFor(request, DEFINITION);
        if (definition == null) {
            return null;
        }
        if (definition.isLeft() && !definition.getLeft().isEmpty()) {
            return definition.getLeft().get(0);
        } else if (definition.isRight() && !definition.getRight().isEmpty()) {
            var def = definition.getRight().get(0);
            return new Location(def.getTargetUri(), def.getTargetRange());
        }
        return null;
    }

    /**
     * Returns the references given the position of the word to search for.
     * May be called from any thread; opening and closing editors for reference locations is marshaled
     * to the event dispatch thread. When called from a background thread, the read lock must not be held.
     *
     * @param offset The offset in the editor
     * @return An array of PsiElement
     */
    public Pair<List<PsiElement>, List<VirtualFile>> references(int offset, boolean getOriginalElement,
            boolean close) {
        Position lspPos = DocumentUtils.offsetToLSPPos(editor, offset);
        TextDocumentIdentifier textDocumentIdentifier = new TextDocumentIdentifier(FileUtils.editorToURIString(editor));
        ReferenceParams params = new ReferenceParams(
                textDocumentIdentifier, lspPos, new ReferenceContext(getOriginalElement));
        params.setPosition(lspPos);
        params.setTextDocument(identifier);
        CompletableFuture<List<? extends Location>> request = wrapper.getRequestManager().references(params);
        List<? extends Location> res = wrapper.getRequestExecutor().waitFor(request, REFERENCES);
        if (res == null || res.isEmpty()) {
            // Empty, not null: LSPInplaceRenamer.collectRefs() streams the first list and passes the
            // second to LSPRenameProcessor.addEditors(), which calls List.addAll(...) on it — both
            // would NPE on a null list where callers that do check for null are unaffected.
            return new Pair<>(new ArrayList<>(), new ArrayList<>());
        }
        List<VirtualFile> openedEditors = new ArrayList<>();
        List<PsiElement> elements = new ArrayList<>();
        res.forEach(l -> {
            Position start = l.getRange().getStart();
            Position end = l.getRange().getEnd();
            String uri = FileUtils.sanitizeURI(l.getUri());
            VirtualFile file = FileUtils.virtualFileFromURI(uri);
            Editor curEditor = FileUtils.editorFromUri(uri, project);
            if (curEditor == null && file != null) {
                OpenFileDescriptor descriptor = new OpenFileDescriptor(
                        project, file, start.getLine(), start.getCharacter());
                curEditor = openEditor(descriptor);
                if (curEditor != null) {
                    openedEditors.add(file);
                }
            }
            if (curEditor == null) {
                LOG.warn("Error occurred in LSP references.");
                return;
            }
            Editor refEditor = curEditor;
            elements.add(computableReadAction(() -> {
                int logicalStart = DocumentUtils.lspPosToOffset(refEditor, start);
                int logicalEnd = DocumentUtils.lspPosToOffset(refEditor, end);
                String name = refEditor.getDocument().getText(new TextRange(logicalStart, logicalEnd));
                return new LSPPsiElement(name, project, logicalStart, logicalEnd,
                        PsiDocumentManager.getInstance(project).getPsiFile(refEditor.getDocument()));
            }));
        });
        if (close && !openedEditors.isEmpty()) {
            invokeAndWait(() -> writeAction(
                    () -> openedEditors.forEach(f -> FileEditorManager.getInstance(project).closeFile(f))));
            openedEditors.clear();
        }
        return new Pair<>(elements, openedEditors);
    }

    /**
     * Opens an editor for the given descriptor. Runs directly when called on the event dispatch thread;
     * otherwise the opening is marshaled to the event dispatch thread and this method blocks until done.
     */
    private Editor openEditor(OpenFileDescriptor descriptor) {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            return computableWriteAction(
                    () -> FileEditorManager.getInstance(project).openTextEditor(descriptor, false));
        }
        if (ApplicationManager.getApplication().isReadAccessAllowed()) {
            // Blocking on the event dispatch thread while holding the read lock would deadlock.
            LOG.warn("Cannot open an editor for " + descriptor.getFile() + " from inside a read action");
            return null;
        }
        Editor[] result = new Editor[1];
        invokeAndWait(() -> result[0] = computableWriteAction(
                () -> FileEditorManager.getInstance(project).openTextEditor(descriptor, false)));
        return result[0];
    }

    public void gotoLocation(Location loc) {
        VirtualFile file = null;
        try {
            file = VfsUtil.findFileByURL(new URL(loc.getUri()));
        } catch (MalformedURLException e1) {
            LOG.warn("Syntax Exception occurred for uri: " + loc.getUri());
        }
        if (file != null) {
            OpenFileDescriptor descriptor = new OpenFileDescriptor(project, file);
            VirtualFile finalFile = file;
            writeAction(() -> {
                FileEditorManager.getInstance(project).openTextEditor(descriptor, true);
                Editor srcEditor = FileUtils.editorFromVirtualFile(finalFile, project);
                if (srcEditor != null) {
                    Position start = loc.getRange().getStart();
                    LogicalPosition logicalPos = DocumentUtils.getTabsAwarePosition(srcEditor, start);
                    if (logicalPos != null) {
                        srcEditor.getCaretModel().moveToLogicalPosition(logicalPos);
                        srcEditor.getScrollingModel().scrollTo(logicalPos, ScrollType.CENTER);
                    }
                }
            });
        } else {
            LOG.warn("Empty file for " + loc.getUri());
        }
    }
}
