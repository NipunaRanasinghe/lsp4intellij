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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.wso2.lsp4intellij.logging.LspLogEntry;
import org.wso2.lsp4intellij.logging.LspLogSource;

/**
 * Decides whether one {@link LspLogEntry} should be shown for a chosen level threshold and source.
 * Holds no Swing state and touches no IntelliJ Platform UI class, so the decision is unit-testable on
 * its own; {@link LspInspectorPanel} only calls {@link #matches(LspLogEntry)}.
 *
 * @param minLevel the least severe {@link MessageType} to show; {@code null} means every level is
 *         shown. {@link MessageType#getValue()} is smaller for more severe levels ({@code Error} is 1,
 *         {@code Log} is 4), so choosing {@code Warning} shows {@code Warning} and {@code Error} but
 *         not {@code Info} or {@code Log}
 * @param source the single {@link LspLogSource} to show; {@code null} means every source is shown
 */
public record LspInspectorFilter(@Nullable MessageType minLevel, @Nullable LspLogSource source) {

    /** Shows every entry regardless of level or source. */
    public static final LspInspectorFilter ALL = new LspInspectorFilter(null, null);

    /**
     * Returns whether {@code entry} passes both the level and the source check.
     */
    public boolean matches(@NotNull LspLogEntry entry) {
        if (source != null && entry.source() != source) {
            return false;
        }
        return minLevel == null || entry.level().getValue() <= minLevel.getValue();
    }
}
