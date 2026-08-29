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

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import org.eclipse.lsp4j.CompletionItem;

/**
 * The subset of {@code EditorEventManager}'s public completion methods that {@link CompletionFeature}
 * calls back into rather than calling on itself.
 * <p>
 * Before the feature-layer decomposition these were unqualified self-calls inside a single
 * {@code EditorEventManager}, so they dispatched virtually: an extension supplying a subclass via
 * {@code LSPExtensionManager.getExtendedEditorEventManagerFor} could override any of them and have
 * the override take effect on the internal completion path. {@link CompletionFeature} is
 * {@code final}, so a plain self-call inside it can never reach such an override. Routing these
 * three calls through the composing {@code EditorEventManager} — which implements this interface
 * with the same method signatures it already exposed — restores that dispatch.
 * <p>
 * This is deliberately an interface rather than a reference to {@code EditorEventManager}: the
 * feature depends only on the operations it needs to re-dispatch, not on the class composing it.
 */
public interface CompletionOverrides {

    LookupElement createLookupItem(CompletionItem item);

    LookupElementBuilder addCompletionInsertHandlers(CompletionItem item, LookupElementBuilder builder,
            String lookupString);

    void prepareAndRunSnippet(String insertText);
}
