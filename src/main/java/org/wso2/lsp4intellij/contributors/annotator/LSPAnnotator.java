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
package org.wso2.lsp4intellij.contributors.annotator;

import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.lang.annotation.AnnotationBuilder;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DiagnosticTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.wso2.lsp4intellij.IntellijLanguageClient;
import org.wso2.lsp4intellij.client.languageserver.ServerStatus;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;
import org.wso2.lsp4intellij.editor.EditorEventManager;
import org.wso2.lsp4intellij.editor.EditorEventManagerBase;
import org.wso2.lsp4intellij.features.LspAnnotation;
import org.wso2.lsp4intellij.features.SilentAnnotation;
import org.wso2.lsp4intellij.utils.DocumentUtils;
import org.wso2.lsp4intellij.utils.FileUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders LSP diagnostics, and the code-action quick fixes later attached to them, as IntelliJ
 * annotations.
 *
 * <p>Per the {@link ExternalAnnotator} threading contract, {@code collectInformation} and
 * {@code apply} are called within a read action and should only gather immutable inputs / paint a
 * precomputed result, respectively; {@code doAnnotate} is called outside a read action and does the
 * actual work. All three run on the daemon's own background annotator thread, not the EDT — even
 * {@code apply}, which takes its read action on that same thread rather than switching to the EDT.
 * This class used to violate the contract: {@code collectInformation}/{@code doAnnotate} only ran
 * validation checks and discarded their results, and {@code apply} redid those checks and all the
 * real diagnostic/annotation logic itself. Now {@code doAnnotate} looks up the current diagnostics
 * (or the cached replay data — see below) and converts either into plain {@link LspAnnotation}
 * records; {@code apply} only turns those records into {@link AnnotationBuilder} calls.
 *
 * <p>Code actions arrive asynchronously, after the diagnostic annotations they attach a quick fix to
 * are already rendered — {@code CodeActionFeature.requestAndShowCodeActions()} mutates the matching
 * cached {@link LspAnnotation} in place and forces a new annotation pass, which this class then
 * replays (rebuilding a fresh {@link AnnotationBuilder} from the mutated cache) instead of
 * reconverting diagnostics again. {@link LspAnnotation} exists to make that possible without the
 * deprecated {@code com.intellij.lang.annotation.Annotation} type or the
 * {@code (SmartList<Annotation>) holder} cast the old code used to retrieve one back out of a holder.
 */
public class LSPAnnotator extends ExternalAnnotator<LSPAnnotator.AnnotationSource, LSPAnnotator.AnnotationResult> {

    private static final Logger LOG = Logger.getInstance(LSPAnnotator.class);
    private static final Map<DiagnosticSeverity, HighlightSeverity> annotationsMap = new HashMap<>();

    static {
        annotationsMap.put(DiagnosticSeverity.Error, HighlightSeverity.ERROR);
        annotationsMap.put(DiagnosticSeverity.Warning, HighlightSeverity.WARNING);

        // seem flipped, but just different semantics lsp<->intellij. Hint is rendered without any squiggle
        annotationsMap.put(DiagnosticSeverity.Information, HighlightSeverity.WEAK_WARNING);
        annotationsMap.put(DiagnosticSeverity.Hint, HighlightSeverity.INFORMATION);

        // As per the LSP spec, it’s recommended for the client to use error severity if the severity is not defined.
        annotationsMap.put(null, HighlightSeverity.ERROR);
    }

    @Nullable
    @Override
    public AnnotationSource collectInformation(@NotNull PsiFile file, @NotNull Editor editor, boolean hasErrors) {
        try {
            VirtualFile virtualFile = file.getVirtualFile();

            // If the file is not supported, we skips the annotation by returning null.
            if (!FileUtils.isFileSupported(virtualFile) || !IntellijLanguageClient.isExtensionSupported(virtualFile)) {
                return null;
            }
            if (EditorEventManagerBase.forEditor(editor) == null) {
                return null;
            }

            return new AnnotationSource(virtualFile, file.getProject());
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    @Override
    public AnnotationResult doAnnotate(AnnotationSource source) {
        LanguageServerWrapper languageServerWrapper =
                LanguageServerWrapper.forVirtualFile(source.virtualFile, source.project);
        if (languageServerWrapper == null || languageServerWrapper.getStatus() != ServerStatus.INITIALIZED) {
            return null;
        }
        if (!FileUtils.isFileSupported(source.virtualFile)
                || !IntellijLanguageClient.isExtensionSupported(source.virtualFile)) {
            return null;
        }

        String uri = FileUtils.vfsToUri(source.virtualFile);
        // TODO annotations are applied to a file / document not to an editor.
        // so store them by file and not by editor..
        EditorEventManager eventManager = EditorEventManagerBase.forUri(uri);
        if (eventManager == null) {
            return null;
        }

        if (eventManager.isDiagnosticSyncRequired()) {
            List<LspAnnotation> annotations = null;
            try {
                annotations = computeAnnotations(eventManager);
            } catch (ConcurrentModificationException e) {
                // Todo - Add proper fix to handle concurrent modifications gracefully.
                LOG.warn("Error occurred when computing LSP diagnostic annotations due to concurrent "
                        + "modifications.", e);
            } catch (Throwable t) {
                LOG.warn("Error occurred when computing LSP diagnostic annotations.", t);
            }
            if (annotations != null) {
                eventManager.setAnnotations(annotations);
                eventManager.markAnnotated();
            }
            // Requesting code actions doesn't need the annotation holder, so it can fire from here
            // instead of waiting for apply(); this is the same fire-and-forget async request as before.
            eventManager.requestAndShowCodeActions();
            return annotations == null ? null : new AnnotationResult(annotations, Collections.emptyList());
        } else {
            eventManager.triggerIntentionActions();
            return new AnnotationResult(eventManager.getAnnotations(), eventManager.getSilentAnnotations());
        }
    }

    @Override
    public void apply(@NotNull PsiFile file, @Nullable AnnotationResult annotationResult,
            @NotNull AnnotationHolder holder) {
        if (annotationResult == null) {
            return;
        }
        try {
            annotationResult.silentAnnotations.forEach(annotation -> {
                AnnotationBuilder builder = holder.newSilentAnnotation(annotation.severity());
                builder.range(annotation.range()).withFix(annotation.fix()).create();
            });
            annotationResult.annotations.forEach(annotation -> {
                AnnotationBuilder builder = holder.newAnnotation(annotation.getSeverity(), annotation.getMessage());
                if (annotation.getHighlightType() != null) {
                    builder = builder.highlightType(annotation.getHighlightType());
                }
                List<LspAnnotation.QuickFix> quickFixes = annotation.getQuickFixes();
                if (quickFixes.isEmpty()) {
                    builder.range(new TextRange(annotation.getStartOffset(), annotation.getEndOffset())).create();
                    return;
                }
                boolean firstFix = true;
                for (LspAnnotation.QuickFix quickFix : quickFixes) {
                    if (firstFix) {
                        builder = builder.range(quickFix.getRange());
                        firstFix = false;
                    }
                    builder = builder.withFix(quickFix.getFix());
                }
                builder.create();
            });
        } catch (ConcurrentModificationException e) {
            // Todo - Add proper fix to handle concurrent modifications gracefully.
            LOG.warn("Error occurred when rendering LSP diagnostics due to concurrent modifications.", e);
        } catch (Throwable t) {
            LOG.warn("Error occurred when rendering LSP diagnostics.", t);
        }
    }

    private List<LspAnnotation> computeAnnotations(EditorEventManager eventManager) {
        final List<Diagnostic> diagnostics = eventManager.getDiagnostics();
        final Editor editor = eventManager.editor;

        List<LspAnnotation> annotations = new ArrayList<>();
        diagnostics.forEach(d -> {
            LspAnnotation annotation = createAnnotation(editor, d);
            if (annotation != null) {
                if (d.getTags() != null && d.getTags().contains(DiagnosticTag.Deprecated)) {
                    annotation.setHighlightType(ProblemHighlightType.LIKE_DEPRECATED);
                }
                annotations.add(annotation);
            }
        });
        return annotations;
    }

    @Nullable
    protected LspAnnotation createAnnotation(Editor editor, Diagnostic diagnostic) {
        final int start = DocumentUtils.lspPosToOffset(editor, diagnostic.getRange().getStart());
        final int end = DocumentUtils.lspPosToOffset(editor, diagnostic.getRange().getEnd());
        if (start > end) {
            return null;
        }
        final TextRange range = new TextRange(start, end);

        HighlightSeverity severity = annotationsMap.getOrDefault(diagnostic.getSeverity(), HighlightSeverity.ERROR);
        String message = diagnostic.getMessage() != null ? diagnostic.getMessage() : "";

        return new LspAnnotation(severity, message, range);
    }

    /**
     * What {@link #collectInformation} hands to {@link #doAnnotate} — just enough immutable data to
     * redo the server/URI lookup on the background thread; {@code doAnnotate} must not touch PSI.
     */
    static final class AnnotationSource {
        private final VirtualFile virtualFile;
        private final Project project;

        AnnotationSource(VirtualFile virtualFile, Project project) {
            this.virtualFile = virtualFile;
            this.project = project;
        }
    }

    /**
     * What {@link #doAnnotate} hands to {@link #apply}: the diagnostic annotations and the silent
     * (intention-action-only) annotations to paint, precomputed outside the read action {@code apply}
     * takes.
     */
    static final class AnnotationResult {
        private final List<LspAnnotation> annotations;
        private final List<SilentAnnotation> silentAnnotations;

        AnnotationResult(List<LspAnnotation> annotations, List<SilentAnnotation> silentAnnotations) {
            this.annotations = annotations;
            this.silentAnnotations = silentAnnotations;
        }
    }
}
