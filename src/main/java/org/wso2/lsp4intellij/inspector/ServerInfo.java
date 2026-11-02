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
package org.wso2.lsp4intellij.inspector;

import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.wso2.lsp4intellij.Lsp4IntellijBundle;
import org.wso2.lsp4intellij.client.languageserver.ServerStatus;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * What the Server Info tab and the log export show for one server: what it reported about itself in
 * its {@code initialize} response, and which of the requests lsp4intellij sends it advertised
 * support for.
 */
final class ServerInfo {

    static final String NONE = "—";

    private static final String EDITING = "inspector.capability.group.editing";
    private static final String NAVIGATION = "inspector.capability.group.navigation";
    private static final String FORMATTING = "inspector.capability.group.formatting";

    /**
     * The capabilities lsp4intellij reads before sending a request, in display order.
     */
    private static final List<Capability> CAPABILITIES = List.of(
            new Capability(EDITING, "completion", "textDocument/completion",
                    ServerCapabilities::getCompletionProvider),
            new Capability(EDITING, "hover", "textDocument/hover", ServerCapabilities::getHoverProvider),
            new Capability(EDITING, "signature.help", "textDocument/signatureHelp",
                    ServerCapabilities::getSignatureHelpProvider),
            new Capability(EDITING, "highlight", "textDocument/documentHighlight",
                    ServerCapabilities::getDocumentHighlightProvider),
            new Capability(EDITING, "code.action", "textDocument/codeAction",
                    ServerCapabilities::getCodeActionProvider),
            new Capability(EDITING, "code.lens", "textDocument/codeLens", ServerCapabilities::getCodeLensProvider),
            new Capability(EDITING, "document.link", "textDocument/documentLink",
                    ServerCapabilities::getDocumentLinkProvider),
            new Capability(EDITING, "rename", "textDocument/rename", ServerCapabilities::getRenameProvider),
            new Capability(EDITING, "folding", "textDocument/foldingRange",
                    ServerCapabilities::getFoldingRangeProvider),
            new Capability(EDITING, "execute.command", "workspace/executeCommand",
                    ServerCapabilities::getExecuteCommandProvider),
            new Capability(NAVIGATION, "definition", "textDocument/definition",
                    ServerCapabilities::getDefinitionProvider),
            new Capability(NAVIGATION, "references", "textDocument/references",
                    ServerCapabilities::getReferencesProvider),
            new Capability(NAVIGATION, "document.symbol", "textDocument/documentSymbol",
                    ServerCapabilities::getDocumentSymbolProvider),
            new Capability(NAVIGATION, "workspace.symbol", "workspace/symbol",
                    ServerCapabilities::getWorkspaceSymbolProvider),
            new Capability(FORMATTING, "formatting", "textDocument/formatting",
                    ServerCapabilities::getDocumentFormattingProvider),
            new Capability(FORMATTING, "range.formatting", "textDocument/rangeFormatting",
                    ServerCapabilities::getDocumentRangeFormattingProvider),
            new Capability(FORMATTING, "on.type.formatting", "textDocument/onTypeFormatting",
                    ServerCapabilities::getDocumentOnTypeFormattingProvider));

    private ServerInfo() {
    }

    /**
     * One labelled value.
     *
     * @param name  the label
     * @param value the value, {@link #NONE} when unknown
     */
    record Row(@NotNull String name, @NotNull String value) {
    }

    /**
     * One request lsp4intellij can send, and whether the server advertised it.
     *
     * @param group     the localized group name, for example "Navigation"
     * @param label     the localized feature name, for example "Go to definition"
     * @param method    the request's JSON-RPC method
     * @param supported whether the server advertised the capability
     */
    record CapabilityRow(@NotNull String group, @NotNull String label, @NotNull String method,
            boolean supported) {
    }

    private record Capability(@NotNull String groupKey, @NotNull String labelKey, @NotNull String method,
            @NotNull Function<ServerCapabilities, Object> provider) {
    }

    /**
     * Returns the version the server reported in its {@code initialize} response, or {@link #NONE}.
     */
    @NotNull
    static String version(@NotNull LanguageServerWrapper wrapper) {
        InitializeResult result = wrapper.getInitializeResult();
        org.eclipse.lsp4j.ServerInfo serverInfo = result == null ? null : result.getServerInfo();
        return orNone(serverInfo == null ? null : serverInfo.getVersion());
    }

    /**
     * Returns the server's reported name, trace level, document sync kind and recent crash count:
     * everything about it except its version, status and definition. Never blocks: before the
     * server has initialized, the values it reports are {@link #NONE}.
     */
    @NotNull
    static List<Row> details(@NotNull LanguageServerWrapper wrapper) {
        InitializeResult result = wrapper.getInitializeResult();
        org.eclipse.lsp4j.ServerInfo serverInfo = result == null ? null : result.getServerInfo();
        ServerCapabilities capabilities = result == null ? null : result.getCapabilities();
        List<Row> rows = new ArrayList<>();
        rows.add(row("inspector.info.name", serverInfo == null ? null : serverInfo.getName()));
        rows.add(row("inspector.info.trace", wrapper.getTraceLevel().name()));
        rows.add(row("inspector.info.sync", syncText(capabilities)));
        rows.add(row("inspector.info.crashes", String.valueOf(wrapper.getCrashCount())));
        return rows;
    }

    /**
     * Returns the server's version, status and definition followed by its {@link #details}, for the
     * export.
     */
    @NotNull
    static List<Row> properties(@NotNull LanguageServerWrapper wrapper) {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(Lsp4IntellijBundle.message("inspector.info.version"), version(wrapper)));
        rows.add(row("inspector.info.status", statusText(wrapper.getStatus())));
        rows.add(row("inspector.info.definition", wrapper.getServerDefinition().toString()));
        rows.addAll(details(wrapper));
        return rows;
    }

    /**
     * Returns one row per request lsp4intellij can send, in display order, with whether
     * {@code capabilities} advertises it. Empty if the server has not initialized yet.
     */
    @NotNull
    static List<CapabilityRow> capabilities(@Nullable ServerCapabilities capabilities) {
        if (capabilities == null) {
            return List.of();
        }
        return CAPABILITIES.stream()
                .map(c -> new CapabilityRow(Lsp4IntellijBundle.message(c.groupKey()),
                        Lsp4IntellijBundle.message("inspector.capability." + c.labelKey()), c.method(),
                        advertised(c.provider().apply(capabilities))))
                .toList();
    }

    /**
     * One {@link ServerStatus} as display text, for example {@code Initialized}. Not localized,
     * matching how the rest of the library renders these constants.
     */
    @NotNull
    static String statusText(@NotNull ServerStatus status) {
        String name = status.name();
        return name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT);
    }

    /**
     * Reads a capability field the way the LSP specification defines them: absent or {@code false}
     * means unsupported, {@code true} or an options object means supported.
     */
    static boolean advertised(@Nullable Object provider) {
        if (provider instanceof Either<?, ?> either) {
            return either.isLeft() ? Boolean.TRUE.equals(either.getLeft()) : either.getRight() != null;
        }
        if (provider instanceof Boolean value) {
            return value;
        }
        return provider != null;
    }

    @Nullable
    private static String syncText(@Nullable ServerCapabilities capabilities) {
        Either<TextDocumentSyncKind, TextDocumentSyncOptions> sync =
                capabilities == null ? null : capabilities.getTextDocumentSync();
        if (sync == null) {
            return null;
        }
        TextDocumentSyncKind kind = sync.isLeft() ? sync.getLeft() : sync.getRight().getChange();
        return kind == null ? null : kind.name();
    }

    @NotNull
    private static Row row(@NotNull String key, @Nullable String value) {
        return new Row(Lsp4IntellijBundle.message(key), orNone(value));
    }

    @NotNull
    private static String orNone(@Nullable String value) {
        return value == null || value.isEmpty() ? NONE : value;
    }
}
