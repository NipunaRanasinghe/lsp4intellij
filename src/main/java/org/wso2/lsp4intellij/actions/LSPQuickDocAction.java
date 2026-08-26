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
package org.wso2.lsp4intellij.actions;

import com.intellij.codeInsight.documentation.actions.ShowQuickDocInfoAction;
import com.intellij.lang.Language;
import com.intellij.lang.LanguageDocumentation;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.PlainTextLanguage;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiManager;
import org.wso2.lsp4intellij.editor.EditorEventManager;
import org.wso2.lsp4intellij.editor.EditorEventManagerBase;

/**
 * Action overriding QuickDoc (CTRL+Q).
 */
class LSPQuickDocAction extends ShowQuickDocInfoAction implements DumbAware {
    private static final Logger LOG = Logger.getInstance(LSPQuickDocAction.class);

    @Override
    public void actionPerformed(AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        Language language = PsiManager.getInstance(editor.getProject()).findFile(file).getLanguage();
        if (LanguageDocumentation.INSTANCE.allForLanguage(language).isEmpty()
                || language == PlainTextLanguage.INSTANCE) {
            EditorEventManager manager = EditorEventManagerBase.forEditor(editor);
            if (manager != null) {
                manager.quickDoc(editor);
            } else {
                super.actionPerformed(e);
            }
        } else {
            super.actionPerformed(e);
        }
    }
}
