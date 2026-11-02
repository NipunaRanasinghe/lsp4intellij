# Features

LSP4IntelliJ surfaces these LSP capabilities in JetBrains IDEs.

> **Note:** Features are mainly tested on IntelliJ IDEA.

## Table of Contents

<!-- The TOC below is auto-generated. To regenerate, run: npx markdown-toc -i docs/features.md --maxdepth 3 -->

<!-- toc -->

- [Code Completion (with code snippet support)](#code-completion-with-code-snippet-support)
- [Code Formatting](#code-formatting)
- [Diagnostics](#diagnostics)
- [Code Actions](#code-actions)
- [Go to Definition](#go-to-definition)
- [Go to References / Find Usages](#go-to-references--find-usages)
- [Hover Support](#hover-support)
- [Workspace Symbols](#workspace-symbols)
- [Renaming Support](#renaming-support)
- [LSP Inspector](#lsp-inspector)
- [Work in progress](#work-in-progress)

<!-- tocstop -->

---

## Code Completion (with code snippet support)

Press `Ctrl+Space` to see the completion items list, which depends on your cursor position. Code completion items also pop up automatically based on your language-server-specific trigger characters.

![Code completion popup showing language-server-provided suggestions](../resources/images/lsp4intellij-completion.gif)

For code snippets, use `Tab`/`Enter` to navigate to the next placeholder position, or `Esc` to apply the snippet with default values.

![Inserting a code snippet and tabbing through its placeholders](../resources/images/lsp4intellij-snippets.gif)

## Code Formatting

Navigate to **Code → Reformat Code** to open a dialog that lets you format the whole file or a selected range.

![Reformatting a file via the Reformat Code dialog](../resources/images/lsp4intellij-formatting.gif)

## Diagnostics

To see diagnostics (errors, warnings, etc.), hover over them to view the message.

![Hovering over a diagnostic to view the error message](../resources/images/lsp4intellij-diagnostics.gif)

## Code Actions

Hover over any diagnostic highlight to view and apply related code actions via the light bulb that pops up.

![Light bulb showing available code actions for a diagnostic](../resources/images/lsp4intellij-codeactions.gif)

## Go to Definition

Use `Ctrl+Click` (`Cmd+Click` on macOS) to navigate to a symbol's definition.

![Ctrl+Click navigation to a symbol's definition](../resources/images/lsp4intellij-gotodef.gif)

## Go to References / Find Usages

Use `Ctrl+Click` (`Cmd+Click` on macOS) or `Shift+Alt+F7` on a symbol to view its references/usages.

![Listing references and usages of a symbol](../resources/images/lsp4intellij-gotoref.gif)

## Hover Support

Hover over an element while pressing `Ctrl` (`Cmd` on macOS) to view its documentation, if available.

![Hover popup showing documentation for a symbol](../resources/images/lsp4intellij-hover.gif)

## Workspace Symbols

Click **Navigate** in the top menu, then **Symbol…**, and enter the name of the symbol you want to search for.

![Searching for a workspace symbol via the Navigate menu](../resources/images/lsp4intellij-workspacesymbols.gif)

## Renaming Support

Place the cursor on the element to rename and press `Shift+F6` to trigger in-place renaming.

![In-place rename of a symbol triggered with Shift+F6](../resources/images/lsp4intellij-renaming.gif)

## LSP Inspector

Open **View → Tool Windows → LSP Inspector**, or click the language server icon in the status bar and choose **Open LSP Inspector**. If the project runs more than one language server, pick one from the dropdown at the left of the toolbar.

![The LSP Inspector's Log tab showing the server's status changes and traced JSON-RPC messages](../resources/images/lsp4intellij-inspector-log.png)

- **Log** shows the server's status changes, crashes, and the log and show-message notifications it sends. Use the level and source dropdowns to filter it; **Error** shows only crashes, error messages, and failed requests.
- To see JSON-RPC traffic, set **Trace** to `MESSAGES` (method, id and direction of each message) or `VERBOSE` (the full payload as well). Each response shows the method of the request it answers and how long the server took, for example `textDocument/hover (12) 38 ms`. Tracing is `OFF` by default and is set per server.
- **Server Info** shows the server's status, the name and version it reports, its trace level, document sync mode and recent crash count, and its definition (the launch command), with **Copy** and **Restart** buttons. Next to it, **Capabilities** lists the 17 requests LSP4IntelliJ sends, grouped into Editing, Navigation and Formatting, and marks each one the server did not advertise in its `initialize` response. Hover a capability to see its LSP method; select **Unsupported only** to list just the ones the server lacks. If rename or formatting does nothing, check here first.

  ![The Server Info tab showing the server's details and which capabilities it advertises](../resources/images/lsp4intellij-inspector-server-info.png)
- **Requests** shows the requests waiting for a response, with how long each has waited, and the response times per method: count, errors, median, 95th percentile and maximum. Click a column header to sort by it. Times of 300 ms or more are shown in amber and 1 s or more in red. Response times are recorded whether or not tracing is on.

  ![The Requests tab showing response times per method](../resources/images/lsp4intellij-inspector-requests.png)
- **Timeouts** shows, per request type, how many requests got a response within their timeout and how many timed out.
- **Connected Files** lists the files currently open on the server, with their folders. Double-click a file, or select it and press Enter, to open it in the editor.

**Clear** empties the selected server's log, **Export Log** saves it to a file, and **Scroll to End** jumps to the newest entry. The exported file starts with the Server Info details and capabilities, followed by the log with the current level and source filters applied, so it can be attached to a bug report.

> **Note:** The tool window appears only if the plugin using LSP4IntelliJ registers it. See [LSP Inspector](../README.md#lsp-inspector) in the README.

---

## Work in progress

- Signature Help
