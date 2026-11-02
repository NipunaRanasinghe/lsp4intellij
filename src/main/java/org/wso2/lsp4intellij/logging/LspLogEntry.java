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

import org.eclipse.lsp4j.MessageType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One recorded event for one language server: a log or show message, a lifecycle transition, or a
 * traced JSON-RPC message. Held in a bounded, per-server buffer by {@link LspLogStore} and published
 * on {@link LspLogListener#TOPIC} as it is appended.
 *
 * @param timestampMillis wall-clock time the entry was recorded, as returned by
 *         {@link System#currentTimeMillis()}
 * @param serverId the extension the owning server definition is registered under; identifies which
 *         server this entry belongs to within one project
 * @param serverLabel a human-readable label for the owning server definition
 * @param source where this entry came from
 * @param level the severity to display this entry at
 * @param summary a one-line description of the entry
 * @param detail the raw JSON payload of a traced message, present only when trace is
 *         {@link LspTraceLevel#VERBOSE}; {@code null} otherwise
 */
public record LspLogEntry(
        long timestampMillis,
        @NotNull String serverId,
        @NotNull String serverLabel,
        @NotNull LspLogSource source,
        @NotNull MessageType level,
        @NotNull String summary,
        @Nullable String detail) {
}
