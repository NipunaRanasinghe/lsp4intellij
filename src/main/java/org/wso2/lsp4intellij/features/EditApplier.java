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
package org.wso2.lsp4intellij.features;

import org.eclipse.lsp4j.InsertReplaceEdit;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.List;

/**
 * Matches {@code EditorEventManager.applyEdit(int, List, String, boolean, boolean)} — the future
 * {@code WorkspaceEditApplier} (ARCHITECTURE.md section 2.4) that hasn't been extracted yet.
 * Feature classes that need to write text edits but don't own that logic themselves (currently
 * {@link CompletionFeature} and {@link FormattingFeature}) take it as this injected callback
 * instead, matching the narrow-callback pattern used elsewhere in this decomposition until an
 * {@code EditorContext}-style shared session object exists to hold it.
 */
@FunctionalInterface
public interface EditApplier {
    boolean apply(int version, List<Either<TextEdit, InsertReplaceEdit>> edits,
            String name, boolean closeAfter, boolean setCaret);
}
