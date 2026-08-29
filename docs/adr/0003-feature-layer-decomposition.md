# ADR 0003: Feature-layer decomposition of EditorEventManager

- Status: Accepted
- Date: 2026-08-10

## Context

Before this decision, `EditorEventManager` was 1672 lines implementing hover, completion,
diagnostics, code actions, signature help, navigation, rename, formatting, snippet expansion,
text-edit application, command execution, document-sync wiring, and Swing cursor/hint
manipulation, all in one class (`ARCHITECTURE.md` P4). Consequences:

- Every feature change touched this one file, and the try/catch/timeout/notify pattern around
  requests was duplicated across it (partly addressed by
  [[0001-threading-and-concurrency-model]]'s `RequestExecutor`, but the duplication of the
  surrounding feature logic itself remained).
- Zero tests existed for any of this logic (`ARCHITECTURE.md` P8): the fields a test would need to
  set up or assert on (diagnostics, annotations, completion triggers, the ctrl-range highlight)
  were private and entangled with each other and with `Editor`/`Project` state, with no seam to
  construct or verify one feature in isolation.
- Extending behavior meant subclassing `EditorEventManager` itself — inheriting every other
  feature along with the one being customized.

## Decision

### 1. One class per LSP feature, under `org.wso2.lsp4intellij.features`

Eight classes were extracted, each composed once per editor by `EditorEventManager`'s constructor,
in the exact order `ARCHITECTURE.md` section 3's Phase 3 bullet specifies (diagnostics →
completion → hover/signature → navigation → code actions → rename → formatting), one per commit on
`phase-3-features`:

`DiagnosticsFeature`, `CompletionFeature`, `HoverFeature`, `SignatureHelpFeature`,
`NavigationFeature`, `CodeActionFeature`, `RenameFeature`, `FormattingFeature`.

Each takes exactly the editor/project/wrapper/identifier state it needs and nothing else — no
static lookups, no reference back to the `EditorEventManager` that composes it.

### 2. `EditorEventManager` keeps every public method as an unchanged-signature facade

Every method `EditorEventManager` exposed before this phase — `getDiagnostics`, `completion`,
`createLookupItem`, `getCompletionPrefix`, `quickDoc`, `characterTyped`, `references`,
`gotoLocation`, `codeAction`, `executeCommands`, `requestAndShowCodeActions`, `getSilentAnnotations`,
`triggerIntentionActions`, `rename`, `reformat`, `reformatSelection`, and more — now delegates to
the feature that owns it, with the exact same signature it had before.

This was not optional: unlike Phase 2's `IntellijLanguageClient`/`LanguageServerWrapper` static
facades, callers here reach `EditorEventManager` directly, by concrete type, from all across the
codebase — `LSPAnnotator`, `LSPCompletionContributor`, `LSPTypedHandler`, `LSPQuickDocAction`,
`LSPReferencesAction`, `LSPRenameHandler`/`LSPRenameProcessor`/`LSPInplaceRenamer`,
`LSPCommandFix`/`LSPCodeActionFix`, `ReformatHandler`, `LSPShowReformatDialogAction`, and
`LSPCaretListenerImpl` all call one or more of these methods on an `EditorEventManager` instance,
some also reading the public `completionTriggers` field directly. There is no interface boundary
between them and this class to insulate a signature change.

### 3. Missing shared state: narrow injected callbacks, not a premature `EditorContext`

`ARCHITECTURE.md` section 2.4 describes `EditorContext`, a shared per-editor object features would
depend on for exactly this kind of cross-feature state. It doesn't exist yet — building it was not
in this phase's scope, and speculatively designing it around only eight call sites risked getting
its shape wrong. Where an extracted feature needed a capability that isn't extracted yet, it takes
that capability as a small constructor-injected callback instead:

- **`EditApplier`** (`org.wso2.lsp4intellij.features.EditApplier`, a `@FunctionalInterface`
  matching `EditorEventManager.applyEdit(int, List, String, boolean, boolean)`) — injected into
  `CompletionFeature` and `FormattingFeature`, since text-edit application (the future
  `WorkspaceEditApplier`) is not extracted yet. Started as a nested type inside `CompletionFeature`;
  promoted to a shared top-level interface once `FormattingFeature` needed the identical shape,
  rather than duplicating it.
- **`Consumer<List<Command>>`** matching `executeCommands` — injected into `CompletionFeature`,
  since command execution ended up owned by `CodeActionFeature` (shared with code-action and
  command quick-fixes), not by completion itself.
- **`Runnable`** matching `signatureHelp()` — injected into `CompletionFeature`, called after
  expanding a snippet.
- **`Consumer<Hint>`** — the currently-shown editor hint (`currentHint`) stayed a field on
  `EditorEventManager`, because `mouseMoved`'s hover-dwell logic reads it to hide a stale hint
  before showing a new one. `HoverFeature` and `SignatureHelpFeature` each take a setter callback
  instead of owning the field.

These callbacks are deliberately narrower than a full `EditorContext` reference. When that object
is built in a later phase, each of these constructor parameters should be replaced by a reference
to it rather than kept alongside it.

### 4. Already-extracted features compose each other directly, not through callbacks

Where one feature's logic genuinely depends on another's, and both are already extracted, the
dependent feature takes the other feature object directly as a constructor parameter — ordinary
composition, not a stand-in for anything:

- `CodeActionFeature` takes a `DiagnosticsFeature` (to read the diagnostic context for a code-action
  request, and to force re-annotation after attaching a fix).

`RenameFeature` is the exception: it needs reference lookup, but reaches it through the
`ReferenceLookup` interface rather than a direct `NavigationFeature`, for the dispatch reason in
point 4b.

### 4b. Self-calls that used to be virtual are re-dispatched through the facade

Inside the old single class, some public methods were called unqualified from other methods of the
same class, so they dispatched virtually. `LSPExtensionManager.getExtendedEditorEventManagerFor`
lets an extension supply an `EditorEventManager` subclass, and `LanguageServerWrapper` installs it
for every editor, so an override of one of those methods took effect on the internal path that
called it. The feature classes are `final`, so an ordinary self-call inside one can never reach such
an override — the call would silently run the base implementation while the facade method stayed
overridable, so a signature-level API check would not catch the change.

Six calls were affected. They now go back through the composing `EditorEventManager`, via three
interfaces it implements using the method signatures it already exposed:

- `CompletionOverrides` — `createLookupItem` (called from `completion`),
  `addCompletionInsertHandlers` (from `createLookupItem`), `prepareAndRunSnippet` (from the three
  insert handlers).
- `CodeActionOverrides` — `codeAction` and `resolvedCodeAction` (both from
  `requestAndShowCodeActions`). Neither has any other caller in this codebase; being overridden was
  their only purpose.
- `ReferenceLookup` — `references(int, boolean, boolean)` (from `rename`).

These are interfaces, not an `EditorEventManager` reference, so point 1's rule that a feature holds
no reference back to the class composing it still holds. No recursion results: the facade method
forwards to the feature method that does the work, and that method does not re-enter the same hook.

### 5. Mouse/ctrl-range navigation glue stays in `EditorEventManager`

`mouseMoved`, `mouseClicked`, `createCtrlRange`, `trySourceNavigationAndHover`, and `getPos` were
not extracted. They decide *whether and when* a mouse event should trigger hover, a navigation
preview highlight, or nothing — reading and writing the ctrl-range global slot on
`EditorEventManagerBase` to do it — which is mouse/UI event-routing glue that spans features, not
one LSP feature's logic itself. They now call `hoverFeature.showHoverAt(...)`,
`navigationFeature.definition(...)`, and `navigationFeature.gotoLocation(...)` for the actual work.

`EditorEventManagerBase.getIsCtrlDown()` widened from package-private to public: `HoverFeature`,
now in a different package, needs to read it to decide how to show a hint. It stays a pre-existing
global static flag otherwise (part of the P6 static-state debt `ARCHITECTURE.md` tracks
separately, not something this decomposition redesigns).

### 6. Folding, symbols, and commands were considered and excluded

`ARCHITECTURE.md` section 2.4's target class list also names `FoldingFeature`, `SymbolFeature`, and
`CommandFeature`. Section 3's actual Phase 3 migration-order bullet does not include them — it
lists exactly the eight extractions above. Checked against the current code before treating that as
settled: folding (`LSPFoldingRangeProvider`) and symbols (`LSPSymbolContributor`/
`WorkspaceSymbolProvider`) already live in their own independent contributor classes and were never
part of `EditorEventManager`, so there is no god-class coupling for an extraction to fix there;
commands (`executeCommands`) already moved into `CodeActionFeature` per point 3 above. Moving any
of the three into `org.wso2.lsp4intellij.features` now would rename already-independent code for
consistency alone, which this phase treats as out of scope.

### 7. Extractions preserve behavior, including pre-existing quirks

Each method's body was moved verbatim, not opportunistically cleaned up, consistent with
[[0002-project-and-application-scoped-server-registries]]'s precedent of relocating state without
redesigning it in the same change. Two examples kept as-is: `createLookupItem`'s unused local
`command`/`addTextEdits` variables (recomputed from `item` again inside
`addCompletionInsertHandlers`), and `DiagnosticsFeature`'s locking split (synchronized accessor
methods for reads vs. `synchronized (this.diagnostics)` blocks for the mutation and the
code-action-context read) — a real pre-existing inconsistency, left as found rather than fixed as
an unannounced side effect of the move.

### 7b. One deviation from that: the six accessors' monitor was split in two

`getDiagnostics`, `getAnnotations`, `setAnnotations`, `setAnonHolder`, `isDiagnosticSyncRequired`,
and `isCodeActionSyncRequired` were all `public synchronized` on `EditorEventManager`, so all six
locked the same object: the manager instance. They are now unsynchronized delegating methods, and
the `synchronized` sits on the two feature classes that hold the state — `DiagnosticsFeature`
(`diagnostics`, `syncRequired`) and `CodeActionFeature` (`annotations`, `anonHolder`,
`codeActionSyncRequired`). One monitor became two, so a call in the first group and a call in the
second group can now run at the same time.

That does not break an invariant. The two state groups are disjoint — no field is read or written by
both — so every pair of calls that stopped excluding each other operates on different fields. The
exclusion between the groups came from both groups happening to use `this` as their monitor, not
from anything either group needed from the other. The modifier also only ever made a single call
atomic, never a sequence of them: `LSPAnnotator` reads the diagnostics, builds annotations from
them, then calls `setAnnotations` and `setAnonHolder`, and the lock was released between each of
those calls before this change too.

What the split does remove is the ability of code outside this class to write
`synchronized (manager) { ... }` and block these accessors, and a subclass's `super.getAnnotations()`
no longer acquires the manager's monitor. Nothing in this repository does either; a downstream
plugin could. The modifier was not restored on the delegating methods, because it guards no
invariant and would add a second lock acquisition to a path `LSPAnnotator` runs on every
`DaemonCodeAnalyzer` pass. Callers should treat each accessor as individually safe to call from any
thread and not assume a sequence of them is atomic — which was already true before this change.

For the same reason, note that `CodeActionFeature.showCodeActions` reads and assigns `annotations`
with no lock at all (`if (annotations == null) { annotations = new ArrayList<>(); }`) while the
accessors beside it are `synchronized`. That is pre-existing and was moved verbatim under point 7,
and it means the annotation state was never fully protected by the manager's monitor either.

### 7c. Three behavior changes that are not verbatim relocations

Rule 5 below requires a bug fixed during an extraction to be called out rather than folded silently
into the move. Three such changes are in this phase, each in its own commit:

- **`NavigationFeature.references(...)` returns empty lists instead of `new Pair<>(null, null)` when
  the server reports no references.** `LSPInplaceRenamer.collectRefs()` streams the first list and
  hands the second to `LSPRenameProcessor.addEditors()`, which calls `addAll` on it; both threw a
  `NullPointerException` on the old null result. The four callers that do check for null
  (`LSPRenameHandler`, `LSPReferencesAction`, `LSPRenameProcessor`, `RenameFeature`) treat an empty
  list the same way they treated null — `LSPReferencesAction` still reports "No references found".
- **`SignatureHelpFeature` guards the active-signature and active-parameter indices.** Both are
  nullable `Integer` in the LSP spec ("if omitted... defaults to zero") and a server may also send an
  out-of-range value; the old code unboxed them directly and indexed with them, so either case threw.
  Out of range or absent now falls back to `0`, and a valid `0` is still honored.
- **`SignatureHelpFeature` also null-checks `activeSignature.getParameters()`.** This one is a
  consequence of the guard above rather than a separately intended fix: when a server omits
  `parameters`, the old code threw, was caught, logged a warning, and showed no hint at all; it now
  renders the signature with an empty active parameter, which is the same path the old code took
  whenever the parameter index was merely out of range.

Everything else in this phase is a verbatim relocation per point 7. The two decisions in points 4b
and 7b are also not verbatim, but they are consequences of moving code into `final` classes in
another package, not fixes to the logic itself.

### 8. Tests were added only where a feature has an isolable, pure part

A unit test was added per feature only where genuine pure logic existed that needed neither a
running language server nor a real `Editor`'s UI behavior: `DiagnosticsFeatureTest`,
`CompletionFeatureTest` (scoped to `getCompletionPrefix`), `SignatureHelpFeatureTest` (scoped to
the trigger-character gate), `CodeActionFeatureTest` (scoped to the annotation/sync-flag
bookkeeping). `HoverFeature`, `NavigationFeature`, `RenameFeature`, and `FormattingFeature` have no
such test: every one of their methods needs the wrapper's request manager (a running server) or
real editor navigation/selection behavior. `LspServerIntegrationTest` does not reach any of these
request paths (it covers only open/didOpen, edit/didChange, and close/didClose/shutdown) — this is
a pre-existing coverage gap this phase did not introduce and was not scoped to close.

`CodeActionFeature` has one package-private method that exists only for its test:
`markCodeActionSyncRequiredForTest()`. Every production path that raises `codeActionSyncRequired`
sits inside `showCodeActions`, which needs a real editor and a server response, so without the seam
the flag can only ever be observed in its initial `false` state — and a test asserting it is `false`
after `getAnnotations()` would pass even if `getAnnotations()` stopped clearing it. Both tests that
cover this bookkeeping were verified by mutation: removing the flag clear from `getAnnotations()`,
and making `getSilentAnnotations()` return a defensive copy instead of the live list, each fail the
corresponding test. Prefer a package-private seam over widening public API when a flag is otherwise
unobservable, and check that a new test fails against the defect it is meant to catch.

Note that `checkstyleTest` is disabled in `build.gradle`, so test sources are not style-checked;
"checkstyle passes" for this phase means `checkstyleMain`.

## Consequences

- `EditorEventManager` shrank from 1672 to 884 lines. What remains is intentionally not
  feature-layer material yet: the mouse/ctrl-range glue (point 5), `applyEdit`/`getEditsRunnable`/
  `LSPTextEdit` (the future `WorkspaceEditApplier`, referenced via `EditApplier` in the meantime),
  and document-lifecycle delegation to `DocumentEventManager` (`documentOpened`/`Closed`/`Changed`/
  `Saved`, `willSave`/`willSaveWaitUntil` — the future `DocumentSynchronizer` from section 2.3,
  never in this phase's scope).
- No public method signature changed, so every downstream plugin calling `EditorEventManager`,
  `IntellijLanguageClient`, or `LanguageServerWrapper` still compiles. Two behavioral details do
  differ: the six accessors in point 7b no longer lock the manager instance, and a subclass override
  of a method listed in point 4b now takes effect only because the call was routed back through the
  facade — an ordinary self-call inside a `final` feature class would have skipped it.
- The `EditApplier`/command/signature-help/hint callbacks introduced in point 3 are scaffolding,
  not a final design — expected to be replaced by a real `EditorContext` reference once a later
  phase builds one, per Rule 3 below.
- `LSPFoldingRangeProvider` and `LSPSymbolContributor`/`WorkspaceSymbolProvider` are unchanged by
  this ADR; nothing about them was judged broken.

## Rules for new code

1. New LSP feature logic is added to its own class under `org.wso2.lsp4intellij.features`, not to
   `EditorEventManager`.
2. Before removing or changing the signature of any `EditorEventManager` public method, check for
   direct external callers first — contributors, actions, and quick-fixes call it by concrete type
   across the codebase, not through an interface, so a signature change is a compile break for them,
   not a caught-by-the-compiler-in-one-place change.
3. When a new feature needs a capability that isn't extracted yet, inject it as a narrow callback
   (the `EditApplier` pattern), rather than reaching back into `EditorEventManager`'s private state
   or duplicating the logic. When `EditorContext` is eventually built, replace these callbacks with
   a reference to it rather than keeping both.
4. When one already-extracted feature depends on another, compose the dependency directly as a
   constructor parameter (peer composition), not through `EditorEventManager`.
5. Preserve exact behavior, including pre-existing quirks, when relocating code out of
   `EditorEventManager`. Fixing a bug noticed during an extraction is a separate, explicitly called
   out decision, not a silent side effect of moving the code.
6. Add a unit test for a new feature's pure/isolable logic; when a feature has none because every
   path needs a running server or real editor UI behavior, say so in its javadoc rather than
   skipping the test silently.
