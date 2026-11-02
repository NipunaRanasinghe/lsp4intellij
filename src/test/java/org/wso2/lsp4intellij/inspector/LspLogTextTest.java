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

import org.eclipse.lsp4j.MessageType;
import org.junit.Assert;
import org.junit.Test;
import org.wso2.lsp4intellij.logging.LspLogEntry;
import org.wso2.lsp4intellij.logging.LspLogSource;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Plain JUnit: {@link LspLogText} has no Swing or platform dependency.
 */
public class LspLogTextTest {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Test
    public void lineUsesAnArrowForTraceAndTheLevelOtherwise() {
        LspLogEntry out = entry(LspLogSource.TRACE_OUT, MessageType.Log, "textDocument/hover (1)", null);
        LspLogEntry log = entry(LspLogSource.SERVER_LOG, MessageType.Warning, "disk low", null);

        Assert.assertEquals("00:00:01 → textDocument/hover (1)", LspLogText.line(out, TIME, ZoneOffset.UTC));
        Assert.assertEquals("00:00:01 Warning disk low", LspLogText.line(log, TIME, ZoneOffset.UTC));
    }

    @Test
    public void detailIndentsEveryLine() {
        LspLogEntry entry = entry(LspLogSource.TRACE_IN, MessageType.Log, "x", "{\n  \"a\": 1\n}");

        Assert.assertEquals("    {\n      \"a\": 1\n    }", LspLogText.detail(entry));
        Assert.assertNull(LspLogText.detail(entry(LspLogSource.TRACE_IN, MessageType.Log, "x", null)));
    }

    @Test
    public void exportListsRowsThenOnlyTheEntriesTheFilterMatches() {
        List<ServerInfo.Row> properties = List.of(new ServerInfo.Row("Name", "bal"));
        List<ServerInfo.CapabilityRow> capabilities =
                List.of(new ServerInfo.CapabilityRow("Editing", "Hover", "textDocument/hover", true));
        List<LspLogEntry> entries = List.of(
                entry(LspLogSource.LIFECYCLE, MessageType.Info, "Started", null),
                entry(LspLogSource.SERVER_LOG, MessageType.Error, "boom", "trace"));

        String text = LspLogText.export(properties, capabilities, entries,
                new LspInspectorFilter(MessageType.Error, null), ZoneOffset.UTC);

        Assert.assertEquals("""
                Name: bal

                Hover (textDocument/hover): Yes

                1970-01-01 00:00:01.000 Error boom
                    trace
                """, text);
    }

    private static LspLogEntry entry(LspLogSource source, MessageType level, String summary, String detail) {
        return new LspLogEntry(1000, "bal", "bal", source, level, summary, detail);
    }
}
