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

package org.wso2.lsp4intellij.inspector;

import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.eclipse.lsp4j.MessageType;
import org.wso2.lsp4intellij.client.connection.StreamConnectionProvider;
import org.wso2.lsp4intellij.client.languageserver.serverdefinition.LanguageServerDefinition;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;
import org.wso2.lsp4intellij.logging.LspLogSource;
import org.wso2.lsp4intellij.logging.LspLogStore;
import org.wso2.lsp4intellij.services.LspServerManager;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the panel's server bookkeeping: which wrappers it keeps, when it forgets one, and that it
 * queues nothing while hidden. The consoles and tabs themselves are not inspected.
 */
public class LspInspectorPanelTest extends BasePlatformTestCase {

    private ToolWindow toolWindow;
    private LspLogStore store;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        toolWindow = mock(ToolWindow.class);
        store = LspLogStore.getInstance(getProject());
    }

    public void testEntriesAreNotQueuedWhileHidden() {
        when(toolWindow.isVisible()).thenReturn(false);
        LspInspectorPanel panel = createPanel();
        LanguageServerWrapper wrapper = new LanguageServerWrapper(new StubDefinition("hid"), getProject());

        store.append(wrapper, LspLogSource.LIFECYCLE, MessageType.Info, "starting", null);

        assertEquals(0, panel.pendingEntryCountForTest());
    }

    public void testEntriesAreQueuedWhileVisible() {
        when(toolWindow.isVisible()).thenReturn(true);
        LspInspectorPanel panel = createPanel();
        LanguageServerWrapper wrapper = new LanguageServerWrapper(new StubDefinition("vis"), getProject());

        store.append(wrapper, LspLogSource.LIFECYCLE, MessageType.Info, "starting", null);

        assertEquals(1, panel.pendingEntryCountForTest());
    }

    public void testQueueStopsAtItsLimitAndIsDroppedOnFlush() {
        when(toolWindow.isVisible()).thenReturn(true);
        LspInspectorPanel panel = createPanel();
        LanguageServerWrapper wrapper = new LanguageServerWrapper(new StubDefinition("cap"), getProject());

        for (int i = 0; i < LspInspectorPanel.MAX_PENDING_ENTRIES + 10; i++) {
            store.append(wrapper, LspLogSource.SERVER_LOG, MessageType.Log, "line " + i, null);
        }
        assertEquals(LspInspectorPanel.MAX_PENDING_ENTRIES, panel.pendingEntryCountForTest());

        panel.flush();

        assertEquals(0, panel.pendingEntryCountForTest());
    }

    public void testReplacedWrapperIsForgottenOnFlush() {
        when(toolWindow.isVisible()).thenReturn(true);
        LspInspectorPanel panel = createPanel();
        LanguageServerWrapper old = new LanguageServerWrapper(new StubDefinition("rep"), getProject());
        store.append(old, LspLogSource.LIFECYCLE, MessageType.Info, "old", null);
        panel.selectServer(old);

        LanguageServerWrapper replacement = new LanguageServerWrapper(new StubDefinition("rep"), getProject());
        store.append(replacement, LspLogSource.LIFECYCLE, MessageType.Info, "new", null);
        panel.flush();

        assertFalse(panel.knownWrappersForTest().contains(old));
    }

    public void testReplacedWrapperIsPrunedWhenShownAgain() {
        when(toolWindow.isVisible()).thenReturn(false);
        LspInspectorPanel panel = createPanel();
        LanguageServerWrapper old = new LanguageServerWrapper(new StubDefinition("prn"), getProject());
        panel.selectServer(old);

        // The old wrapper has no buffer and is not registered, as after its replacement while hidden.
        when(toolWindow.isVisible()).thenReturn(true);
        panel.updateFlushState();

        assertFalse(panel.knownWrappersForTest().contains(old));
    }

    public void testClearingALiveServerKeepsIt() {
        when(toolWindow.isVisible()).thenReturn(true);
        LspInspectorPanel panel = createPanel();
        StubDefinition definition = new StubDefinition("liv");
        LanguageServerWrapper live = LspServerManager.getInstance(getProject())
                .getOrCreateWrapper(definition.ext, definition, null);
        try {
            store.append(live, LspLogSource.LIFECYCLE, MessageType.Info, "starting", null);
            panel.selectServer(live);

            store.removeBuffer(live);
            panel.flush();

            assertTrue(panel.knownWrappersForTest().contains(live));
        } finally {
            // The project and its services are shared across light tests.
            LspServerManager.getInstance(getProject()).unregister(live, new String[]{definition.ext});
        }
    }

    private LspInspectorPanel createPanel() {
        LspInspectorPanel panel = new LspInspectorPanel(getProject(), toolWindow);
        Disposer.register(getTestRootDisposable(), panel);
        return panel;
    }

    /**
     * A definition that never connects to anything, matching the one in {@code LspServerManagerTest}.
     */
    private static class StubDefinition extends LanguageServerDefinition {

        StubDefinition(String ext) {
            this.ext = ext;
        }

        @Override
        public StreamConnectionProvider createConnectionProvider(String workingDir) {
            throw new UnsupportedOperationException("no server is started in these tests");
        }
    }
}
