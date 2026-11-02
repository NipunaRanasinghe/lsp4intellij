/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
package org.wso2.lsp4intellij.client;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.wso2.lsp4intellij.client.languageserver.requestmanager.RequestManager;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;
import org.wso2.lsp4intellij.editor.EditorEventManager;

/**
 * The client context which is received by {@link DefaultLanguageClient}. The context contains
 * information about the runtime and its components.
 *
 * @author gayanper
 */
public interface ClientContext {

    /**
     * Returns the {@link EditorEventManager} for the given document URI.
     */
    @Nullable
    EditorEventManager getEditorEventManagerFor(@NotNull String documentUri);

    /**
     * Returns the {@link Project} associated with the LanguageClient.
     */
    @Nullable
    Project getProject();

    /**
     * Returns the {@link RequestManager} associated with the Language Server Connection.
     */
    @Nullable
    RequestManager getRequestManager();

    /**
     * Returns the {@link LanguageServerWrapper} this context was built for, so that entries recorded
     * by {@code DefaultLanguageClient} can be attributed to a server. {@code null} by default so that
     * consumers who implement {@link ClientContext} directly are unaffected.
     */
    @Nullable
    default LanguageServerWrapper getWrapper() {
        return null;
    }
}
