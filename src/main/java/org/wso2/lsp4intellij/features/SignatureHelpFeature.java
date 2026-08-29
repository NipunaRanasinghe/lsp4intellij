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

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.ui.Hint;
import com.intellij.util.ui.UIUtil;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.ParameterInformation;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.SignatureInformation;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Tuple;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;
import org.wso2.lsp4intellij.utils.DocumentUtils;

import java.awt.Font;
import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.wso2.lsp4intellij.requests.Timeouts.SIGNATURE;
import static org.wso2.lsp4intellij.utils.ApplicationUtils.invokeLater;
import static org.wso2.lsp4intellij.utils.GUIUtils.createAndShowEditorHint;

/**
 * Calls signature help at the current caret position and shows the active signature as a hint,
 * and decides whether a just-typed character should trigger that request.
 *
 * <p>Extracted from {@code EditorEventManager} as part of the feature-layer decomposition
 * (ARCHITECTURE.md section 2.4); {@code EditorEventManager} composes one instance per editor and
 * keeps {@code characterTyped}/{@code signatureHelp} as public delegating facades, since
 * {@code LSPTypedHandler} calls {@code characterTyped} directly and {@code CompletionFeature}
 * calls {@code signatureHelp} after expanding a snippet.
 *
 * <p>The currently shown hint is still tracked by {@code EditorEventManager} (see
 * {@link HoverFeature}'s equivalent note), so it's injected here as a setter callback too.
 */
public final class SignatureHelpFeature {

    private static final Logger LOG = Logger.getInstance(SignatureHelpFeature.class);

    private final Editor editor;
    private final LanguageServerWrapper wrapper;
    private final TextDocumentIdentifier identifier;
    private final List<String> signatureTriggers;
    private final Consumer<Hint> hintSetter;

    public SignatureHelpFeature(Editor editor, LanguageServerWrapper wrapper, TextDocumentIdentifier identifier,
            List<String> signatureTriggers, Consumer<Hint> hintSetter) {
        this.editor = editor;
        this.wrapper = wrapper;
        this.identifier = identifier;
        this.signatureTriggers = signatureTriggers;
        this.hintSetter = hintSetter;
    }

    /**
     * Calls onTypeFormatting or signatureHelp if the character typed was a trigger character.
     *
     * @param c The character just typed
     */
    public void characterTyped(char c) {
        if (signatureTriggers.contains(Character.toString(c))) {
            signatureHelp();
        }
    }

    /**
     * Calls signatureHelp at the current editor caret position.
     */
    @SuppressWarnings("WeakerAccess")
    public void signatureHelp() {
        if (editor.isDisposed()) {
            return;
        }
        LogicalPosition lPos = editor.getCaretModel().getCurrentCaret().getLogicalPosition();
        Point point = editor.logicalPositionToXY(lPos);
        SignatureHelpParams params = new SignatureHelpParams(identifier, DocumentUtils.logicalToLSPPos(lPos, editor));
        wrapper.pool(() -> {
            CompletableFuture<SignatureHelp> future = wrapper.getRequestManager().signatureHelp(params);
            SignatureHelp signatureResp = wrapper.getRequestExecutor().waitFor(future, SIGNATURE);
            if (signatureResp == null) {
                return;
            }
            try {
                List<SignatureInformation> signatures = signatureResp.getSignatures();
                if (signatures == null || signatures.isEmpty()) {
                    return;
                }
                // getActiveSignature()/getActiveParameter() are nullable Integers per the LSP spec
                // ("if omitted... defaults to zero"), and a server may also send an out-of-range value;
                // unboxing a null or indexing out of range would otherwise throw here.
                Integer activeSignatureBoxed = signatureResp.getActiveSignature();
                int activeSignatureIndex = activeSignatureBoxed == null
                        || activeSignatureBoxed < 0 || activeSignatureBoxed >= signatures.size()
                        ? 0 : activeSignatureBoxed;
                Integer activeParameterBoxed = signatureResp.getActiveParameter();
                int activeParameterIndex = activeParameterBoxed == null || activeParameterBoxed < 0
                        ? 0 : activeParameterBoxed;

                SignatureInformation activeSignature = signatures.get(activeSignatureIndex);
                List<ParameterInformation> parameters = activeSignature.getParameters();
                String activeParameter =
                        parameters != null && parameters.size() > activeParameterIndex
                        ? extractLabel(activeSignature, parameters.get(activeParameterIndex).getLabel())
                        : "";
                Either<String, MarkupContent> signatureDescription =
                        activeSignature.getDocumentation();
                StringBuilder builder = new StringBuilder();
                Font font = UIUtil.getLabelFont();
                MutableDataSet options = new MutableDataSet();
                Parser parser = Parser.builder(options).build();
                HtmlRenderer renderer = HtmlRenderer.builder(options).build();
                builder.append("<html>");
                builder.append(UIUtil.getCssFontDeclaration(font));
                List<String> result = new ArrayList<>();
                if (!signatures.isEmpty() && signatures.get(activeSignatureIndex).getParameters() != null) {
                    for (ParameterInformation param : signatures.get(activeSignatureIndex).getParameters()) {
                        Either<String, MarkupContent> doc = param.getDocumentation();
                        if (doc.isRight()) {
                            result.add(renderer.render(parser.parse(doc.getRight().getValue())));
                        }
                    }
                }
                if (signatureDescription == null) {
                    builder.append("<code>").append(signatures.get(activeSignatureIndex).getLabel().
                            replace(" " + activeParameter, String.format("<font color=\"orange\"> %s</font>",
                                    activeParameter))).append("</code>");
                } else if (signatureDescription.isLeft()) {
                    String description = signatureDescription.getLeft().replace(System.lineSeparator(), "<br />");
                    builder.append("<code>").append(signatures.get(activeSignatureIndex).getLabel()
                            .replace(" " + activeParameter, String.format("<font color=\"orange\"> %s</font>",
                                    activeParameter))).append("</code>");
                    builder.append("<p>").append(description).append("</p>");
                } else if (signatureDescription.isRight()) {
                    String string = renderer.render(parser.parse(signatures.get(activeSignatureIndex).getLabel()));
                    builder.append("<code>").append(string).append("</code>");
                }
                if (!result.isEmpty()) {
                    // "<br/>", not a line-separator character: this joins already-rendered HTML
                    // fragments (one per parameter's documentation), and a raw newline between two
                    // HTML blocks is collapsed by the renderer either way - only a real HTML break
                    // separates them visually, same reasoning as the getLeft() branch above.
                    builder.append("<div>").append(String.join("<br/>", result)).append("</div>");
                }
                builder.append("</html>");
                invokeLater(() -> hintSetter.accept(createAndShowEditorHint(
                        editor, builder.toString(), point,
                        HintManager.UNDER, HintManager.HIDE_BY_OTHER_HINT)));

            } catch (Exception e) {
                LOG.warn("Internal error occurred when processing signature help", e);
            }
        });
    }

    private String extractLabel(SignatureInformation signatureInformation,
            Either<String, Tuple.Two<Integer, Integer>> label) {
        if (label.isLeft()) {
            return label.getLeft();
        } else if (label.isRight()) {
            return signatureInformation.getLabel().substring(label.getRight().getFirst(), label.getRight().getSecond());
        } else {
            return "";
        }
    }
}
