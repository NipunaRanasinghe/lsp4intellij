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

import org.eclipse.lsp4j.TraceValue;

/**
 * How much of the JSON-RPC traffic between the client and a server is turned into
 * {@link LspLogEntry} records. Defaults to {@link #OFF} for every server; {@link TracingMessageConsumer}
 * reads this per message and does no work at all while it is {@link #OFF}.
 *
 * <p>Also mirrors the {@code $/setTrace} values the protocol itself defines in {@link TraceValue}, so a
 * server that implements {@code $/setTrace} can be told to match what this level records locally.
 */
public enum LspTraceLevel {

    /** No trace entries are recorded; {@code $/setTrace} is sent with {@link TraceValue#Off}. */
    OFF,

    /** Method, id and direction are recorded; {@code $/setTrace} is sent with {@link TraceValue#Messages}. */
    MESSAGES,

    /** The full payload is recorded as well; {@code $/setTrace} is sent with {@link TraceValue#Verbose}. */
    VERBOSE;

    /**
     * Returns the {@code $/setTrace} wire value for this level.
     */
    public String traceValue() {
        return switch (this) {
            case MESSAGES -> TraceValue.Messages;
            case VERBOSE -> TraceValue.Verbose;
            case OFF -> TraceValue.Off;
        };
    }
}
