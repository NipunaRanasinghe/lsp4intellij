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

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.lang.annotation.AnnotationBuilder;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.util.TextRange;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A plain, mutable stand-in for the platform's deprecated {@code com.intellij.lang.annotation.Annotation},
 * used as an in-memory cache of what {@code LSPAnnotator} has rendered for the current diagnostics
 * snapshot for one editor.
 *
 * <p>{@link AnnotationBuilder} (the modern replacement) is a build-once, immutable API — once
 * {@code create()} is called there's no way to attach a quick fix to that annotation later. But code
 * actions arrive asynchronously, after the diagnostic annotations they attach to are already rendered,
 * so {@link CodeActionFeature#requestAndShowCodeActions()} needs to record a fix against an
 * already-rendered annotation and force a new annotation pass. This class is the mutable cache entry
 * that makes that possible without depending on the deprecated {@code Annotation} type or the
 * {@code (SmartList<Annotation>) holder} cast that used to be needed to retrieve one back out of an
 * {@code AnnotationHolder}. {@code LSPAnnotator} rebuilds a fresh {@link AnnotationBuilder} from these
 * fields on every annotation pass.
 *
 * <p>{@code registerFix} runs on the EDT (from {@code CodeActionFeature.showCodeActions()}, once an
 * async code-action response arrives), while {@code LSPAnnotator.apply()} iterates
 * {@link #getQuickFixes()} on the daemon's background annotator thread, for a replayed (not freshly
 * built) annotation — the two are genuinely concurrent, not just interleaved on one thread, since
 * {@code ExternalAnnotator.apply()} runs under a read action taken on that background thread, not on
 * the EDT. {@code quickFixes} is a {@link CopyOnWriteArrayList} so that race can't throw
 * {@code ConcurrentModificationException}: a reader iterates a stable snapshot, and a concurrent
 * {@code registerFix} is simply visible on the next pass.
 */
public final class LspAnnotation {

    private final HighlightSeverity severity;
    private final String message;
    private final TextRange range;
    private ProblemHighlightType highlightType;
    private final List<QuickFix> quickFixes = new CopyOnWriteArrayList<>();

    public LspAnnotation(HighlightSeverity severity, String message, TextRange range) {
        this.severity = severity;
        this.message = message;
        this.range = range;
    }

    public HighlightSeverity getSeverity() {
        return severity;
    }

    public String getMessage() {
        return message;
    }

    public int getStartOffset() {
        return range.getStartOffset();
    }

    public int getEndOffset() {
        return range.getEndOffset();
    }

    public ProblemHighlightType getHighlightType() {
        return highlightType;
    }

    public void setHighlightType(ProblemHighlightType highlightType) {
        this.highlightType = highlightType;
    }

    public List<QuickFix> getQuickFixes() {
        return quickFixes;
    }

    public void registerFix(IntentionAction fix, TextRange range) {
        quickFixes.add(new QuickFix(fix, range));
    }

    /**
     * Mirrors the deprecated {@code Annotation.QuickFixInfo}: a quick fix plus the range it applies to.
     */
    public static final class QuickFix {
        private final IntentionAction fix;
        private final TextRange range;

        QuickFix(IntentionAction fix, TextRange range) {
            this.fix = fix;
            this.range = range;
        }

        public IntentionAction getFix() {
            return fix;
        }

        public TextRange getRange() {
            return range;
        }
    }
}
