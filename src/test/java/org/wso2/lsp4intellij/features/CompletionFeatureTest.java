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
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.Collections;
import java.util.List;

/**
 * Tests for {@link CompletionFeature#getCompletionPrefix(Editor, int)} — the one piece of this
 * feature that is pure document/string logic and needs neither a language server nor the wrapper
 * it would otherwise call into. The other methods (completion request, lookup item conversion,
 * snippet expansion) need a running server or IntelliJ's completion machinery and have no test
 * coverage — {@code LspServerIntegrationTest} does not reach them; it covers only open/didOpen,
 * edit/didChange, and close/didClose/shutdown.
 */
public class CompletionFeatureTest extends BasePlatformTestCase {

    public void testPrefixStopsAtACustomTriggerCharacter() {
        CompletionFeature feature = newFeature(Collections.singletonList("."));
        myFixture.configureByText("Test.txt", "foo.ba");

        assertEquals("ba", feature.getCompletionPrefix(myFixture.getEditor(), 6));
    }

    public void testPrefixFallsBackToWhitespaceWithNoTriggerCharacters() {
        CompletionFeature feature = newFeature(Collections.emptyList());
        myFixture.configureByText("Test.txt", "hello wor");

        assertEquals("wor", feature.getCompletionPrefix(myFixture.getEditor(), 9));
    }

    public void testPrefixIsTheWholeTextWhenNoDelimiterIsFound() {
        CompletionFeature feature = newFeature(Collections.emptyList());
        myFixture.configureByText("Test.txt", "abc");

        assertEquals("abc", feature.getCompletionPrefix(myFixture.getEditor(), 3));
    }

    private CompletionFeature newFeature(List<String> completionTriggers) {
        // editor/wrapper/identifier are null: getCompletionPrefix takes its own editor parameter
        // and never touches them, and the edit/command/signature-help callbacks and the override
        // hooks are never invoked.
        return new CompletionFeature(null, getProject(), null, null, completionTriggers,
                (version, edits, name, closeAfter, setCaret) -> false,
                commands -> { },
                () -> { },
                null);
    }
}
