# LSP Inspector — implementation plan

Status: proposed, not started
Base branch: `phase-4-platform-modernization`
Work branch: `feature/lsp-inspector`

## Goal

Give users of a plugin built on lsp4intellij a way to see what their language server is
doing: the messages it logs, the lifecycle transitions it goes through, and — when
switched on — every JSON-RPC message exchanged in both directions. Today all of this
either goes to `idea.log` or is discarded.

## What exists today

| Source | Current handling | Location |
| --- | --- | --- |
| `window/logMessage` | mapped to `Logger.error` / `warn` / `info` | `DefaultLanguageClient.java:288` |
| `window/showMessage` | balloon notification, or modal dialog when `isModal` | `DefaultLanguageClient.java:162` |
| Every JSON-RPC message, both directions | passes a `MessageConsumer` wrapper that only inspects messages for `InitializeResult` | `MessageHandler.java:41` |
| Server process stderr | `ProcessBuilder.Redirect.INHERIT` — written to the IDE's own stderr | `ProcessStreamConnectionProvider.java:80` |
| Server status transitions | update the status bar widget only | `LanguageServerWrapper.setStatus` |

Two facts about this repository shape the design:

1. **lsp4intellij is a library that other plugins embed, not a standalone plugin.** It
   ships no `plugin.xml` (the one under `src/main/resources/META-INF/` is untracked local
   test scaffolding). Every UI-facing class is registered by the consumer plugin in its own
   `plugin.xml`. The inspector must therefore work with no tool window registered at all.
2. **`MessageHandler` is already the single point every message passes through**, and
   Phase 4's `LspServerManager` establishes the project-scoped `@Service` + `Disposable`
   pattern that the log store follows.

## Scope

In scope for v1:

- `window/logMessage` and `window/showMessage` notifications, with their message level.
- Server lifecycle transitions (`STOPPED`, `STARTING`, `STARTED`, `INITIALIZED`,
  `STOPPING`) and crashes.
- JSON-RPC trace of both directions, off by default, switchable per server to
  method-and-id or full-payload.
- A tool window showing the above per server, filterable by level and by source.

Out of scope for v1, with reasons:

- **Server process stderr.** Capturing it means changing `Redirect.INHERIT` to `PIPE` and
  draining the pipe on a pooled thread, otherwise a chatty server fills the OS buffer and
  blocks. It also changes behavior for consumers who supply their own `ProcessBuilder` via
  `ProcessBuilderServerDefinition`. Worth doing, but after the store and UI are proven.
- **Removing the existing `idea.log` output.** `DefaultLanguageClient.logMessage` calls
  `LOG.error` for server errors today. v1 leaves that in place so existing consumers see no
  change; server errors will appear both in the inspector and in `idea.log`. Routing them to
  the inspector only is a behavior change that needs its own decision.
- **Persisting the trace level.** Trace level is per server and lives only for the IDE
  session. No `PersistentStateComponent` until there is evidence people want it to survive
  a restart.

## Design

Two packages, so the capture layer never depends on Swing and keeps working when no tool
window is registered.

### `org.wso2.lsp4intellij.logging` — capture, always active

| File | Role |
| --- | --- |
| `LspLogEntry` | record: timestamp millis, server id, server label, `LspLogSource`, `MessageType` level, summary line, nullable detail holding raw JSON |
| `LspLogSource` | enum: `SERVER_LOG`, `SERVER_MESSAGE`, `LIFECYCLE`, `TRACE_OUT`, `TRACE_IN` |
| `LspTraceLevel` | enum: `OFF`, `MESSAGES`, `VERBOSE` |
| `LspLogStore` | `@Service(Service.Level.PROJECT)`, `Disposable`. Holds one bounded deque per `LanguageServerWrapper`, capacity 10000, oldest entry evicted on overflow. Publishes each entry on a `MessageBus` topic |
| `LspLogListener` | `MessageBus` topic interface: `entryAdded(LanguageServerWrapper, LspLogEntry)`, `serverCleared(LanguageServerWrapper)` |
| `TracingMessageConsumer` | `MessageConsumer` decorator that turns each message into an entry |

`LspLogStore` is looked up with `getServiceIfCreated` on read paths, matching the contract
`LspServerManager` documents: a project can close between a caller's check and the lookup,
and `Project.getService` throws on a disposed project.

A buffer outlives the server that filled it: stopping or disposing a wrapper leaves its
entries in place, since a server that has just died is the one whose log someone wants to
read. All buffers are dropped when the project is disposed. The cost is that a buffer keeps
its drained wrapper reachable until the project closes — one object per server per session.

### `org.wso2.lsp4intellij.inspector` — UI, opt-in

- `LspInspectorToolWindowFactory` — implements `ToolWindowFactory` and `DumbAware`.
- `LspInspectorPanel` — a `JBSplitter`. Left: a `SimpleTree` of server definition nodes with
  the running wrappers beneath each. Right: one `ConsoleView` per node, created lazily.
- Toolbar actions: level filter, source filter, trace level, clear, scroll to end.

`ConsoleView` gives search, scroll-lock and native appearance without extra code. Filtering
re-prints the selected server's buffer, which is affordable because the buffer is bounded.

```
┌─ LSP Inspector ────────────────────────────┐
│ Servers      │ [Level ▾][Source ▾][🔍][⨯][⇩] │
│ ▾ ballerina  │ 12:04:11 INFO  server started │
│   • proj-a   │ 12:04:12 →     textDocument/. │
│   • proj-b   │ 12:04:12 ←     result: 3 items│
│ ▸ gopls      │ 12:04:19 ERROR parse failed   │
└────────────────────────────────────────────┘
```

### Changes to existing code

1. **`ClientContext`** — add `default @Nullable LanguageServerWrapper getWrapper() { return null; }`.
   `DefaultLanguageClient` holds a `ClientContext` and needs the wrapper to attribute an
   entry to a server. `ServerWrapperBaseClientContext` already has this method
   (`ServerWrapperBaseClientContext.java:50`); it gains an `@Override`. A `default` method
   means consumers who implement `ClientContext` themselves are unaffected.

2. **`DefaultLanguageClient.logMessage` and `showMessage`** — append to the store in
   addition to the existing `Logger` and notification behavior.

3. **`LanguageServerWrapper.setStatus` and `crashed`** — append `LIFECYCLE` entries.

4. **`MessageHandler.apply`** — compose `TracingMessageConsumer` into the returned
   consumer. lsp4j calls this wrapper exactly twice while building the launcher: once
   around the `StreamMessageConsumer` that writes to the output stream (client to server)
   and once around the `RemoteEndpoint` that handles input (server to client). Direction is
   read from the type of the consumer being wrapped rather than inferred from the message.

   The existing guard that drops messages once the wrapper is no longer running stays as it
   is; trace entries are only produced for messages that are actually passed on.

5. **`$/setTrace`** — sent to the server when the level changes, so servers that implement
   it contribute their own trace output. It is sent after initialize only when the level is
   not `OFF`: sending it unconditionally would put a notification on the wire for every
   server at startup that it did not receive before this feature existed, which would break
   the "no behavior change when the inspector is unused" criterion below.

### Cost when tracing is off

`TracingMessageConsumer` reads a `volatile LspTraceLevel` and returns immediately when it
is `OFF`, before touching the message. No JSON is serialized and no object is allocated on
that path. `OFF` is the default for every server.

## Known constraints and open items

Carried forward from milestone 1. Read this before starting milestone 2.

### The platform's bundled lsp4j shadows the declared 0.23.1 dependency

IntelliJ IDEA Community 2024.3 ships its own older copy of `org.eclipse.lsp4j.jsonrpc`
inside `lib/lib.jar` for its native LSP support, and that copy wins over the 0.23.1 jar this
project declares. Verified with `javap` on both jars: the bundled
`org.eclipse.lsp4j.jsonrpc.messages.Message` has only `getJsonrpc`, `setJsonrpc` and
`toString`, while the 0.23.1 copy also has `jsonHandler()`, `setJsonHandler()` and
`toStringFallback()`.

Effect on this feature: `Message.jsonHandler()` would give the exact wire JSON for the
verbose detail column, and it is unreachable. `TracingMessageConsumer.serialize()` falls back
to `Message.toString()`. Milestone 2 must not assume exact wire JSON is available.

Effect beyond this feature, unresolved: the library may not be compiling or running against
the lsp4j version it declares wherever the platform bundles the same class. That is a latent
`NoSuchMethodError` risk anywhere a post-bundled-version lsp4j API is used, and it deserves
its own investigation independent of the inspector.

### `entryAdded` is published on the JSON-RPC dispatch thread

`LspLogStore.append` calls `syncPublisher(...).entryAdded(wrapper, entry)` on whichever thread
produced the entry, which for trace entries is an lsp4j dispatch thread. The milestone 2
listener must marshal to EDT and coalesce: a verbose trace produces entries far faster than
Swing can repaint, and one repaint per entry will stall the UI.

### Log buffers keep their wrapper reachable

`LspLogStore` keys buffers by `LanguageServerWrapper` identity and does not drop a buffer
when its wrapper is disposed, so the log of a server that just died stays readable. A
retained buffer is removed when a new wrapper for the same `definition.ext` records its
first entry, so at most one stopped instance per server is kept. Each buffer is limited to
`CAPACITY` entries and `MAX_CHARS` characters of summary and detail text.

## Milestones

1. **Capture layer, including trace.** Everything in `org.wso2.lsp4intellij.logging`:
   `LspLogEntry`, `LspLogSource`, `LspTraceLevel`, `LspLogStore`, `LspLogListener` and
   `TracingMessageConsumer`; plus all five wiring changes to existing code — `ClientContext`,
   `DefaultLanguageClient`, `LanguageServerWrapper`, the `MessageHandler` composition and
   `$/setTrace`. Unit tests. No UI, so the whole milestone is verifiable headlessly.

   Trace is folded in here rather than kept separate because it is the same headless layer,
   and because its one risky assumption — that lsp4j calls the `MessageHandler` wrapper
   twice, once per direction, in a known order — is better tested before any UI depends on
   it.
2. **Tool window.** `LspInspectorToolWindowFactory`, `LspInspectorPanel`, toolbar actions,
   icons.
3. **Docs and manual verification.** `docs/features.md`, README, and a documented
   `<toolWindow>` entry in `resources/plugin.xml.example`. Manual check with
   `./gradlew runIde` against the Ballerina language server, using the existing untracked
   test-host scaffolding.

## Tests

- `LspLogStoreTest` — eviction at capacity, isolation between wrappers, listener fan-out,
  buffer removal on server stop, buffer removal on project dispose, `getServiceIfCreated`
  returning null for a disposed project.
- `TracingMessageConsumerTest` — `OFF` produces no entries; `MESSAGES` records method, id
  and direction; `VERBOSE` records the payload; direction is correct for each of the two
  wrapped consumer types; the decorated consumer still forwards every message.
- `DefaultLanguageClientTest` additions — a `window/logMessage` at each level produces one
  entry with the matching level, and the existing `Logger` call still happens.

## Success criteria

- With no tool window registered by the consumer plugin: no behavior change, and no
  measurable overhead while trace level is `OFF`.
- With the tool window registered: a server's `window/logMessage` output, its lifecycle
  transitions, and — once trace is switched on — its full JSON-RPC traffic are visible,
  filterable by level and source, and bounded in memory.
- Existing tests pass, and the new tests above pass.
