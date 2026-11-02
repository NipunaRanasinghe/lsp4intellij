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

import org.wso2.lsp4intellij.client.languageserver.ServerStatus;

import java.util.Collection;
import java.util.List;

/**
 * Picks the least healthy {@link ServerStatus} out of the servers running for a project, for
 * {@link LSPServerStatusWidget}'s aggregated icon. Holds no Swing or IntelliJ Platform state, so it is
 * unit-testable without a platform test fixture.
 */
public final class ServerStatusAggregator {

    /**
     * Worst to best. The icon reflects the first of these found among the given statuses.
     */
    private static final List<ServerStatus> WORST_TO_BEST = List.of(
            ServerStatus.STOPPED, ServerStatus.STOPPING, ServerStatus.STARTING,
            ServerStatus.STARTED, ServerStatus.INITIALIZED);

    private ServerStatusAggregator() {
    }

    /**
     * Returns the least healthy status in {@code statuses}, or {@link ServerStatus#STOPPED} if it is
     * empty (no servers running for the project).
     */
    public static ServerStatus aggregate(Collection<ServerStatus> statuses) {
        for (ServerStatus candidate : WORST_TO_BEST) {
            if (statuses.contains(candidate)) {
                return candidate;
            }
        }
        return ServerStatus.STOPPED;
    }
}
