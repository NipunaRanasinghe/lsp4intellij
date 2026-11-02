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

/**
 * Where one {@link LspLogEntry} came from.
 */
public enum LspLogSource {

    /** A {@code window/logMessage} notification from the server. */
    SERVER_LOG,

    /** A {@code window/showMessage} notification from the server. */
    SERVER_MESSAGE,

    /** A server status transition or crash, recorded by {@code LanguageServerWrapper}. */
    LIFECYCLE,

    /** A JSON-RPC message sent from the client to the server. */
    TRACE_OUT,

    /** A JSON-RPC message sent from the server to the client. */
    TRACE_IN
}
