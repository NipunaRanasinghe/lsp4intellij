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

import com.intellij.util.messages.Topic;
import org.jetbrains.annotations.NotNull;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;

/**
 * Notified as {@link LspLogStore} changes. Subscribers connect to {@link #TOPIC} on a project's
 * {@link com.intellij.util.messages.MessageBus}; there is no tool window registered by lsp4intellij
 * itself, so nothing has to be listening for a project's store to keep working.
 */
public interface LspLogListener {

    Topic<LspLogListener> TOPIC = Topic.create("lsp4intellij LSP log", LspLogListener.class);

    /**
     * An entry was appended to {@code wrapper}'s buffer.
     */
    void entryAdded(@NotNull LanguageServerWrapper wrapper, @NotNull LspLogEntry entry);

    /**
     * {@code wrapper}'s buffer was removed, either by an explicit clear or because a newer wrapper for
     * the same server started recording and replaced it.
     * Not fired when the project itself is disposing: every buffer is dropped then, but the message
     * bus a listener would be notified on is on its way out at the same time, so {@link LspLogStore}
     * clears its state directly instead of publishing one event per server.
     */
    void serverCleared(@NotNull LanguageServerWrapper wrapper);
}
