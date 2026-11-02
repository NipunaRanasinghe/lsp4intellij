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
package org.wso2.lsp4intellij.logging;

import com.intellij.openapi.project.Project;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.eclipse.lsp4j.MessageType;
import org.wso2.lsp4intellij.client.connection.StreamConnectionProvider;
import org.wso2.lsp4intellij.client.languageserver.serverdefinition.LanguageServerDefinition;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;
import org.wso2.lsp4intellij.services.LspServerManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link LspLogStore}'s buffering, eviction and disposal contract against a light test
 * project. Each test builds its own store rather than fetching the project's service, for the same
 * reason {@link org.wso2.lsp4intellij.services.LspServerManagerTest} does: {@code BasePlatformTestCase}
 * reuses one light project across the methods of a class, so a test that disposes the shared service
 * would leave {@code disposed} set for every test after it.
 */
public class LspLogStoreTest extends BasePlatformTestCase {

    private LspLogStore store;
    private LanguageServerWrapper wrapperA;
    private LanguageServerWrapper wrapperB;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        store = new LspLogStore(getProject());
        wrapperA = new LanguageServerWrapper(new StubDefinition("lsa"), getProject());
        wrapperB = new LanguageServerWrapper(new StubDefinition("lsb"), getProject());
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            store.dispose();
        } finally {
            super.tearDown();
        }
    }

    public void testAppendEvictsTheOldestEntryOnceCapacityIsExceeded() {
        for (int i = 0; i < LspLogStore.CAPACITY; i++) {
            store.append(wrapperA, LspLogSource.LIFECYCLE, MessageType.Info, "entry " + i, null);
        }
        List<LspLogEntry> before = store.entries(wrapperA);
        assertEquals(LspLogStore.CAPACITY, before.size());
        assertEquals("entry 0", before.get(0).summary());

        store.append(wrapperA, LspLogSource.LIFECYCLE, MessageType.Info, "overflow", null);

        List<LspLogEntry> after = store.entries(wrapperA);
        assertEquals("capacity must not grow past the bound", LspLogStore.CAPACITY, after.size());
        assertEquals("the oldest entry must be the one evicted", "entry 1", after.get(0).summary());
        assertEquals("overflow", after.get(after.size() - 1).summary());
    }

    public void testAppendEvictsTheOldestEntriesOnceTheCharacterBoundIsExceeded() {
        String detail = "x".repeat(LspLogStore.MAX_CHARS / 3);
        store.append(wrapperA, LspLogSource.TRACE_OUT, MessageType.Log, "first", detail);
        store.append(wrapperA, LspLogSource.TRACE_OUT, MessageType.Log, "second", detail);
        assertEquals("first", store.entries(wrapperA).get(0).summary());

        store.append(wrapperA, LspLogSource.TRACE_OUT, MessageType.Log, "third", detail);

        List<LspLogEntry> entries = store.entries(wrapperA);
        assertEquals(2, entries.size());
        assertEquals("second", entries.get(0).summary());
        assertEquals("third", entries.get(1).summary());
    }

    public void testAppendKeepsAnEntryLargerThanTheCharacterBound() {
        store.append(wrapperA, LspLogSource.TRACE_OUT, MessageType.Log, "small", null);
        store.append(wrapperA, LspLogSource.TRACE_OUT, MessageType.Log, "huge",
                "x".repeat(LspLogStore.MAX_CHARS + 1));

        List<LspLogEntry> entries = store.entries(wrapperA);
        assertEquals(1, entries.size());
        assertEquals("huge", entries.get(0).summary());
    }

    public void testANewWrapperForTheSameServerReplacesTheOldBuffer() {
        LanguageServerWrapper replacement = new LanguageServerWrapper(new StubDefinition("lsa"), getProject());
        store.append(wrapperA, LspLogSource.LIFECYCLE, MessageType.Info, "old", null);
        store.append(wrapperB, LspLogSource.LIFECYCLE, MessageType.Info, "other server", null);

        store.append(replacement, LspLogSource.LIFECYCLE, MessageType.Info, "new", null);

        assertTrue("the replaced wrapper's buffer must be dropped", store.entries(wrapperA).isEmpty());
        assertEquals(1, store.entries(wrapperB).size());
        assertEquals("new", store.entries(replacement).get(0).summary());
    }

    public void testANewWrapperForTheSameServerKeepsARegisteredWrappersBuffer() {
        StubDefinition definition = new StubDefinition("lsr");
        LanguageServerWrapper live = LspServerManager.getInstance(getProject())
                .getOrCreateWrapper(definition.ext, definition, null);
        try {
            store.append(live, LspLogSource.LIFECYCLE, MessageType.Info, "live", null);

            LanguageServerWrapper other = new LanguageServerWrapper(new StubDefinition("lsr"), getProject());
            store.append(other, LspLogSource.LIFECYCLE, MessageType.Info, "other", null);

            assertEquals("a registered wrapper's buffer must be kept", 1, store.entries(live).size());
            assertEquals(1, store.entries(other).size());
        } finally {
            // The project and its services are shared across light tests.
            LspServerManager.getInstance(getProject()).unregister(live, new String[]{definition.ext});
        }
    }

    public void testEntriesAreIsolatedBetweenWrappers() {
        store.append(wrapperA, LspLogSource.LIFECYCLE, MessageType.Info, "for a", null);
        store.append(wrapperB, LspLogSource.LIFECYCLE, MessageType.Info, "for b", null);

        assertEquals(1, store.entries(wrapperA).size());
        assertEquals("for a", store.entries(wrapperA).get(0).summary());
        assertEquals(1, store.entries(wrapperB).size());
        assertEquals("for b", store.entries(wrapperB).get(0).summary());
    }

    public void testAppendPublishesToListeners() {
        AtomicReference<LspLogEntry> received = new AtomicReference<>();
        getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(LspLogListener.TOPIC,
                new LspLogListener() {
                    @Override
                    public void entryAdded(LanguageServerWrapper wrapper, LspLogEntry entry) {
                        received.set(entry);
                    }

                    @Override
                    public void serverCleared(LanguageServerWrapper wrapper) {
                        // Not under test here.
                    }
                });

        store.append(wrapperA, LspLogSource.SERVER_LOG, MessageType.Warning, "disk low", null);

        assertNotNull(received.get());
        assertEquals("disk low", received.get().summary());
        assertEquals(MessageType.Warning, received.get().level());
        assertEquals("lsa", received.get().serverId());
    }

    public void testRemoveBufferDropsEntriesAndNotifiesListeners() {
        store.append(wrapperA, LspLogSource.LIFECYCLE, MessageType.Info, "starting", null);
        AtomicReference<LanguageServerWrapper> cleared = new AtomicReference<>();
        getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(LspLogListener.TOPIC,
                new LspLogListener() {
                    @Override
                    public void entryAdded(LanguageServerWrapper wrapper, LspLogEntry entry) {
                        // Not under test here.
                    }

                    @Override
                    public void serverCleared(LanguageServerWrapper wrapper) {
                        cleared.set(wrapper);
                    }
                });

        store.removeBuffer(wrapperA);

        assertTrue(store.entries(wrapperA).isEmpty());
        assertSame(wrapperA, cleared.get());
    }

    public void testDisposeClearsEveryBufferAndRejectsFurtherAppends() {
        store.append(wrapperA, LspLogSource.LIFECYCLE, MessageType.Info, "starting", null);
        store.append(wrapperB, LspLogSource.LIFECYCLE, MessageType.Info, "starting", null);

        store.dispose();

        assertTrue(store.entries(wrapperA).isEmpty());
        assertTrue(store.entries(wrapperB).isEmpty());
        store.append(wrapperA, LspLogSource.LIFECYCLE, MessageType.Info, "after dispose", null);
        assertTrue("a disposed store must not record new entries either", store.entries(wrapperA).isEmpty());
    }

    public void testGetInstanceIfCreatedReturnsNullForADisposedProject() {
        Project disposedProject = mock(Project.class);
        when(disposedProject.isDisposed()).thenReturn(true);

        assertNull(LspLogStore.getInstanceIfCreated(disposedProject));
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
