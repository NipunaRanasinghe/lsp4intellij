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

import junit.framework.TestCase;

import java.util.Collections;

/**
 * Tests for {@link SignatureHelpFeature#characterTyped}'s trigger-character gate — the one piece
 * of this feature that is pure logic. A non-trigger character must be a no-op without touching the
 * editor or wrapper at all (both are null here); the triggering path itself
 * ({@code signatureHelp()}, which needs a real editor, wrapper, and running server) has no test
 * coverage — {@code LspServerIntegrationTest} does not reach it; it covers only open/didOpen,
 * edit/didChange, and close/didClose/shutdown.
 */
public class SignatureHelpFeatureTest extends TestCase {

    public void testNonTriggerCharacterIsANoOp() {
        SignatureHelpFeature feature = new SignatureHelpFeature(null, null, null,
                Collections.singletonList("("), hint -> fail("must not show a hint for a non-trigger character"));

        // Would NPE on editor.isDisposed() inside signatureHelp() if the gate let it through.
        feature.characterTyped('x');
    }
}
