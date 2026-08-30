# Stepper Guide

Use this template for a step-by-step guided walkthrough where the reader advances through ordered stages — especially when the underlying subject is **cyclic** (a loop, an event loop, a recurring process) and drawing it as a ring or cycle diagram would be wrong.

Source inspiration: the external `可视化模板/03-交互组件模板.html` stepper and `09-代码片段速查.md` stepper. Adapted to the Dynamic UI chat-widget contract (token colors, scoped root, `data-widget-theme`, no `display:none`, reduced-motion).

## Use When

- A process has a small ordered set of steps (2-6) that the reader advances through one at a time.
- The subject is a **loop / cycle / recurring flow** (a Krebs cycle, an event loop, a refresh cycle) that a linear-or-ring static SVG would misrepresent.
- A stepper with a `● ○ ○` position indicator and prev/next controls makes the order and current stage clearest.
- The widget needs exactly one local control concept (advance/rewind). No other independent controls may be added.

## Avoid When

- The steps are better scanned as one static visual or Markdown list.
- The reader needs to jump to arbitrary steps, edit, branch, or reorder — use a tree-flow or Markdown.
- There are more than 6 steps — group phases or summarize in text.
- The steps depend on external APIs, persistence, or routing.
- The process is not naturally a sequence (use architecture-and-flow or data-visualization instead).

## Data Shape

```json
{
  "title": "Release pipeline",
  "positionLabel": "Step 1 of 3",
  "steps": [
    { "title": "Build", "body": "Compile and lint the source, run unit tests, produce an artifact." },
    { "title": "Stage", "body": "Promote the artifact to a staging environment and run integration checks." },
    { "title": "Deploy", "body": "Release to production with a rolling update and health validation." }
  ]
}
```

Steps are ordered; `steps[0]` is the initial default view. The indicator shows the current step as a filled dot and the rest as outline dots.

## Visual Rules

- Use neutral surfaces for the panel; the active dot uses `--chart-series-1` (or `--brand` for the focal step).
- Use token typography: step title `--text-body`/medium; body `--text-body`; position label `--text-caption`/muted.
- Use `--surface` for the panel and `--surface-muted` for controls; borders use `--border`.
- Use spacer tokens for gaps/padding; `--radius` for controls, `--radius-full` for dots.
- The panel must not exceed the compact widget budget (target height ≤340px).
- Do not draw the cycle as a ring or a return arc; the stepper's wrap-around is the correct encoding for a loop.
- The body area keeps a stable reserved height so the panel does not jump when the step text changes.

## Required Interaction

- Render the first step fully visible before JavaScript runs (no `display:none` on the initial state).
- Advance (`Next`) and rewind (`Prev`) controls; advancing from the last step wraps back to the first (matches cyclic behavior); rewinding from the first wraps to the last.
- Dots indicate the current step; updating them is part of the interaction.
- Use semantic `<button>` elements with `aria-label` (e.g. "Previous step", "Next step") and `aria-live="polite"` on the step body so screen readers announce changes.
- Bind only in the final `<script>` via the stable root selector; no inline handlers.
- Wrap button/panel transitions in `@media (prefers-reduced-motion: no-preference)`; use `opacity`/`transform`, max 150ms.

## Edge Cases

- Empty `steps`, or step body text missing: fall back to a static readable step title.
- Very long step text: let the body wrap within the reserved area rather than shrinking font or using fixed clipping.
- If JavaScript fails, the first step (static default) stays readable; only advance/rewind is lost.

## Implementation Assumptions

- The full token `<style>` header (light + dark + media fallback) is included verbatim.
- Data is small and embedded directly in the fragment.
- Steps are ordered; the widget wraps around to express a loop.

## Acceptance Checks

- `widget-code.html` starts with `<style>`.
- Root is `.widget` with `data-dynamic-ui-widget` and `data-template="stepper-guide"`.
- First step is visible before the script runs; no initial `display:none`.
- Dots, prev/next buttons, and step body are present in static HTML.
- Final `<script>` finds the root via a stable selector and sets `data-mounted=""`.
- No `DOCTYPE`/`<html>`/`<head>`/`<body>`/inline handlers/`position: fixed`.
- Uses token colors; no hard-coded light-only colors in component CSS.
- `templates/manifest.json` marks `stepper-guide` as ready.
