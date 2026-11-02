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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.wso2.lsp4intellij.Lsp4IntellijBundle;
import org.wso2.lsp4intellij.logging.LspLogEntry;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders log entries as text, for the Log tab's console and for the exported log file.
 */
final class LspLogText {

    private static final String DETAIL_INDENT = "    ";

    private LspLogText() {
    }

    /**
     * Returns the entry's one-line form: time, a direction arrow for trace entries or the level
     * otherwise, and the summary. No trailing newline.
     */
    @NotNull
    static String line(@NotNull LspLogEntry entry, @NotNull DateTimeFormatter time, @NotNull ZoneId zone) {
        String marker = switch (entry.source()) {
            case TRACE_OUT -> "→";
            case TRACE_IN -> "←";
            default -> entry.level().name();
        };
        return time.format(Instant.ofEpochMilli(entry.timestampMillis()).atZone(zone)) + " " + marker + " "
                + entry.summary();
    }

    /**
     * Returns the entry's detail with every line indented, or null if it has none. No trailing
     * newline.
     */
    @Nullable
    static String detail(@NotNull LspLogEntry entry) {
        return entry.detail() == null ? null : DETAIL_INDENT + entry.detail().replace("\n", "\n" + DETAIL_INDENT);
    }

    /**
     * Returns the text of an exported log: the server's properties and capabilities, then every
     * entry {@code filter} matches, oldest first.
     */
    @NotNull
    static String export(@NotNull List<ServerInfo.Row> properties, @NotNull List<ServerInfo.CapabilityRow> capabilities,
            @NotNull List<LspLogEntry> entries, @NotNull LspInspectorFilter filter, @NotNull ZoneId zone) {
        DateTimeFormatter time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
        StringBuilder text = new StringBuilder();
        appendRows(text, properties);
        if (!capabilities.isEmpty()) {
            text.append('\n');
            String yes = Lsp4IntellijBundle.message("inspector.info.yes");
            String no = Lsp4IntellijBundle.message("inspector.info.no");
            for (ServerInfo.CapabilityRow row : capabilities) {
                text.append(row.label()).append(" (").append(row.method()).append("): ")
                        .append(row.supported() ? yes : no).append('\n');
            }
        }
        text.append('\n');
        for (LspLogEntry entry : entries) {
            if (!filter.matches(entry)) {
                continue;
            }
            text.append(line(entry, time, zone)).append('\n');
            String detail = detail(entry);
            if (detail != null) {
                text.append(detail).append('\n');
            }
        }
        return text.toString();
    }

    private static void appendRows(@NotNull StringBuilder text, @NotNull List<ServerInfo.Row> rows) {
        for (ServerInfo.Row row : rows) {
            text.append(row.name()).append(": ").append(row.value()).append('\n');
        }
    }
}
