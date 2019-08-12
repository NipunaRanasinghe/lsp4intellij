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
package org.wso2.lsp4intellij.contributors.rename;

import com.intellij.codeInsight.template.impl.TemplateManagerImpl;
import com.intellij.codeInsight.template.impl.TemplateState;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.command.impl.StartMarkAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.NonEmptyInputValidator;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.Pass;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageUtil;
import com.intellij.refactoring.rename.PsiElementRenameHandler;
import com.intellij.refactoring.rename.RenameHandler;
import com.intellij.refactoring.rename.RenamePsiElementProcessor;
import com.intellij.refactoring.rename.inplace.InplaceRefactoring;
import com.intellij.refactoring.rename.inplace.MemberInplaceRenameHandler;
import com.intellij.refactoring.rename.inplace.MemberInplaceRenamer;
import org.jetbrains.annotations.NotNull;
import org.wso2.lsp4intellij.IntellijLanguageClient;
import org.wso2.lsp4intellij.contributors.psi.LSPPsiElement;
import org.wso2.lsp4intellij.editor.EditorEventManager;
import org.wso2.lsp4intellij.editor.EditorEventManagerBase;
import org.wso2.lsp4intellij.utils.FileUtils;

import java.util.List;

/**
 * The LSP based rename handler implementation.
 */
public class LSPRenameHandler implements RenameHandler {

    @Override
    public void invoke(@NotNull Project project, @NotNull PsiElement[] elements, DataContext dataContext) {
        if (elements.length == 1) {
            new MemberInplaceRenameHandler()
                    .doRename(elements[0], dataContext.getData(CommonDataKeys.EDITOR), dataContext);
        } else {
            invoke(project, dataContext.getData(CommonDataKeys.EDITOR), dataContext.getData(CommonDataKeys.PSI_FILE),
                    dataContext);
        }
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file, DataContext dataContext) {
        EditorEventManager manager = EditorEventManagerBase.forEditor(editor);
        if (manager != null) {
            if (editor.getContentComponent().hasFocus()) {
                LSPPsiElement psiElement = getElementAtOffset(manager,
                        editor.getCaretModel().getCurrentCaret().getOffset());
                if (psiElement != null) {
                    doRename(psiElement, editor);
                }
            }
        }
    }

    private InplaceRefactoring doRename(PsiElement elementToRename, Editor editor) {
        if (elementToRename instanceof PsiNameIdentifierOwner) {
            RenamePsiElementProcessor processor = RenamePsiElementProcessor.forElement(elementToRename);
            if (processor.isInplaceRenameSupported()) {
                StartMarkAction startMarkAction = StartMarkAction.canStart(elementToRename.getProject());
                if (startMarkAction == null || (processor.substituteElementToRename(elementToRename, editor)
                        == elementToRename)) {
                    processor.substituteElementToRename(elementToRename, editor, new Pass<PsiElement>() {
                        @Override
                        public void pass(PsiElement element) {
                            MemberInplaceRenamer renamer = createMemberRenamer(element,
                                    (PsiNameIdentifierOwner) elementToRename, editor);
                            boolean startedRename = renamer.performInplaceRename();
                            if (!startedRename) {
                                performDialogRename(editor);
                            }
                        }
                    });
                    return null;
                }
            } else {
                InplaceRefactoring inplaceRefactoring = editor.getUserData(InplaceRefactoring.INPLACE_RENAMER);
                if ((inplaceRefactoring instanceof MemberInplaceRenamer)) {
                    TemplateState templateState = TemplateManagerImpl
                            .getTemplateState(InjectedLanguageUtil.getTopLevelEditor(editor));
                    if (templateState != null) {
                        templateState.gotoEnd(true);
                    }
                }
            }
        }
        performDialogRename(editor);
        return null;
    }

    @Override
    public boolean isRenaming(DataContext dataContext) {
        return isAvailableOnDataContext(dataContext);
    }

    @Override
    public boolean isAvailableOnDataContext(DataContext dataContext) {
        PsiElement element = PsiElementRenameHandler.getElement(dataContext);
        Editor editor = CommonDataKeys.EDITOR.getData(dataContext);
        PsiFile file = CommonDataKeys.PSI_FILE.getData(dataContext);

        return editor != null && file != null && isAvailable(element, editor, file);
    }

    private boolean isAvailable(PsiElement psiElement, Editor editor, PsiFile psiFile) {
        if (psiElement instanceof PsiFile || psiElement instanceof LSPPsiElement) {
            return true;
        } else {
            return IntellijLanguageClient.isExtensionSupported(psiFile.getVirtualFile());
        }
    }

    private MemberInplaceRenamer createMemberRenamer(PsiElement element, PsiNameIdentifierOwner elementToRename,
                                                     Editor editor) {
        return new LSPInplaceRenamer((PsiNamedElement) element, elementToRename, editor);
    }

    private void performDialogRename(Editor editor) {
        EditorEventManager manager = EditorEventManagerBase.forEditor(editor);
        if (manager != null) {
            String renameTo = Messages
                    .showInputDialog(editor.getProject(), "Enter new name: ", "Rename", Messages.getQuestionIcon(), "",
                            new NonEmptyInputValidator());
            if (renameTo != null && !renameTo.equals("")) {
                manager.rename(renameTo);
            }
        }
    }

    private LSPPsiElement getElementAtOffset(EditorEventManager eventManager, int offset) {
        Pair<List<PsiElement>, List<VirtualFile>> refResponse = eventManager.references(offset, true, false);
        List<PsiElement> refs = refResponse.getFirst();
        if (refs == null || refs.isEmpty()) {
            return null;
        }

        PsiElement curElement = refs.stream()
                .filter(e -> e.getTextRange().getStartOffset() <= offset && offset <= e.getTextRange().getEndOffset())
                .findAny().orElse(null);
        if (curElement != null) {
            return new LSPPsiElement(curElement.getText(), curElement.getProject(),
                    curElement.getTextRange().getStartOffset(), curElement.getTextRange().getEndOffset(),
                    curElement.getContainingFile());
        }
        return null;
    }
}
