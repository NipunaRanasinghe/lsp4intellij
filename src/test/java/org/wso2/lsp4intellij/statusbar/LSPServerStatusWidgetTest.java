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
package org.wso2.lsp4intellij.statusbar;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.wso2.lsp4intellij.client.connection.StreamConnectionProvider;
import org.wso2.lsp4intellij.client.languageserver.ServerStatus;
import org.wso2.lsp4intellij.client.languageserver.serverdefinition.LanguageServerDefinition;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;

import java.util.List;

/**
 * Covers the text the widget shows in its tooltip and popup title. A platform test because the text
 * comes from the message bundle.
 */
public class LSPServerStatusWidgetTest extends BasePlatformTestCase {

    public void testDetailsShowOnlyTheStatusWhenNothingIsWaitingOrCrashed() {
        assertEquals("Initialized", LSPServerStatusWidget.details(ServerStatus.INITIALIZED, 0, 0));
    }

    public void testDetailsAddWaitingRequestsAndCrashes() {
        assertEquals("Initialized, 1 request waiting",
                LSPServerStatusWidget.details(ServerStatus.INITIALIZED, 1, 0));
        assertEquals("Starting, 2 requests waiting, 1 recent crash",
                LSPServerStatusWidget.details(ServerStatus.STARTING, 2, 1));
        assertEquals("Stopped, 3 recent crashes", LSPServerStatusWidget.details(ServerStatus.STOPPED, 0, 3));
    }

    public void testTooltipListsEachServerSortedByName() {
        LanguageServerWrapper toml = new LanguageServerWrapper(new StubDefinition("toml"), getProject());
        LanguageServerWrapper bal = new LanguageServerWrapper(new StubDefinition("bal"), getProject());

        assertEquals("<html>bal: Stopped<br>toml: Stopped<br>Click for actions</html>",
                LSPServerStatusWidget.tooltipText(List.of(toml, bal)));
    }

    public void testTooltipWithoutServers() {
        assertEquals("No language server is running", LSPServerStatusWidget.tooltipText(List.of()));
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
