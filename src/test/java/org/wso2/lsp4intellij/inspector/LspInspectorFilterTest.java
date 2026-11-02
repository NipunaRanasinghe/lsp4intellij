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

/**
 * Unit tests for {@link LspInspectorFilter}. Plain JUnit, no IntelliJ Platform test fixture needed since
 * the class under test has no Swing or platform dependency.
 */
public class LspInspectorFilterTest {

    @Test
    public void testAllShowsEveryLevelAndSource() {
        Assert.assertTrue(LspInspectorFilter.ALL.matches(entry(LspLogSource.TRACE_IN, MessageType.Log)));
        Assert.assertTrue(LspInspectorFilter.ALL.matches(entry(LspLogSource.LIFECYCLE, MessageType.Error)));
    }

    @Test
    public void testLevelFilterShowsChosenLevelAndMoreSevereOnes() {
        LspInspectorFilter warningAndAbove = new LspInspectorFilter(MessageType.Warning, null);

        Assert.assertTrue("Error is more severe than the Warning threshold",
                warningAndAbove.matches(entry(LspLogSource.SERVER_LOG, MessageType.Error)));
        Assert.assertTrue("Warning itself must pass",
                warningAndAbove.matches(entry(LspLogSource.SERVER_LOG, MessageType.Warning)));
        Assert.assertFalse("Info is less severe than the Warning threshold",
                warningAndAbove.matches(entry(LspLogSource.SERVER_LOG, MessageType.Info)));
        Assert.assertFalse("Log is less severe than the Warning threshold",
                warningAndAbove.matches(entry(LspLogSource.SERVER_LOG, MessageType.Log)));
    }

    @Test
    public void testSourceFilterShowsOnlyTheChosenSource() {
        LspInspectorFilter traceOutOnly = new LspInspectorFilter(null, LspLogSource.TRACE_OUT);

        Assert.assertTrue(traceOutOnly.matches(entry(LspLogSource.TRACE_OUT, MessageType.Log)));
        Assert.assertFalse(traceOutOnly.matches(entry(LspLogSource.TRACE_IN, MessageType.Log)));
        Assert.assertFalse(traceOutOnly.matches(entry(LspLogSource.LIFECYCLE, MessageType.Error)));
    }

    @Test
    public void testLevelAndSourceFilterBothApply() {
        LspInspectorFilter errorLifecycleOnly = new LspInspectorFilter(MessageType.Error, LspLogSource.LIFECYCLE);

        Assert.assertTrue(errorLifecycleOnly.matches(entry(LspLogSource.LIFECYCLE, MessageType.Error)));
        Assert.assertFalse("wrong source",
                errorLifecycleOnly.matches(entry(LspLogSource.SERVER_LOG, MessageType.Error)));
        Assert.assertFalse("wrong level",
                errorLifecycleOnly.matches(entry(LspLogSource.LIFECYCLE, MessageType.Warning)));
    }

    private static LspLogEntry entry(LspLogSource source, MessageType level) {
        return new LspLogEntry(System.currentTimeMillis(), "ext", "label", source, level, "summary", null);
    }
}
