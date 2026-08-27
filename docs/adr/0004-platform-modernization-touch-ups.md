# ADR 0004: Platform modernization touch-ups

- Status: Accepted
- Date: 2026-08-26

## Context

`ARCHITECTURE.md` P5 lists deprecated and internal-API usage as a structural risk: `ApplicationComponent`
+ `AppTopics.FILE_DOCUMENT_SYNC`, the deprecated `Annotation` API plus a `(SmartList<Annotation>) holder`
cast, `TemplateManagerImpl`/`HintManagerImpl`/`StartMarkAction`/`Pass` (internal/impl packages),
`groovy.lang.Tuple2`/`Tuple3` as ad hoc data holders, a hand-rolled `KeyFMap` reimplementation in
`LSPPsiElement`, and a `plugin.xml` descriptor with (at the time that section was written) gaps in what
it documented — each a break risk on newer IDE lines, since this library ships with no `untilBuild`
upper bound. Section 3's Phase 4 bullet names six things to fix.

This ADR differs from [[0003-feature-layer-decomposition]] in one respect worth stating up front:
Phase 3's six decision points were all "how do we extract this" — the target was never in doubt, only
the design. Phase 4's items turned out to be mostly "is this actually still true, and is a fix even
possible" — more than half of them, checked against the real current code and the actual 2024.3 platform
jar rather than assumed from the Phase 0 text, turned out to be stale, already fixed by unrelated work,
or resting on a platform API with no public replacement at all. That distinction is the throughline of
the decisions below.

## Decision

### 1. `ApplicationComponent`: deprecated in place, not removed

The literal plan text ("replace `ApplicationComponent`") reads as "delete the interface." Checked
against JetBrains' own SDK docs first: `ApplicationComponent` is deprecated but still functionally
supported at the `sinceBuild=243` baseline, and `docs/developer-guide.md`'s "Appendix: Legacy
components-based setup" documents `IntellijLanguageClient` registered via `<application-components>`
as a supported, if discouraged, path today. Deleting the interface would break plugin *load* for any
consumer still on that path — a different, more serious kind of break than a recompile, and one this
migration has not made anywhere else (every prior phase kept every public method's signature exactly
so downstream plugins kept compiling; principle 5 in `ARCHITECTURE.md` §2.1).

Separately, `resources/plugin.xml.example` (the documented modern setup) already registers the four
listeners `initComponent()` wires up (`LSPEditorListener`, `LSPFileDocumentManagerListener`,
`VFSListener`, `LSPProjectManagerListener`) declaratively, and registers `IntellijLanguageClient` as an
`applicationService` — under which `ApplicationComponent` lifecycle methods never fire. So
`initComponent()`'s body was already 100% dead code for a modern consumer and 100% load-bearing only
for a legacy-appendix one.

Three options (doc-only fix / deprecate-but-keep / remove outright) were presented to the user rather
than picked unilaterally, since this is a compatibility trade-off, not a mechanical modernization.
**Decision: deprecate, don't remove.** `IntellijLanguageClient` still `implements ApplicationComponent,
Disposable`; `initComponent()`/`disposeComponent()` are `@Deprecated` with javadoc explaining they only
run for legacy `<application-components>` consumers. No behavior change for any existing consumer.

A real, unrelated gap found while comparing the two setups: README's Quick Start registered only
`postStartupActivity` and omitted the four listener registrations `plugin.xml.example` has — a new
consumer following just the README got no editor/VFS/doc-sync wiring at all. Fixed in the same commit.

### 2. `LSPAnnotator`: the `doAnnotate`/`apply` split was fixed for real, not just relabeled

The actual defect was worse than "business logic runs in `apply` instead of `doAnnotate`":
`collectInformation`/`doAnnotate` ran validation checks and discarded the result (returning/receiving a
shared sentinel `Object`), and `apply` redid an independent copy of the same validation — by a different
lookup path, URI instead of editor, per the file's own "annotations are applied to a file/document not
to an editor" comment — plus 100% of the diagnostic-to-annotation conversion and the code-action-request
kickoff, entirely on the EDT.

Restructured so `collectInformation` (EDT) only validates and hands `doAnnotate` an immutable
`(VirtualFile, Project)` pair; `doAnnotate` (background thread) does the by-URI lookup, decides
sync-required vs. replay, and converts diagnostics — or reads the cached replay data — into plain
`org.wso2.lsp4intellij.features.LspAnnotation` records; `apply` (EDT) does nothing but turn those
records into `AnnotationBuilder` calls. `LspAnnotation` replaces `List<Annotation>` as
`CodeActionFeature`'s cache and removes the `(SmartList<Annotation>) holder` cast the old code used to
retrieve a just-created `Annotation` back out of a holder — `LSPAnnotator` now rebuilds a fresh builder
from the record on every pass instead. `CodeActionFeature.showCodeActions()` mutates the cached record
in place (`registerFix`) to attach a fix once an async code-action response arrives, the same
"annotation renders before its fix exists" problem the deprecated API solved, without needing it.

Two behavior changes are a forced consequence of no longer having a mutable `Annotation` object to edit
after `.create()`, not separate opportunistic fixes: `ProblemHighlightType.LIKE_DEPRECATED` now applies
on every replay pass (the old code lost it after the first render, since the replay path never
reapplied a highlight type set by mutating the original object); and a diagnostics-computation failure
now skips painting that pass cleanly instead of leaving a partial render (the old code painted
progressively as it iterated, so a mid-iteration exception left whatever had been painted so far).

Public API changes, confirmed with the user first: `CodeActionFeature`/`EditorEventManager`'s
`getAnnotations()`/`setAnnotations(List<Annotation>)` now take/return `List<LspAnnotation>`;
`setAnonHolder(AnnotationHolder)` is replaced by `markAnnotated()` (the retained holder was only ever
null-checked, never called into). No in-repo caller besides `LSPAnnotator` used any of the three, and
their old types were themselves deprecated or annotator-internal. `LSPAnnotator.createAnnotation`
(`protected`, a subclass-override hook with no in-repo overriders) changed from
`(Editor, AnnotationHolder, Diagnostic) -> Annotation` to `(Editor, Diagnostic) -> LspAnnotation` —
flagged rather than re-confirmed, since it's the same risk category as the change just approved.

### 3. Internal-API removal: one real fix, three confirmed unavoidable, one correction

Checked all four classes P5 names against the actual 2024.3 platform jar's public surface before
concluding anything was fixable.

- **`Pass` was never actually internal API.** `com.intellij.openapi.util.Pass` is public (not
  `.impl`), and it is the mandatory parameter type for the platform's own
  `RenamePsiElementProcessorBase.substituteElementToRename(PsiElement, Editor, Pass<? super
  PsiElement>)` — there is no `Consumer`-based overload at 2024.3 to call instead. P5's "internal/impl
  packages" framing grouped it with the other three incorrectly. Left as-is in `LSPRenameHandler`, with
  a comment recording that this was checked, not overlooked.
- **`HintManagerImpl`** (`GUIUtils.createAndShowEditorHint`, `LSPReferencesAction.showReferences`):
  genuinely internal, and genuinely has no public replacement for what these two call sites need — a
  hint positioned at an arbitrary point relative to a logical position with a constraint, specific
  `HIDE_BY_*` flags, and the created `Hint` handle returned so `HoverFeature`/`SignatureHelpFeature` can
  dismiss a stale hint later via `currentHint`. The closest public method,
  `HintManager.showHint(JComponent, RelativePoint, flags, timeout)`, is `void` and uses different
  position semantics — swapping would be a real UX regression, not a mechanical modernization.
- **`StartMarkAction`/`TemplateManagerImpl`** (`LSPRenameHandler`): same situation. No public API
  exposes "is there an in-flight inplace-rename mark action on this editor" or "get the live
  `TemplateState` for this editor" (needed for `.gotoEnd(true)`) — the public `TemplateManager` only
  exposes `getActiveTemplate(Editor)`, a different, more limited object. JetBrains' own built-in
  inplace-rename handlers depend on these same internal classes for exactly this reason.
- **The version-parse hack** (`LSPQuickDocAction`) — the one item that was cleanly fixable, no
  caveats. The condition was `A || (B && C)` with `B` = `Integer.parseInt(getMajorVersion()) > 2017`;
  since `sinceBuild=243` (2024.3+), `B` is provably always `true` for any IDE this plugin can run on,
  so it simplifies to `A || C` with zero behavior change. Removed `B` and the now-unused
  `ApplicationInfo` import.

All three genuinely-internal call sites got an explanatory comment recording that a public replacement
was looked for and doesn't exist, so a future reader doesn't re-litigate this from scratch.

### 4. `groovy.lang.Tuple2`/`Tuple3` → records

Unlike point 3, this one was exactly as clean as planned: both types were plain positional data
holders with a real, direct replacement (Java 17 records — `sourceCompatibility` is already 17), and no
platform method forced their shape the way `Pass` did in point 3.

`Tuple3<HighlightSeverity, TextRange, LSPCodeActionFix>` (the "silent annotations" list shared by
`CodeActionFeature`, `LSPAnnotator`, and `EditorEventManager`'s facade) became
`org.wso2.lsp4intellij.features.SilentAnnotation(severity, range, fix)` — same three fields, now named
instead of positional. `getSilentAnnotations()`'s public signature changed type; same
zero-external-caller situation already established for point 2's `Annotation` → `LspAnnotation` swap,
so not re-confirmed with the user, just flagged. `Tuple2<String, String>`
(`DefaultLanguageClient.progressNotificationItems`) became a private nested
`ProgressNotificationItem(title, message)` record — already a private field with no public exposure,
so no compatibility question at all.

### 5. `LSPPsiElement` PSI cleanups

Checked each of §2.6's four items individually rather than assumed still outstanding:

- **`textMatches` reference equality — already fixed, before this phase.** `git log` on the file found
  commit `863848c` had already landed this. Nothing to do.
- **`UserDataHolderBase` instead of hand-rolled `KeyFMap` — done.** `LSPPsiElement` extended nothing
  before (only interfaces), so `extends UserDataHolderBase implements PsiNameIdentifierOwner,
  NavigatablePsiElement` was a free slot. Verified `UserDataHolderBase`'s actual public/protected
  surface against the 2024.3 jar first — a 1:1 signature match with the hand-rolled block, which was
  clearly modeled on that exact class originally. Deleted ~100 lines
  (`putUserData`/`getUserData`/`getCopyableUserData`/`putCopyableUserData`/`putUserDataIfAbsent`/
  `replace`/`copyCopyableDataTo`/`isUserDataEmpty`/`clearUserData`/`setUserMap`/`changeUserMap` plus
  the backing fields). No caller-visible change at all — every method name/signature callers could
  depend on is unchanged, only the implementation moved from local code to the inherited one.
- **Dead icon ternary — done.** `getIcon(boolean unused) { return (unused) ? null : null; }` (with a
  dangling comment referencing a variable that doesn't exist) simplified to `return null;` — both
  branches already returned the same value.
- **Document the PSI-tree limitation — done.** Added a class javadoc paragraph: `LSPPsiElement` is
  synthetic (no real AST, no children/siblings), so platform features that walk the PSI tree won't
  work against it — only this library's own LSP-backed features are supported. Writes down the
  trade-off `ARCHITECTURE.md` §2.6 already endorses keeping.

### 6. `plugin.xml` descriptor: closed the real gap, corrected the stale claim, removed a second copy

§2.6 calls out "the currently undocumented folding builder, status-bar widget factory, and quick-doc
action." All three were already present in `resources/plugin.xml.example`, added by unrelated earlier
commits (`c83471e` for folding, `2d8194e`/`9a33590` for the rest) — that part of the plan was stale, not
outstanding.

Cross-checking `plugin.xml.example` against `docs/developer-guide.md`'s legacy appendix (which turned
out to be the more complete list for one feature) found the actual gap: the two code-formatting
actions, `LSPReformatAction` and `LSPShowReformatDialogAction`, were documented in the legacy appendix
but missing from `plugin.xml.example` itself. Added both.

The legacy appendix, in turn, independently duplicated the entire extension-point list a second time
and had drifted the other way — missing hover's quick-doc action, folding, the status bar widget, and
the file-sync listeners, while asserting "No additional configuration is required for the other
features." Rather than fix this second copy to match (creating a third future drift opportunity),
replaced its step 2 with a pointer to `plugin.xml.example`'s `<extensions>`/`<actions>`/
`<applicationListeners>` blocks, which apply unchanged regardless of which way `IntellijLanguageClient`
itself is registered. One source of truth for the extension list, not two.

Found but left out of scope: `docs/features.md` doesn't mention folding at all and lists "Signature
Help" as work-in-progress despite it being fully implemented and tested since [[0003-feature-layer-decomposition]]
— feature-documentation accuracy, not the `plugin.xml` descriptor this item is scoped to.

## Consequences

- No public method signature changed except the two narrow, user-confirmed cases in points 2 and 4
  (`getAnnotations`/`setAnnotations`/`setAnonHolder→markAnnotated` on `CodeActionFeature` and
  `EditorEventManager`; `getSilentAnnotations`'s element type) and the flagged `protected
  createAnnotation` hook signature change on `LSPAnnotator`. Every other change in this phase is either
  behavior-preserving or additive (comments, javadoc, a `plugin.xml.example` addition).
- `ApplicationComponent`, `Pass`, `HintManagerImpl`, `StartMarkAction`, and `TemplateManagerImpl` all
  remain in the codebase after this phase — deliberately, each for a documented reason (compat window
  still open; not actually internal API; no public replacement exists; no public replacement exists;
  no public replacement exists). A future IDE line dropping `ApplicationComponent` support entirely, or
  a future platform version adding the missing public APIs the other four would need, would change
  these conclusions — they were verified against 2024.3, not against every future baseline.
- Two behavior changes to `LSPAnnotator` ship as a side effect of removing the deprecated `Annotation`
  API, not as independently requested fixes: deprecated-diagnostic strikethrough styling now survives
  replay passes; a diagnostics-computation failure no longer leaves a partial paint.
- `plugin.xml.example` is now the single documented source for the full extension-point list;
  `docs/developer-guide.md`'s legacy appendix only covers the one thing that differs between the
  legacy and modern setup styles (how `IntellijLanguageClient` itself is registered).

## Rules for new code

1. Before "modernizing" a deprecated or internal API call, verify against the actual current platform
   jar that a public replacement exists and covers the same capability (position control, returned
   handles, callback shape) — don't assume "deprecated" implies "has a drop-in replacement."
2. A comment recording "checked, no public replacement, here's why" is required at a genuinely
   unavoidable internal-API call site, so the next reader doesn't re-investigate from scratch or
   silently "fix" it into a regression.
3. Before doing planned cleanup work, check whether the plan's description still matches the current
   code — `git log` on the file, and a fresh read of the surrounding code, not the original assessment
   text. Several of this phase's six items were already done, already correct, or already something
   else by the time this phase started.
4. When a duplicated document (like the legacy setup appendix) risks drifting from its source of
   truth, point to the source instead of maintaining a second copy — a second copy that already
   drifted once will drift again.
5. Same as [[0003-feature-layer-decomposition]] rule 5: preserve exact behavior when relocating or
   restructuring code; a bug fix or behavior change forced by the restructuring is a separate,
   explicitly called-out decision, not a silent side effect.
