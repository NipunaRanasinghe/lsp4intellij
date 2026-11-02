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

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.eclipse.lsp4j.CodeActionOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.List;

/**
 * Covers how {@link ServerInfo} reads capability fields. Extends {@link BasePlatformTestCase} only
 * because the rows' labels come from the message bundle.
 */
public class ServerInfoTest extends BasePlatformTestCase {

    public void testAdvertisedFollowsTheSpecificationEncodings() {
        assertFalse(ServerInfo.advertised(null));
        assertFalse(ServerInfo.advertised(false));
        assertTrue(ServerInfo.advertised(true));
        assertTrue(ServerInfo.advertised(Either.forLeft(true)));
        assertFalse(ServerInfo.advertised(Either.forLeft(false)));
        assertTrue("an options object means supported",
                ServerInfo.advertised(Either.forRight(new CodeActionOptions())));
        assertTrue(ServerInfo.advertised(new Object()));
    }

    public void testCapabilitiesListEveryRequestWithItsSupportAndGroup() {
        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setHoverProvider(true);
        capabilities.setRenameProvider(false);

        List<ServerInfo.CapabilityRow> rows = ServerInfo.capabilities(capabilities);

        assertEquals(17, rows.size());
        ServerInfo.CapabilityRow hover = row(rows, "textDocument/hover");
        assertTrue(hover.supported());
        assertEquals("Hover", hover.label());
        assertEquals("Editing", hover.group());
        assertFalse(row(rows, "textDocument/rename").supported());
        assertEquals("Navigation", row(rows, "textDocument/definition").group());
    }

    public void testCapabilitiesKeepEachGroupContiguous() {
        List<String> groups = ServerInfo.capabilities(new ServerCapabilities()).stream()
                .map(ServerInfo.CapabilityRow::group).toList();

        assertEquals("groups appear in display order",
                List.of("Editing", "Navigation", "Formatting"), groups.stream().distinct().toList());
        // A group must not reappear after another one starts.
        for (int i = 1; i < groups.size(); i++) {
            if (!groups.get(i).equals(groups.get(i - 1))) {
                assertFalse(groups.subList(0, i).contains(groups.get(i)));
            }
        }
    }

    public void testCapabilitiesAreEmptyBeforeInitialization() {
        assertTrue(ServerInfo.capabilities(null).isEmpty());
    }

    private static ServerInfo.CapabilityRow row(List<ServerInfo.CapabilityRow> rows, String method) {
        return rows.stream().filter(r -> r.method().equals(method)).findFirst().orElseThrow();
    }
}
