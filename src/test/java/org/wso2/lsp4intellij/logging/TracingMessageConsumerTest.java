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

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.jsonrpc.Endpoint;
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.eclipse.lsp4j.jsonrpc.json.StreamMessageConsumer;
import org.eclipse.lsp4j.jsonrpc.messages.Message;
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage;
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage;
import org.wso2.lsp4intellij.client.connection.StreamConnectionProvider;
import org.wso2.lsp4intellij.client.languageserver.serverdefinition.LanguageServerDefinition;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;
import org.wso2.lsp4intellij.requests.RequestStats;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests for {@link TracingMessageConsumer}: the {@code OFF}/{@code MESSAGES}/{@code VERBOSE}
 * behaviors, and direction detection against the two real lsp4j consumer types {@code MessageHandler}
 * composes it around. {@link LspLogStore#getInstance(com.intellij.openapi.project.Project)} is called
 * once in {@link #setUp()} to force this project's store into existence, matching what
 * {@code LanguageServerWrapper.start()} does on the {@code STARTING} transition before any message can
 * arrive; every assertion then reads it back with the same accessor a UI would use later.
 */
public class TracingMessageConsumerTest extends BasePlatformTestCase {

    private LanguageServerWrapper wrapper;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        wrapper = new LanguageServerWrapper(new StubDefinition("tmc"), getProject());
        LspLogStore.getInstance(getProject());
    }

    public void testOffLevelRecordsNothingButStillForwards() {
        RecordingStreamMessageConsumer delegate = new RecordingStreamMessageConsumer();
        TracingMessageConsumer consumer = new TracingMessageConsumer(delegate, wrapper, () -> LspTraceLevel.OFF,
                new HashMap<>());
        RequestMessage message = requestMessage("1", "textDocument/hover");

        consumer.consume(message);

        assertEquals(List.of(message), delegate.forwarded);
        assertTrue(LspLogStore.getInstance(getProject()).entries(wrapper).isEmpty());
    }

    public void testMessagesLevelRecordsMethodIdAndDirection() {
        RecordingStreamMessageConsumer delegate = new RecordingStreamMessageConsumer();
        TracingMessageConsumer consumer = new TracingMessageConsumer(delegate, wrapper, () -> LspTraceLevel.MESSAGES,
                new HashMap<>());
        RequestMessage message = requestMessage("7", "textDocument/hover");

        consumer.consume(message);

        assertEquals(List.of(message), delegate.forwarded);
        List<LspLogEntry> entries = LspLogStore.getInstance(getProject()).entries(wrapper);
        assertEquals(1, entries.size());
        LspLogEntry entry = entries.get(0);
        assertEquals(LspLogSource.TRACE_OUT, entry.source());
        assertTrue(entry.summary().contains("textDocument/hover"));
        assertTrue(entry.summary().contains("7"));
        assertNull("MESSAGES must not record the payload", entry.detail());
    }

    public void testVerboseLevelRecordsThePayload() {
        RecordingRemoteEndpoint delegate = new RecordingRemoteEndpoint();
        TracingMessageConsumer consumer = new TracingMessageConsumer(delegate, wrapper, () -> LspTraceLevel.VERBOSE,
                new HashMap<>());
        NotificationMessage message = notificationMessage("textDocument/didOpen");

        consumer.consume(message);

        assertEquals(List.of(message), delegate.forwarded);
        List<LspLogEntry> entries = LspLogStore.getInstance(getProject()).entries(wrapper);
        assertEquals(1, entries.size());
        assertNotNull("VERBOSE must record the payload", entries.get(0).detail());
        assertTrue(entries.get(0).detail().contains("textDocument/didOpen"));
    }

    public void testDirectionForStreamMessageConsumerIsTraceOut() {
        RecordingStreamMessageConsumer delegate = new RecordingStreamMessageConsumer();
        TracingMessageConsumer consumer = new TracingMessageConsumer(delegate, wrapper, () -> LspTraceLevel.MESSAGES,
                new HashMap<>());

        consumer.consume(notificationMessage("a"));

        assertEquals(LspLogSource.TRACE_OUT, LspLogStore.getInstance(getProject()).entries(wrapper).get(0).source());
    }

    public void testDirectionForRemoteEndpointIsTraceIn() {
        RecordingRemoteEndpoint delegate = new RecordingRemoteEndpoint();
        TracingMessageConsumer consumer = new TracingMessageConsumer(delegate, wrapper, () -> LspTraceLevel.MESSAGES,
                new HashMap<>());

        consumer.consume(notificationMessage("b"));

        assertEquals(LspLogSource.TRACE_IN, LspLogStore.getInstance(getProject()).entries(wrapper).get(0).source());
    }

    public void testResponseIsSummarizedWithTheMethodOfTheRequestItAnswers() {
        Map<String, TracingMessageConsumer.InFlightRequest> inFlight = new HashMap<>();
        TracingMessageConsumer out = new TracingMessageConsumer(new RecordingStreamMessageConsumer(), wrapper,
                () -> LspTraceLevel.MESSAGES, inFlight);
        TracingMessageConsumer in = new TracingMessageConsumer(new RecordingRemoteEndpoint(), wrapper,
                () -> LspTraceLevel.MESSAGES, inFlight);

        out.consume(requestMessage("3", "textDocument/hover"));
        in.consume(responseMessage("3", null));

        LspLogEntry response = LspLogStore.getInstance(getProject()).entries(wrapper).get(1);
        assertTrue(response.summary(), response.summary().matches("textDocument/hover \\(3\\) \\d+ ms"));
        assertEquals(MessageType.Log, response.level());
        assertTrue("an answered request must not stay tracked", inFlight.isEmpty());
    }

    public void testRequestIsTrackedWhileTracingIsOff() {
        Map<String, TracingMessageConsumer.InFlightRequest> inFlight = new HashMap<>();
        AtomicReference<LspTraceLevel> level = new AtomicReference<>(LspTraceLevel.OFF);
        TracingMessageConsumer out = new TracingMessageConsumer(new RecordingStreamMessageConsumer(), wrapper,
                level::get, inFlight);
        TracingMessageConsumer in = new TracingMessageConsumer(new RecordingRemoteEndpoint(), wrapper,
                level::get, inFlight);

        out.consume(requestMessage("4", "textDocument/definition"));
        level.set(LspTraceLevel.MESSAGES);
        in.consume(responseMessage("4", null));

        LspLogEntry response = LspLogStore.getInstance(getProject()).entries(wrapper).get(0);
        assertTrue(response.summary(), response.summary().startsWith("textDocument/definition (4) "));
    }

    public void testResponseTimesAreRecordedWhileTracingIsOff() {
        Map<String, TracingMessageConsumer.InFlightRequest> inFlight = new HashMap<>();
        TracingMessageConsumer out = new TracingMessageConsumer(new RecordingStreamMessageConsumer(), wrapper,
                () -> LspTraceLevel.OFF, inFlight);
        TracingMessageConsumer in = new TracingMessageConsumer(new RecordingRemoteEndpoint(), wrapper,
                () -> LspTraceLevel.OFF, inFlight);

        out.consume(requestMessage("8", "textDocument/rename"));
        TracingMessageConsumer.InFlightRequest pending = inFlight.values().iterator().next();
        in.consume(responseMessage("8", new ResponseError(-32603, "Internal error", null)));

        assertEquals("8", pending.id());
        assertEquals(LspLogSource.TRACE_OUT, pending.direction());
        RequestStats.Summary summary = wrapper.getRequestStats().summaries().get(0);
        assertEquals("textDocument/rename", summary.method());
        assertEquals(1, summary.count());
        assertEquals(1, summary.errors());
    }

    public void testErrorResponseIsRecordedAtErrorLevel() {
        TracingMessageConsumer in = new TracingMessageConsumer(new RecordingRemoteEndpoint(), wrapper,
                () -> LspTraceLevel.MESSAGES, new HashMap<>());

        in.consume(responseMessage("5", new ResponseError(-32601, "Unhandled method", null)));

        LspLogEntry response = LspLogStore.getInstance(getProject()).entries(wrapper).get(0);
        assertEquals(MessageType.Error, response.level());
        assertEquals("response (5) error -32601: Unhandled method", response.summary());
    }

    private static ResponseMessage responseMessage(String id, ResponseError error) {
        ResponseMessage message = new ResponseMessage();
        message.setId(id);
        if (error != null) {
            message.setError(error);
        }
        return message;
    }

    private static RequestMessage requestMessage(String id, String method) {
        RequestMessage message = new RequestMessage();
        message.setId(id);
        message.setMethod(method);
        return message;
    }

    private static NotificationMessage notificationMessage(String method) {
        NotificationMessage message = new NotificationMessage();
        message.setMethod(method);
        return message;
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

    /**
     * A real {@link StreamMessageConsumer} — the type {@code MessageHandler} wraps for the
     * client-to-server direction — with {@link #consume} overridden so the test does not depend on
     * lsp4j's own JSON serialization succeeding for a message built by hand.
     */
    private static final class RecordingStreamMessageConsumer extends StreamMessageConsumer {

        private final List<Message> forwarded = new ArrayList<>();

        RecordingStreamMessageConsumer() {
            super(new ByteArrayOutputStream(), new MessageJsonHandler(Collections.emptyMap()));
        }

        @Override
        public void consume(Message message) {
            forwarded.add(message);
        }
    }

    /**
     * A real {@link RemoteEndpoint} — the type {@code MessageHandler} wraps for the server-to-client
     * direction — with {@link #consume} overridden for the same reason as {@link RecordingStreamMessageConsumer}.
     */
    private static final class RecordingRemoteEndpoint extends RemoteEndpoint {

        private final List<Message> forwarded = new ArrayList<>();

        RecordingRemoteEndpoint() {
            super(m -> { }, new Endpoint() {
                @Override
                public CompletableFuture<?> request(String method, Object parameter) {
                    return null;
                }

                @Override
                public void notify(String method, Object parameter) {
                    // The endpoint is only a type marker for the direction check; nothing is sent.
                }
            });
        }

        @Override
        public void consume(Message message) {
            forwarded.add(message);
        }
    }
}
