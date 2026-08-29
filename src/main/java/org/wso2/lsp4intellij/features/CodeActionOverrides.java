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

import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.List;

/**
 * The subset of {@code EditorEventManager}'s public code-action methods that
 * {@link CodeActionFeature} calls back into rather than calling on itself.
 * <p>
 * Both methods had no caller anywhere in this codebase other than
 * {@link CodeActionFeature#requestAndShowCodeActions()} — being overridden by an extension's
 * {@code EditorEventManager} subclass was their only purpose. As unqualified self-calls inside the
 * old single class they dispatched virtually; inside {@code final CodeActionFeature} they cannot.
 * See {@link CompletionOverrides} for the same reasoning in more detail.
 */
public interface CodeActionOverrides {

    List<Either<Command, CodeAction>> codeAction(int offset);

    CodeAction resolvedCodeAction(CodeAction codeAction);
}
