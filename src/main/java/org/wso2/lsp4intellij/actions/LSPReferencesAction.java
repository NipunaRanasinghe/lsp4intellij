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

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.codeInsight.hint.HintManagerImpl;
import com.intellij.find.FindBundle;
import com.intellij.find.findUsages.FindUsagesOptions;
import com.intellij.find.findUsages.PsiElement2UsageTargetAdapter;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.ui.JBColor;
import com.intellij.ui.LightweightHint;
import com.intellij.usageView.UsageInfo;
import com.intellij.usageView.UsageViewUtil;
import com.intellij.usages.Usage;
import com.intellij.usages.UsageInfo2UsageAdapter;
import com.intellij.usages.UsageTarget;
import com.intellij.usages.UsageViewManager;
import com.intellij.usages.UsageViewPresentation;
import org.wso2.lsp4intellij.editor.EditorEventManager;
import org.wso2.lsp4intellij.editor.EditorEventManagerBase;

import java.awt.Color;
import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JLabel;

import static org.wso2.lsp4intellij.utils.ApplicationUtils.computableReadAction;
import static org.wso2.lsp4intellij.utils.ApplicationUtils.invokeLater;

/**
 * Action for references / see usages (SHIFT+ALT+F7).
 */
public class LSPReferencesAction extends DumbAwareAction {

    @Override
    public void actionPerformed(AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (editor != null) {
            EditorEventManager eventManager = EditorEventManagerBase.forEditor(editor);
            if (eventManager == null) {
                return;
            }
            forManagerAndOffset(eventManager, editor.getCaretModel().getCurrentCaret().getOffset());
        }
    }

    public void forManagerAndOffset(EditorEventManager manager, int offset) {
        // The references lookup blocks on the server response, so it runs on the wrapper's
        // dispatcher; only showing the results runs on the EDT.
        manager.wrapper.pool(() -> {
            Pair<List<PsiElement>, List<VirtualFile>> references = manager.references(offset);
            List<PsiElement2UsageTargetAdapter> targets = computableReadAction(() -> {
                List<PsiElement2UsageTargetAdapter> adapters = new ArrayList<>();
                if (references.first != null && references.second != null) {
                    references.first.forEach(element ->
                            adapters.add(new PsiElement2UsageTargetAdapter(element, true)));
                }
                return adapters;
            });
            Editor editor = manager.editor;
            invokeLater(() -> {
                if (!editor.isDisposed()) {
                    showReferences(editor, targets, editor.offsetToLogicalPosition(offset));
                }
            });
        });
    }

    private void showReferences(Editor editor, List<PsiElement2UsageTargetAdapter> targets, LogicalPosition position) {
        if (targets.isEmpty()) {
            short constraint = HintManager.ABOVE;
            int flags = HintManager.HIDE_BY_ANY_KEY | HintManager.HIDE_BY_TEXT_CHANGE | HintManager.HIDE_BY_SCROLLING;
            JLabel label = new JLabel("No references found");
            label.setBackground(new JBColor(new Color(150, 0, 0), new Color(150, 0, 0)));
            LightweightHint hint = new LightweightHint(label);
            // HintManagerImpl (internal) is used deliberately here too - see the same note in
            // GUIUtils.createAndShowEditorHint; no public HintManager method covers this positioning.
            Point p = HintManagerImpl.getHintPosition(hint, editor, position, constraint);
            HintManagerImpl.getInstanceImpl().showEditorHint(hint, editor, p, flags, 0, false,
                    HintManagerImpl.createHintHint(editor, p, hint, constraint).setContentActive(false));
        } else {
            List<Usage> usages = new ArrayList<>();
            targets.forEach(ut -> {
                PsiElement elem = ut.getElement();
                usages.add(new UsageInfo2UsageAdapter(new UsageInfo(elem, -1, -1, false)));
            });

            if (editor == null) {
                return;
            }
            Project project = editor.getProject();
            if (project == null) {
                return;
            }
            UsageViewPresentation presentation = createPresentation(targets.get(0).getElement(),
                    new FindUsagesOptions(editor.getProject()), false);
            UsageViewManager.getInstance(project)
                    .showUsages(new UsageTarget[] { targets.get(0) }, usages.toArray(new Usage[usages.size()]),
                            presentation);
        }
    }

    private UsageViewPresentation createPresentation(PsiElement psiElement, FindUsagesOptions options,
            boolean toOpenInNewTab) {
        UsageViewPresentation presentation = new UsageViewPresentation();
        String scopeString = options.searchScope.getDisplayName();
        presentation.setScopeText(scopeString);
        String usagesString = options.generateUsagesString();
        presentation.setSearchString(usagesString);
        String title = FindBundle.message("find.usages.of.element.in.scope.panel.title", usagesString,
                UsageViewUtil.getLongName(psiElement), scopeString);
        presentation.setTabText(title);
        presentation.setTabName(FindBundle
                .message("find.usages.of.element.tab.name", usagesString, UsageViewUtil.getShortName(psiElement)));
        presentation.setTargetsNodeText(StringUtil.capitalize(UsageViewUtil.getType(psiElement)));
        presentation.setOpenInNewTab(toOpenInNewTab);
        presentation.setShowCancelButton(true);
        return presentation;
    }
}
