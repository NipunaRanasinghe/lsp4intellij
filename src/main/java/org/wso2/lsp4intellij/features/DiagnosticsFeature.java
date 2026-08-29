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

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.eclipse.lsp4j.Diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.wso2.lsp4intellij.utils.ApplicationUtils.computableReadAction;

/**
 * Owns one editor's most recently published diagnostics and the "annotator needs to re-render"
 * flag, and the DaemonCodeAnalyzer restart that publishing (or an attached code-action fix) forces.
 *
 * <p>Extracted from {@code EditorEventManager} as the first slice of the feature-layer decomposition
 * (ARCHITECTURE.md section 2.4); {@code EditorEventManager} composes one instance per editor and
 * delegates its diagnostics-facing methods to it with the same signatures and behavior it had before.
 * The class is otherwise self-contained: no static lookups, no dependency on the wrapper or on
 * {@code EditorEventManager} itself.
 */
public final class DiagnosticsFeature {

    private static final Logger LOG = Logger.getInstance(DiagnosticsFeature.class);

    private final Editor editor;
    private final Project project;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private volatile boolean syncRequired = true;

    public DiagnosticsFeature(Editor editor, Project project) {
        this.editor = editor;
        this.project = project;
    }

    /**
     * Replaces the diagnostics for this editor and, unless both the old and new lists are empty,
     * marks a re-sync required and forces a full DaemonCodeAnalyzer pass so the annotator picks up
     * the change.
     */
    public void publish(List<Diagnostic> diagnostics) {
        if (editor.isDisposed() || (this.diagnostics.isEmpty() && diagnostics.isEmpty())) {
            return;
        }
        synchronized (this.diagnostics) {
            this.diagnostics.clear();
            this.diagnostics.addAll(diagnostics);
            syncRequired = true;
            restartDaemonCodeAnalyzer();
        }
    }

    /**
     * Returns the current diagnostics and clears the sync-required flag. Returns the same mutable
     * list instance {@link #publish} mutates, not a defensive copy — matching the original
     * {@code EditorEventManager.getDiagnostics()} contract, which callers such as {@code LSPAnnotator}
     * already tolerate a concurrent-modification exception from.
     */
    public synchronized List<Diagnostic> diagnostics() {
        syncRequired = false;
        return diagnostics;
    }

    public synchronized boolean isSyncRequired() {
        return syncRequired;
    }

    /**
     * Runs {@code action} with exclusive access to the live diagnostics list, e.g. to filter it by
     * range for a code-action request without a concurrent {@link #publish} interleaving. Locks on
     * the same monitor {@link #publish} does, matching the {@code synchronized (this.diagnostics)}
     * block this was extracted from.
     */
    public void withDiagnostics(Consumer<List<Diagnostic>> action) {
        synchronized (diagnostics) {
            action.accept(diagnostics);
        }
    }

    /**
     * Forces a full DaemonCodeAnalyzer pass for this editor's file, so the annotator re-runs and
     * picks up whatever changed. Public because {@code EditorEventManager}'s code-action handling
     * — not yet extracted into its own feature — also calls this after attaching a fix to an
     * existing annotation.
     */
    public void restartDaemonCodeAnalyzer() {
        computableReadAction(() -> {
            final PsiFile file = PsiDocumentManager.getInstance(project).getCachedPsiFile(editor.getDocument());
            if (file == null) {
                return null;
            }
            LOG.debug("Triggering force full DaemonCodeAnalyzer execution.");
            DaemonCodeAnalyzer.getInstance(project).restart(file);
            return null;
        });
    }
}
