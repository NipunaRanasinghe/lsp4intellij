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
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tests for {@link DiagnosticsFeature}'s storage and sync-flag contract, run against a light test
 * editor. No language server is involved: {@code publish} only stores the given diagnostics and
 * forces a DaemonCodeAnalyzer restart, which is safe to trigger against the fixture's project.
 */
public class DiagnosticsFeatureTest extends BasePlatformTestCase {

    private DiagnosticsFeature feature;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.configureByText("Test.txt", "hello world");
        Editor editor = myFixture.getEditor();
        feature = new DiagnosticsFeature(editor, getProject());
    }

    public void testInitiallySyncRequiredWithNoDiagnostics() {
        assertTrue(feature.isSyncRequired());
        assertTrue(feature.diagnostics().isEmpty());
    }

    public void testDiagnosticsReturnsLiveContractAndClearsSyncFlag() {
        List<Diagnostic> published = Collections.singletonList(diagnosticAt(0, 5));
        feature.publish(published);

        assertTrue(feature.isSyncRequired());
        List<Diagnostic> read = feature.diagnostics();
        assertEquals(1, read.size());
        assertFalse(feature.isSyncRequired());
    }

    public void testPublishingEmptyOverEmptyIsANoOp() {
        feature.diagnostics(); // clears the initial sync-required flag
        assertFalse(feature.isSyncRequired());

        feature.publish(new ArrayList<>());

        assertFalse(feature.isSyncRequired());
    }

    public void testPublishReplacesPreviousDiagnostics() {
        feature.publish(Collections.singletonList(diagnosticAt(0, 5)));
        feature.publish(Collections.singletonList(diagnosticAt(6, 11)));

        List<Diagnostic> current = feature.diagnostics();
        assertEquals(1, current.size());
        assertEquals(6, current.get(0).getRange().getStart().getCharacter());
    }

    public void testWithDiagnosticsSeesCurrentContent() {
        feature.publish(Collections.singletonList(diagnosticAt(0, 5)));

        List<Diagnostic> seen = new ArrayList<>();
        feature.withDiagnostics(seen::addAll);

        assertEquals(1, seen.size());
    }

    private static Diagnostic diagnosticAt(int startChar, int endChar) {
        Diagnostic diagnostic = new Diagnostic();
        diagnostic.setRange(new Range(new Position(0, startChar), new Position(0, endChar)));
        diagnostic.setMessage("test diagnostic");
        return diagnostic;
    }
}
