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
package org.wso2.lsp4intellij.client;

import com.intellij.testFramework.LoggedErrorProcessor;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.wso2.lsp4intellij.client.connection.StreamConnectionProvider;
import org.wso2.lsp4intellij.client.languageserver.serverdefinition.LanguageServerDefinition;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;
import org.wso2.lsp4intellij.logging.LspLogEntry;
import org.wso2.lsp4intellij.logging.LspLogSource;
import org.wso2.lsp4intellij.logging.LspLogStore;

import java.util.List;

/**
 * Tests that {@link DefaultLanguageClient#logMessage} records one {@link LspLogEntry} per call in
 * addition to the {@code Logger} call it already made, for every {@link MessageType}. {@code Error} is
 * routed through {@link LoggedErrorProcessor#executeAndReturnLoggedError} because
 * {@code Logger.error(String)} throws in test mode unless intercepted; that call succeeding at all is
 * itself proof the existing {@code LOG.error} call still happened.
 *
 * <p>{@link LspLogStore#getInstance(com.intellij.openapi.project.Project)} is called once in
 * {@link #setUp()} to force this project's store into existence, matching what
 * {@code LanguageServerWrapper.start()} does on the {@code STARTING} transition before any message can
 * arrive: {@code DefaultLanguageClient} itself only ever reads the store with the tolerant accessor, so
 * without this a test driving {@code logMessage} directly (skipping {@code start()}) would see nothing
 * recorded.
 */
public class DefaultLanguageClientTest extends BasePlatformTestCase {

    private LanguageServerWrapper wrapper;
    private DefaultLanguageClient client;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        wrapper = new LanguageServerWrapper(new StubDefinition("dlc"), getProject());
        client = new DefaultLanguageClient(new ServerWrapperBaseClientContext(wrapper));
        LspLogStore.getInstance(getProject());
    }

    public void testLogMessageAtWarningLevelAppendsAnEntry() {
        client.logMessage(new MessageParams(MessageType.Warning, "disk almost full"));

        LspLogEntry entry = onlyEntry();
        assertEquals(MessageType.Warning, entry.level());
        assertEquals(LspLogSource.SERVER_LOG, entry.source());
        assertEquals("disk almost full", entry.summary());
    }

    public void testLogMessageAtInfoLevelAppendsAnEntry() {
        client.logMessage(new MessageParams(MessageType.Info, "connected"));

        assertEquals(MessageType.Info, onlyEntry().level());
    }

    public void testLogMessageAtLogLevelAppendsAnEntry() {
        client.logMessage(new MessageParams(MessageType.Log, "starting up"));

        assertEquals(MessageType.Log, onlyEntry().level());
    }

    public void testLogMessageAtErrorLevelAppendsAnEntryAndStillLogsThroughLogger() {
        Throwable logged = LoggedErrorProcessor.executeAndReturnLoggedError(
                () -> client.logMessage(new MessageParams(MessageType.Error, "server exploded")));

        assertEquals("server exploded", logged.getMessage());
        LspLogEntry entry = onlyEntry();
        assertEquals(MessageType.Error, entry.level());
        assertEquals("server exploded", entry.summary());
    }

    private LspLogEntry onlyEntry() {
        List<LspLogEntry> entries = LspLogStore.getInstanceIfCreated(getProject()).entries(wrapper);
        assertEquals(1, entries.size());
        return entries.get(0);
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
