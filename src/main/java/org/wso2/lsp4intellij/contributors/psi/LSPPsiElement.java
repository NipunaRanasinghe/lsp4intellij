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
package org.wso2.lsp4intellij.contributors.psi;

import com.intellij.lang.ASTNode;
import com.intellij.lang.Language;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileTypes.PlainTextLanguage;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.psi.ContributedReferenceHost;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.NavigatablePsiElement;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiInvalidElementAccessException;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceService;
import com.intellij.psi.ResolveState;
import com.intellij.psi.scope.PsiScopeProcessor;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.wso2.lsp4intellij.utils.ApplicationUtils;
import org.wso2.lsp4intellij.utils.FileUtils;

import javax.swing.Icon;

/**
 * A simple PsiElement for LSP.
 *
 * <p>This is a synthetic element: it has no real AST behind it (see {@link #getNode()}), no children,
 * and no parent other than its containing file. That's the correct trade-off for a language-agnostic
 * client with no per-language grammar to build a real PSI tree from, but it means platform features
 * that walk the PSI tree (structure view, PSI-based inspections/refactorings, tree-based navigation)
 * will not work against these elements — only the specific LSP-backed features this library implements
 * (references, rename, go-to-definition) are supported.
 */
public class LSPPsiElement extends UserDataHolderBase implements PsiNameIdentifierOwner, NavigatablePsiElement {

    private final PsiManager manager;
    private final LSPPsiReference reference;
    private final Project project;
    private String name;
    private final PsiFile file;
    public final int start;
    public final int end;

    /**
     * @param name    The name (text) of the element
     * @param project The project it belongs to
     * @param start   The offset in the editor where the element starts
     * @param end     The offset where it ends
     */
    public LSPPsiElement(String name, @NotNull Project project, int start, int end, PsiFile file) {
        this.project = project;
        this.name = name;
        this.start = start;
        this.end = end;
        this.file = file;
        manager = PsiManager.getInstance(project);
        reference = new LSPPsiReference(this);
    }

    /**
     * Returns the language of the PSI element.
     *
     * @return the language instance.
     */
    @NotNull
    public Language getLanguage() {
        return PlainTextLanguage.INSTANCE;
    }

    /**
     * Returns the PSI manager for the project to which the PSI element belongs.
     *
     * @return the PSI manager instance.
     */
    public PsiManager getManager() {
        return manager;
    }

    /**
     * Returns the array of children for the PSI element. Important: In some implementations children are only composite
     * elements, i.e. not a leaf elements
     *
     * @return the array of child elements.
     */
    @NotNull
    public PsiElement[] getChildren() {
        return new PsiElement[0];
    }

    /**
     * Returns the parent of the PSI element.
     *
     * @return the parent of the element, or null if the element has no parent.
     */
    public PsiElement getParent() {
        return getContainingFile();
    }

    /**
     * Returns the first child of the PSI element.
     *
     * @return the first child, or null if the element has no children.
     */
    public PsiElement getFirstChild() {
        return null;
    }

    /**
     * Returns the last child of the PSI element.
     *
     * @return the last child, or null if the element has no children.
     */
    public PsiElement getLastChild() {
        return null;
    }

    /**
     * Returns the next sibling of the PSI element.
     *
     * @return the next sibling, or null if the node is the last in the list of siblings.
     */
    public PsiElement getNextSibling() {
        return null;
    }

    /**
     * Returns the previous sibling of the PSI element.
     *
     * @return the previous sibling, or null if the node is the first in the list of siblings.
     */
    public PsiElement getPrevSibling() {
        return null;
    }

    /**
     * Returns the text range in the document occupied by the PSI element.
     *
     * @return the text range.
     */
    public TextRange getTextRange() {
        return new TextRange(start, end);
    }

    /**
     * Returns the text offset of the PSI element relative to its parent.
     *
     * @return the relative offset.
     */
    public int getStartOffsetInParent() {
        return start;
    }

    /**
     * Returns the length of text of the PSI element.
     *
     * @return the text length.
     */
    public int getTextLength() {
        return end - start;
    }

    /**
     * Finds a leaf PSI element at the specified offset from the start of the text range of this node.
     *
     * @param offset the relative offset for which the PSI element is requested.
     * @return the element at the offset, or null if none is found.
     */
    public PsiElement findElementAt(int offset) {
        return null;
    }

    /**
     * Finds a reference at the specified offset from the start of the text range of this node.
     *
     * @param offset the relative offset for which the reference is requested.
     * @return the reference at the offset, or null if none is found.
     */
    public PsiReference findReferenceAt(int offset) {
        return null;
    }

    /**
     * Returns the text of the PSI element as a character array.
     *
     * @return the element text as a character array.
     */
    @NotNull
    public char[] textToCharArray() {
        return name.toCharArray();
    }

    /**
     * Returns the PSI element which should be used as a navigation target when navigation to this PSI element is
     * requested. The method can either return {@code this} or substitute a different element if this element does not
     * have an associated file and offset. (For example, if the source code of a library is attached to a project, the
     * navigation element for a compiled library class is its source class.)
     *
     * @return the navigation target element.
     */
    public PsiElement getNavigationElement() {
        return this;
    }

    /**
     * Returns the PSI element which corresponds to this element and belongs to either the project source path or class
     * path. The method can either return {@code this} or substitute a different element if this element does not belong
     * to the source path or class path. (For example, the original element for a library source file is the
     * corresponding compiled class file.)
     *
     * @return the original element.
     */
    public PsiElement getOriginalElement() {
        return null;
    }

    /**
     * Checks if the text of this PSI element is equal to the specified character sequence.
     *
     * @param text the character sequence to compare with.
     * @return true if the text is equal, false otherwise.
     */
    public boolean textMatches(@NotNull CharSequence text) {
        return getText().contentEquals(text);
    }

    //Q: get rid of these methods?

    /**
     * Checks if the text of this PSI element is equal to the text of the specified PSI element.
     *
     * @param element the element to compare the text with.
     * @return true if the text is equal, false otherwise.
     */
    public boolean textMatches(PsiElement element) {
        return getText().equals(element.getText());
    }

    /**
     * Checks if the text of this element contains the specified character.
     *
     * @param c the character to search for.
     * @return true if the character is found, false otherwise.
     */
    public boolean textContains(char c) {
        return getText().indexOf(c) >= 0;
    }

    /**
     * Returns the text of the PSI element.
     *
     * @return the element text.
     */
    public String getText() {
        return name;
    }

    /**
     * Passes the element to the specified visitor.
     *
     * @param visitor the visitor to pass the element to.
     */
    public void accept(PsiElementVisitor visitor) {
        visitor.visitElement(this);
    }

    /**
     * Passes the children of the element to the specified visitor.
     *
     * @param visitor the visitor to pass the children to.
     */
    public void acceptChildren(@NotNull PsiElementVisitor visitor) {

    }

    /**
     * Creates a copy of the file containing the PSI element and returns the corresponding element in the created copy.
     * Resolve operations performed on elements in the copy of the file will resolve to elements in the copy, not in the
     * original file.
     *
     * @return the element in the file copy corresponding to this element.
     */
    public PsiElement copy() {
        return null;
    }

    /**
     * Adds a child to this PSI element.
     *
     * @param element the child element to add.
     * @return the element which was actually added (either { @code element} or its copy).
     * @throws IncorrectOperationException if the modification is not supported or not possible for some reason.
     */
    public PsiElement add(@NotNull PsiElement element) {
        throw new IncorrectOperationException();
    }

    /**
     * Adds a child to this PSI element, before the specified anchor element.
     *
     * @param element the child element to add.
     * @param anchor  the anchor before which the child element is inserted (must be a child of this PSI element)
     * @return the element which was actually added (either { @code element} or its copy).
     * @throws IncorrectOperationException if the modification is not supported or not possible for some reason.
     */
    public PsiElement addBefore(@NotNull PsiElement element, PsiElement anchor) {
        throw new IncorrectOperationException();
    }

    /**
     * Adds a child to this PSI element, after the specified anchor element.
     *
     * @param element the child element to add.
     * @param anchor  the anchor after which the child element is inserted (must be a child of this PSI element)
     * @return the element which was actually added (either { @code element} or its copy).
     * @throws IncorrectOperationException if the modification is not supported or not possible for some reason.
     */
    public PsiElement addAfter(@NotNull PsiElement element, PsiElement anchor) {
        throw new IncorrectOperationException();
    }

    /**
     * Checks if it is possible to add the specified element as a child to this element, and throws an exception if the
     * add is not possible. Does not actually modify anything.
     *
     * @param element the child element to check the add possibility.
     * @throws IncorrectOperationException if the modification is not supported or not possible for some reason.
     * @deprecated not all PSI implementations implement this method correctly.
     */
    @Deprecated
    public void checkAdd(@NotNull PsiElement element) {
        throw new IncorrectOperationException();
    }

    /**
     * Adds a range of elements as children to this PSI element.
     *
     * @param first the first child element to add.
     * @param last  the last child element to add (must have the same parent as { @code first})
     * @return the first child element which was actually added (either { @code first} or its copy).
     * @throws IncorrectOperationException if the modification is not supported or not possible for some reason.
     */
    public PsiElement addRange(PsiElement first, PsiElement last) {
        throw new IncorrectOperationException();
    }

    /**
     * Adds a range of elements as children to this PSI element, before the specified anchor element.
     *
     * @param first  the first child element to add.
     * @param last   the last child element to add (must have the same parent as { @code first})
     * @param anchor the anchor before which the child element is inserted (must be a child of this PSI element)
     * @return the first child element which was actually added (either { @code first} or its copy).
     * @throws IncorrectOperationException if the modification is not supported or not possible for some reason.
     */
    public PsiElement addRangeBefore(@NotNull PsiElement first, @NotNull PsiElement last, PsiElement anchor) {
        throw new IncorrectOperationException();
    }

    /**
     * Adds a range of elements as children to this PSI element, after the specified anchor element.
     *
     * @param first  the first child element to add.
     * @param last   the last child element to add (must have the same parent as { @code first})
     * @param anchor the anchor after which the child element is inserted (must be a child of this PSI element)
     * @return the first child element which was actually added (either { @code first} or its copy).
     * @throws IncorrectOperationException if the modification is not supported or not possible for some reason.
     */
    public PsiElement addRangeAfter(PsiElement first, PsiElement last, PsiElement anchor) {
        throw new IncorrectOperationException();
    }

    /**
     * Deletes this PSI element from the tree.
     *
     * @throws IncorrectOperationException if the modification is not supported or not possible for some reason (for
     *                                     example, the file containing the element is read-only).
     */
    public void delete() {
        throw new IncorrectOperationException();
    }

    /**
     * Checks if it is possible to delete the specified element from the tree, and throws an exception if the add is not
     * possible. Does not actually modify anything.
     *
     * @throws IncorrectOperationException if the modification is not supported or not possible for some reason.
     * @deprecated not all PSI implementations implement this method correctly.
     */
    @Deprecated
    public void checkDelete() {
        throw new IncorrectOperationException();
    }

    /**
     * Deletes a range of children of this PSI element from the tree.
     *
     * @param first the first child to delete (must be a child of this PSI element)
     * @param last  the last child to delete (must be a child of this PSI element)
     * @throws IncorrectOperationException if the modification is not supported or not possible for some reason.
     */
    public void deleteChildRange(PsiElement first, PsiElement last) {
        throw new IncorrectOperationException();
    }

    /**
     * Replaces this PSI element (along with all its children) with another element (along with the children).
     *
     * @param newElement the element to replace this element with.
     * @return the element which was actually inserted in the tree (either { @code newElement} or its copy)
     * @throws IncorrectOperationException if the modification is not supported or not possible for some reason.
     */
    public PsiElement replace(@NotNull PsiElement newElement) {
        throw new IncorrectOperationException();
    }

    /**
     * Checks if this PSI element is valid. Valid elements and their hierarchy members can be accessed for reading and
     * writing. Valid elements can still correspond to underlying documents whose text is different, when those
     * documents have been changed and not yet committed
     * ({@link PsiDocumentManager#commitDocument(com.intellij.openapi.editor.Document)}).
     * (In this case an attempt to change PSI will result in an exception).
     * <p>
     * Any access to invalid elements results in {@link PsiInvalidElementAccessException}.
     * <p>
     * Once invalid, elements can't become valid again.
     * <p>
     * Elements become invalid in following cases:
     * <ul>
     * <li>They have been deleted via PSI operation ({@link #delete()})</li>
     * <li>They have been deleted as a result of an incremental reparse (document commit)</li>
     * <li>Their containing file has been changed externally, or renamed so that its PSI had to be rebuilt from
     * scratch</li>
     * </ul>
     *
     * @return true if the element is valid, false otherwise.
     * @see com.intellij.psi.util.PsiUtilCore#ensureValid(PsiElement)
     */
    public boolean isValid() {
        return true;
    }

    /**
     * Checks if the contents of the element can be modified (if it belongs to a non-read-only source file.).
     *
     * @return true if the element can be modified, false otherwise.
     */
    public boolean isWritable() {
        return true;
    }

    /**
     * Returns the reference from this PSI element to another PSI element (or elements), if one exists. If the element
     * has multiple associated references (see {@link #getReferences()} for an example), returns the first associated
     * reference.
     *
     * @return the reference instance, or null if the PSI element does not have any associated references.
     * @see com.intellij.psi.search.searches.ReferencesSearch
     */
    public PsiReference getReference() {
        return reference;
    }

    /**
     * Returns all references from this PSI element to other PSI elements. An element can have multiple references when,
     * for example, the element is a string literal containing multiple sub-strings which are valid full-qualified class
     * names. If an element contains only one text fragment which acts as a reference but the reference has multiple
     * possible targets, {@link PsiPolyVariantReference} should be used instead of returning multiple references.
     * <p>
     * Actually, it's preferable to call {@link PsiReferenceService#getReferences} instead as it allows adding
     * references by plugins when the element implements {@link ContributedReferenceHost}.
     *
     * @return the array of references, or an empty array if the element has no associated references.
     * @see PsiReferenceService#getReferences
     * @see com.intellij.psi.search.searches.ReferencesSearch
     */
    @NotNull
    public PsiReference[] getReferences() {
        return new PsiReference[]{reference};
    }

    /**
     * Passes the declarations contained in this PSI element and its children for processing to the specified scope
     * processor.
     *
     * @param processor  the processor receiving the declarations.
     * @param lastParent the child of this element has been processed during the previous step of the tree up walk
     *                   (declarations under this element do not need to be processed again)
     * @param place      the original element from which the tree up walk was initiated.
     * @return true if the declaration processing should continue or false if it should be stopped.
     */
    public boolean processDeclarations(@NotNull PsiScopeProcessor processor, @NotNull ResolveState state,
                                       PsiElement lastParent,
                                       @NotNull PsiElement place) {
        return false;
    }

    /**
     * Returns the element which should be used as the parent of this element in a tree up walk during a resolve
     * operation. For most elements, this returns {@code getParent()}, but the context can be overridden for some
     * elements like code fragments.
     *
     * @return the resolve context element.
     */
    public PsiElement getContext() {
        return null;
    }

    /**
     * Checks if an actual source or class file corresponds to the element. Non-physical elements include, for example,
     * PSI elements created for the watch expressions in the debugger. Non-physical elements do not generate tree change
     * events. Also, {@link PsiDocumentManager#getDocument(PsiFile)} returns null for non-physical elements. Not to be
     * confused with {@link FileViewProvider#isPhysical()}.
     *
     * @return true if the element is physical, false otherwise.
     */
    public boolean isPhysical() {
        return true;
    }

    /**
     * Returns the scope in which the declarations for the references in this PSI element are searched.
     *
     * @return the resolve scope instance.
     */
    @NotNull
    public GlobalSearchScope getResolveScope() {
        return getContainingFile().getResolveScope();
    }

    /**
     * Returns the scope in which references to this element are searched.
     *
     * @return the search scope instance.
     * @see com.intellij.psi.search.PsiSearchHelper#getUseScope(PsiElement)
     */
    @NotNull
    public SearchScope getUseScope() {
        return getContainingFile().getResolveScope();
    }

    /**
     * Returns the AST node corresponding to the element.
     *
     * @return the AST node instance.
     */
    public ASTNode getNode() {
        return null;
    }

    /**
     * toString() should never be presented to the user.
     */
    public String toString() {
        return "Name : " + name + " at offset " + start + " to " + end + " in " + project;
    }

    /**
     * This method shouldn't be called by clients directly, because there are no guarantees of it being symmetric. It's
     * called by {@link PsiManager#areElementsEquivalent(PsiElement, PsiElement)} internally, which clients should
     * invoke instead.
     * <p>
     * Implementations of this method should return {@code true} if the parameter is resolve-equivalent to {@code this},
     * i.e. it represents the same entity from the language perspective. See also {@link
     * PsiManager#areElementsEquivalent(PsiElement, PsiElement)} documentation.
     */
    public boolean isEquivalentTo(PsiElement another) {
        return this == another;
    }

    public Icon getIcon(int flags) {
        return null;
    }

    public PsiElement getNameIdentifier() {
        return this;
    }

    public PsiElement setName(@NotNull String name) {
        this.name = name;
        return this;
    }

    public ItemPresentation getPresentation() {
        return new ItemPresentation() {
            public String getPresentableText() {
                return getName();
            }

            public String getLocationString() {
                return getContainingFile().getName();
            }

            public Icon getIcon(boolean unused) {
                return null;
            }
        };
    }

    public String getName() {
        return name;
    }

    public void navigate(boolean requestFocus) {
        Editor editor = FileUtils.editorFromPsiFile(getContainingFile());
        if (editor == null) {
            OpenFileDescriptor descriptor = new OpenFileDescriptor(getProject(), getContainingFile().getVirtualFile(),
                    getTextOffset());
            ApplicationUtils.invokeLater(() -> ApplicationUtils
                    .writeAction(() -> FileEditorManager.getInstance(getProject()).openTextEditor(descriptor, false)));
        }
    }

    /**
     * Returns the file containing the PSI element.
     *
     * @return the file instance, or null if the PSI element is not contained in a file (for example, the element
     * represents a package or directory).
     * @throws PsiInvalidElementAccessException if this element is invalid
     */
    public PsiFile getContainingFile() {
        return file;
    }

    /**
     * Returns the project to which the PSI element belongs.
     *
     * @return the project instance.
     * @throws PsiInvalidElementAccessException if this element is invalid
     */
    @NotNull
    public Project getProject() {
        return project;
    }

    /**
     * Returns the offset in the file to which the caret should be placed when performing the navigation to the element.
     * (For classes implementing {@link PsiNamedElement}, this should return the offset in the file of the name
     * identifier.)
     *
     * @return the offset of the PSI element.
     */
    public int getTextOffset() {
        return start;
    }

    public boolean canNavigateToSource() {
        return true;
    }

    public boolean canNavigate() {
        return true;
    }
}
