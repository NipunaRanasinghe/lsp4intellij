# ADR 0004: `ApplicationComponent` deprecation and `LSPAnnotator` threading fix

- Status: Accepted
- Date: 2026-08-26

## Context

`ARCHITECTURE.md` P5 lists deprecated and internal-API usage as a structural risk, and section 3's
Phase 4 bullet names six items to address it. Checking each against the real current code and the
actual 2024.3 platform jar (rather than the Phase 0 assessment text) found only two with genuine
architectural decision content — a design alternative weighed, a contract actually changed, lasting
consequences for how new code should be written:

- `IntellijLanguageClient`'s `ApplicationComponent` implementation, and whether removing it is safe.
- `LSPAnnotator`'s violation of the `ExternalAnnotator` threading contract.

The other four items in Phase 4 (an internal-API audit that mostly concluded "no public replacement
exists, leave as-is"; `groovy.lang.Tuple2`/`Tuple3` replaced with records; `LSPPsiElement` extending
`UserDataHolderBase` instead of hand-rolling the same thing; completing the `plugin.xml` descriptor
docs) are recorded in `ARCHITECTURE.md`'s Phase 4 progress notes and the PR body instead — each is
either a "verified, no action needed" investigation or a mechanical, alternative-free change with no
tradeoff to record. This ADR covers only the two with an actual decision in them.

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

This is also, in effect, the first application of the compatibility policy [[0002-project-and-application-scoped-server-registries]]
established but never exercised: "Deprecation is deferred to whichever phase actually ships a
replacement" (that ADR's point 3). Here, the declarative extension points *are* the replacement, and
have existed since before this phase — so this is the first `@Deprecated` this migration has actually
applied to a facade.

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

## Consequences

- No public method signature changed except the two narrow, user-confirmed cases in point 2
  (`getAnnotations`/`setAnnotations`/`setAnonHolder→markAnnotated` on `CodeActionFeature` and
  `EditorEventManager`) and the flagged `protected createAnnotation` hook signature change on
  `LSPAnnotator`.
- `ApplicationComponent` remains in the codebase after this phase, deliberately — the compat window is
  still open for legacy `<application-components>` consumers. A future IDE line actually removing
  platform support for it would change this conclusion; it was verified against 2024.3, not against
  every future baseline.
- Two behavior changes to `LSPAnnotator` ship as a side effect of removing the deprecated `Annotation`
  API, not as independently requested fixes: deprecated-diagnostic strikethrough styling now survives
  replay passes; a diagnostics-computation failure no longer leaves a partial paint.

## Rules for new code

1. Before removing a deprecated-but-still-functional platform API entirely, verify against the
   platform's actual current behavior (not just its deprecation status) whether removing it would
   break plugin *load* for an existing registration style, not merely require a recompile. If so,
   deprecate in place instead and treat outright removal as a separate, explicitly-approved decision.
2. `ExternalAnnotator` subclasses: `collectInformation` and `doAnnotate` must do real work and return a
   real result — `apply` should only turn that result into `AnnotationHolder` calls, never redo
   validation or compute what to render itself.
3. When a class needs a mutable, pre-render cache entry that a later async response can attach data to
   (the annotation-then-quick-fix pattern here), use a plain data class, not the deprecated `Annotation`
   type or a reflection-based holder cast.
4. Same as [[0003-feature-layer-decomposition]] rule 5: preserve exact behavior when restructuring
   code; a bug fix or behavior change forced by the restructuring is a separate, explicitly called-out
   decision, not a silent side effect.
