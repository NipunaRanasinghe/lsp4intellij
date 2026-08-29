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

import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;

import java.util.List;

/**
 * Reference lookup as {@link RenameFeature} needs it, to decide which files it opened only to
 * compute the rename and should therefore close again afterwards.
 * <p>
 * {@code EditorEventManager.references(int, boolean, boolean)} is public and was called
 * unqualified — so virtually — from {@code rename} before the feature-layer decomposition. Calling
 * {@code NavigationFeature.references} directly would skip an extension's override of it. See
 * {@link CompletionOverrides} for the same reasoning in more detail.
 */
@FunctionalInterface
public interface ReferenceLookup {

    Pair<List<PsiElement>, List<VirtualFile>> references(int offset, boolean getOriginalElement, boolean close);
}
