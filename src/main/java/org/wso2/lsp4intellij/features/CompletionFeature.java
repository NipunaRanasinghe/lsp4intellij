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

import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.lookup.AutoCompletionPolicy;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.codeInsight.template.TemplateManager;
import com.intellij.codeInsight.template.impl.TemplateImpl;
import com.intellij.codeInsight.template.impl.TextExpression;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorModificationUtil;
import com.intellij.openapi.project.Project;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.InsertReplaceEdit;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jetbrains.annotations.NotNull;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;
import org.wso2.lsp4intellij.contributors.icon.LSPIconProvider;
import org.wso2.lsp4intellij.utils.DocumentUtils;
import org.wso2.lsp4intellij.utils.GUIUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.Icon;

import static org.wso2.lsp4intellij.requests.Timeouts.COMPLETION;
import static org.wso2.lsp4intellij.utils.ApplicationUtils.invokeLater;
import static org.wso2.lsp4intellij.utils.ApplicationUtils.writeAction;
import static org.wso2.lsp4intellij.utils.DocumentUtils.toEither;

/**
 * Requests completion suggestions, converts them into lookup elements, and applies the
 * insert-time text edits, commands, and snippet expansion a chosen item triggers.
 *
 * <p>Extracted from {@code EditorEventManager} as the second slice of the feature-layer
 * decomposition (ARCHITECTURE.md section 2.4); {@code EditorEventManager} composes one instance
 * per editor and delegates its completion-facing public methods to it with the same signatures
 * and behavior they had before.
 *
 * <p>Three capabilities this feature needs are still owned by {@code EditorEventManager} — text
 * edit application (the future {@code WorkspaceEditApplier}; see {@link EditApplier}), LSP command
 * execution (shared with code actions), and triggering signature help (a separate feature) — and
 * are injected as narrow callbacks rather than duplicated here.
 */
public final class CompletionFeature {

    public static final String SNIPPET_PLACEHOLDER_REGEX = "(\\$\\{\\d+:?(\\{)?[^{}]*(\\})?\\}|\\$\\d+)";

    private final Editor editor;
    private final Project project;
    private final LanguageServerWrapper wrapper;
    private final TextDocumentIdentifier identifier;
    private final List<String> completionTriggers;
    private final EditApplier editApplier;
    private final Consumer<List<Command>> commandExecutor;
    private final Runnable signatureHelpTrigger;
    private final CompletionOverrides overrides;

    public CompletionFeature(Editor editor, Project project, LanguageServerWrapper wrapper,
            TextDocumentIdentifier identifier, List<String> completionTriggers, EditApplier editApplier,
            Consumer<List<Command>> commandExecutor, Runnable signatureHelpTrigger,
            CompletionOverrides overrides) {
        this.editor = editor;
        this.project = project;
        this.wrapper = wrapper;
        this.identifier = identifier;
        this.completionTriggers = completionTriggers;
        this.editApplier = editApplier;
        this.commandExecutor = commandExecutor;
        this.signatureHelpTrigger = signatureHelpTrigger;
        this.overrides = overrides;
    }

    /**
     * Returns the completion suggestions given a position.
     *
     * @param pos The LSP position
     * @return The suggestions
     */
    public Iterable<? extends LookupElement> completion(Position pos) {

        List<LookupElement> lookupItems = new ArrayList<>();
        CompletableFuture<Either<List<CompletionItem>, CompletionList>> request = wrapper.getRequestManager()
                .completion(new CompletionParams(identifier, pos));
        Either<List<CompletionItem>, CompletionList> res =
                wrapper.getRequestExecutor().waitFor(request, COMPLETION);
        if (res == null) {
            return lookupItems;
        }
        if (res.getLeft() != null) {
            for (CompletionItem item : res.getLeft()) {
                LookupElement lookupElement = overrides.createLookupItem(item);
                if (lookupElement != null) {
                    lookupItems.add(lookupElement);
                }
            }
        } else if (res.getRight() != null) {
            for (CompletionItem item : res.getRight().getItems()) {
                LookupElement lookupElement = overrides.createLookupItem(item);
                if (lookupElement != null) {
                    lookupItems.add(lookupElement);
                }
            }
        }
        return lookupItems;
    }

    /**
     * Creates a LookupElement given a CompletionItem.
     *
     * @param item The CompletionItem
     * @return The corresponding LookupElement
     */
    @SuppressWarnings("WeakerAccess")
    public LookupElement createLookupItem(CompletionItem item) {
        Command command = item.getCommand();
        String detail = item.getDetail();
        String insertText = item.getInsertText();
        CompletionItemKind kind = item.getKind();
        String label = item.getLabel();
        Either<TextEdit, InsertReplaceEdit> textEditEither = item.getTextEdit();
        TextEdit textEdit = (textEditEither != null) ? textEditEither.getLeft() : null;
        InsertReplaceEdit insertReplaceEdit = (textEditEither != null) ? textEditEither.getRight() : null;
        List<TextEdit> addTextEdits = item.getAdditionalTextEdits();
        String presentableText = StringUtils.isNotEmpty(label) ? label : (insertText != null) ? insertText : "";
        String tailText = (detail != null) ? detail : "";
        LSPIconProvider iconProvider = GUIUtils.getIconProviderFor(wrapper.getServerDefinition());
        Icon icon = iconProvider.getCompletionIcon(kind);
        LookupElementBuilder lookupElementBuilder;

        String lookupString = null;
        if (textEdit != null) {
            lookupString = textEdit.getNewText();
        } else if (insertReplaceEdit != null) {
            lookupString = insertReplaceEdit.getNewText();
        } else if (StringUtils.isNotEmpty(insertText)) {
            lookupString = insertText;
        } else if (StringUtils.isNotEmpty(label)) {
            lookupString = label;
        }
        if (StringUtils.isEmpty(lookupString)) {
            return null;
        }
        // Fixes IDEA internal assertion failure in windows.
        lookupString = lookupString.replace(DocumentUtils.WIN_SEPARATOR, DocumentUtils.LINUX_SEPARATOR);

        lookupElementBuilder = LookupElementBuilder.create(getLookupStringWithoutPlaceholders(item, lookupString));

        lookupElementBuilder = overrides.addCompletionInsertHandlers(item, lookupElementBuilder, lookupString);

        if (kind == CompletionItemKind.Keyword) {
            lookupElementBuilder = lookupElementBuilder.withBoldness(true);
        }

        return lookupElementBuilder.withPresentableText(presentableText).withTypeText(tailText, true).withIcon(icon)
                .withAutoCompletionPolicy(AutoCompletionPolicy.SETTINGS_DEPENDENT);
    }

    private String getLookupStringWithoutPlaceholders(CompletionItem item, String lookupString) {
        if (item.getInsertTextFormat() == InsertTextFormat.Snippet) {
            return convertPlaceHolders(lookupString);
        } else {
            return lookupString;
        }
    }

    @SuppressWarnings("WeakerAccess")
    public LookupElementBuilder addCompletionInsertHandlers(
            CompletionItem item, LookupElementBuilder builder,
            String lookupString) {

        String label = item.getLabel();
        Command command = item.getCommand();
        List<TextEdit> addTextEdits = item.getAdditionalTextEdits();
        InsertTextFormat format = item.getInsertTextFormat();

        if (addTextEdits != null) {
            builder = builder.withInsertHandler(
                    (InsertionContext context, LookupElement lookupElement) -> invokeLater(() -> {
                applyInitialTextEdit(item, context, lookupString);

                if (format == InsertTextFormat.Snippet) {
                    context.commitDocument();
                    overrides.prepareAndRunSnippet(lookupString);
                }

                context.commitDocument();
                editApplier.apply(Integer.MAX_VALUE, toEither(addTextEdits), "Completion : " + label, false, false);
                if (command != null) {
                    commandExecutor.accept(Collections.singletonList(command));
                }
            }));
        } else if (command != null) {
            builder = builder.withInsertHandler((InsertionContext context, LookupElement lookupElement) -> {
                applyInitialTextEdit(item, context, lookupString);

                if (format == InsertTextFormat.Snippet) {
                    context.commitDocument();
                    overrides.prepareAndRunSnippet(lookupString);
                }
                context.commitDocument();
                commandExecutor.accept(Collections.singletonList(command));
            });
        } else {
            builder = builder.withInsertHandler((InsertionContext context, LookupElement lookupElement) -> {
                applyInitialTextEdit(item, context, lookupString);

                if (format == InsertTextFormat.Snippet) {
                    context.commitDocument();
                    overrides.prepareAndRunSnippet(lookupString);
                }
            });
        }
        return builder;
    }

    private void applyInitialTextEdit(CompletionItem item, InsertionContext context, String lookupString) {
        if (item.getTextEdit() != null) {
            // remove intellij edit, server is controlling insertion
            writeAction(() -> {
                Runnable runnable = () -> this.editor.getDocument()
                        .deleteString(context.getStartOffset(), context.getTailOffset());

                CommandProcessor.getInstance()
                        .executeCommand(project, runnable,
                                "Removing Intellij Completion", "LSPPlugin",
                                editor.getDocument());
            });
            context.commitDocument();

            if (item.getTextEdit().isLeft()) {
                item.getTextEdit().getLeft().setNewText(getLookupStringWithoutPlaceholders(item, lookupString));
            }

            editApplier.apply(Integer.MAX_VALUE, Collections.singletonList(item.getTextEdit()), "text edit",
                    false, true);
        } else {
            // client handles insertion, determine a prefix (to allow completions of partially matching items)
            int prefixLength = getCompletionPrefixLength(context.getStartOffset());

            writeAction(() -> {
                Runnable runnable = () -> this.editor.getDocument()
                        .deleteString(context.getStartOffset() - prefixLength,
                                context.getStartOffset());

                CommandProcessor.getInstance()
                        .executeCommand(project, runnable, "Removing Prefix", "LSPPlugin", editor.getDocument());
            });
            context.commitDocument();

        }
    }

    private int getCompletionPrefixLength(int offset) {
        return getCompletionPrefix(this.editor, offset).length();
    }

    @NotNull
    public String getCompletionPrefix(Editor editor, int offset) {
        String delimiterString = String.join("", this.completionTriggers) + " \t\n\r";
        String documentText = editor.getDocument().getText();
        int lastIndex = -1;
        for (char delimiter : delimiterString.toCharArray()) {
            int index = documentText.substring(0, offset).lastIndexOf(delimiter);
            if (index > lastIndex) {
                lastIndex = index;
            }
        }
        return lastIndex >= 0 ? documentText.substring(lastIndex + 1, offset) : documentText.substring(0, offset);
    }

    @SuppressWarnings("WeakerAccess")
    public void prepareAndRunSnippet(String insertText) {

        List<SnippetVariable> variables = new ArrayList<>();
        // Extracts variables using placeholder REGEX pattern.
        Matcher varMatcher = Pattern.compile(SNIPPET_PLACEHOLDER_REGEX).matcher(insertText);
        while (varMatcher.find()) {
            variables.add(new SnippetVariable(varMatcher.group(), varMatcher.start(), varMatcher.end()));
        }
        if (variables.isEmpty()) {
            return;
        }
        variables.sort(Comparator.comparingInt(o -> o.startIndex));
        final String[] finalInsertText = {insertText};
        variables.forEach(var -> finalInsertText[0] = finalInsertText[0].replace(var.lspSnippetText, "$"));

        String[] splitInsertText = finalInsertText[0].split("\\$");
        finalInsertText[0] = String.join("", splitInsertText);

        TemplateImpl template = (TemplateImpl) TemplateManager
                .getInstance(project).createTemplate(finalInsertText[0],
                "lsp4intellij");
        template.parseSegments();

        // prevent "smart" indent of next line...
        template.setToIndent(false);

        final int[] varIndex = {0};
        variables.forEach(var -> {
            template.addTextSegment(splitInsertText[varIndex[0]]);
            template.addVariable(varIndex[0] + "_" + var.variableValue, new TextExpression(var.variableValue),
                    new TextExpression(var.variableValue), true, false);
            varIndex[0]++;
        });
        // If the snippet text ends with a placeholder, there will be no string segment left to append after the last
        // variable.
        if (splitInsertText.length != variables.size()) {
            template.addTextSegment(splitInsertText[splitInsertText.length - 1]);
        }
        template.setInline(true);
        if (variables.size() > 0) {
            EditorModificationUtil.moveCaretRelatively(editor, -template.getTemplateText().length());
        }
        TemplateManager.getInstance(project).startTemplate(editor, template);
        signatureHelpTrigger.run();
    }

    private String convertPlaceHolders(String insertText) {
        return insertText.replaceAll(SNIPPET_PLACEHOLDER_REGEX, "");
    }

    static class SnippetVariable {
        String lspSnippetText;
        int startIndex;
        int endIndex;
        String variableValue;
        String intellijSnippetText;

        SnippetVariable(String text, int start, int end) {
            this.lspSnippetText = text;
            this.startIndex = start;
            this.endIndex = end;
            this.variableValue = getVariableValue(text);
        }

        private String getVariableValue(String lspVarSnippet) {
            if (lspVarSnippet.contains(":")) {
                lspVarSnippet = lspVarSnippet.replace("\\", "");
                return lspVarSnippet.substring(lspVarSnippet.indexOf(':') + 1, lspVarSnippet.lastIndexOf('}'));
            }
            return " ";
        }
    }
}
