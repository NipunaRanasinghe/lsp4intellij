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

public class ServerWrapperBaseClientContext implements ClientContext {

    private final LanguageServerWrapper wrapper;

    public ServerWrapperBaseClientContext(@NotNull LanguageServerWrapper wrapper) {
        this.wrapper = wrapper;
    }

    @Override
    public EditorEventManager getEditorEventManagerFor(@NotNull String documentUri) {
        return wrapper.getEditorManagerFor(documentUri);
    }

    @Nullable
    @Override
    public Project getProject() {
        return wrapper.getProject();
    }

    @Nullable
    @Override
    public RequestManager getRequestManager() {
        return wrapper.getRequestManager();
    }

    @Override
    public LanguageServerWrapper getWrapper() {
        return wrapper;
    }
}
