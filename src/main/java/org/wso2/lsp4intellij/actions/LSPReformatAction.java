/*
 * Copyright (c) 2018-2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
package org.wso2.lsp4intellij.actions;

import com.intellij.codeInsight.actions.ReformatCodeAction;
import com.intellij.lang.LanguageFormatting;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.wso2.lsp4intellij.IntellijLanguageClient;
import org.wso2.lsp4intellij.requests.ReformatHandler;

/**
 * Action overriding the default reformat action
 * Fallback to the default action if the language is already supported or not supported by any language server
 */
public class LSPReformatAction extends ReformatCodeAction implements DumbAware {
    private Logger LOG = Logger.getInstance(LSPReformatAction.class);

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getData(CommonDataKeys.PROJECT);
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (editor == null || project == null) {
            return;
        }
        PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        if (LanguageFormatting.INSTANCE.allForLanguage(file.getLanguage()).isEmpty() && IntellijLanguageClient
                .isExtensionSupported(file.getVirtualFile())) {
            // if editor hasSelection, only reformat selection, not reformat the whole file
            if (editor.getSelectionModel().hasSelection()) {
                ReformatHandler.reformatSelection(editor);
            } else {
                ReformatHandler.reformatFile(editor);
            }
        } else {
            super.actionPerformed(e);
        }
    }

    @Override
    public void update(AnActionEvent event) {
        super.update(event);
    }
}
