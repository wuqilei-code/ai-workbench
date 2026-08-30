# Live Filter

Use this template when the reader should filter a short list of items by typing a keyword into a local input, with the list updating live.

Source inspiration: the external `可视化模板/03-交互组件模板.html` live-filter skeleton. Adapted to the Dynamic UI chat-widget contract.

## Use When

- A compact list of 3-12 items needs filtering by a keyword.
- A single text input is the clearest local control to scan and narrow the list.
- The widget needs exactly one local control concept (filtering). No other independent controls may be added.

## Avoid When

- The whole list fits and is clearer without filtering.
- Filtering needs multiple dimensions or more than one control group.
- The list relies on external data sources or pagination.
- The reader needs sorting, paging, or editing — those are separate control concepts.
- A compact table or grouped bars answers the question better as a chart/table.

## Data Shape

```json
{
  "placeholder": "Type to filter…",
  "items": [
    { "name": "Docker data migration", "meta": "Storage · scheduled" },
    { "name": "Temp file cleanup", "meta": "Cache · ready" },
    { "name": "Package manager cache", "meta": "Cache · ready" },
    { "name": "System update leftovers", "meta": "Storage · review" }
  ]
}
```

Items render as a static list of rows; each row shows `name` (primary) and `meta` (secondary). Filtering hides non-matching rows (empty match shows an empty-state message).

## Visual Rules

- Use `--surface` for the panel, `--surface-muted` for the input, `--border` for separators.
- Row text: primary `--text-body`, secondary `--text-caption`/`--text-muted`.
- Input uses `--radius`, token padding, and `--border`; focus uses `--brand` border.
- Use spacer tokens for gaps/padding; keep to the compact widget budget (target height ≤340px).

## Required Interaction

- The full list is visible before JavaScript runs (no filtering on first paint).
- Typing filters rows in place; a no-match state shows a brief empty message.
- Use `<input>` with an `aria-label`; rows are anonymous list items (`<div>` or `<li>`).
- Bind only in the final `<script>` via the stable root selector; no inline handlers.
- Wrap row transitions in `@media (prefers-reduced-motion: no-preference)`; use `opacity`, max 120ms.

## Edge Cases

- Empty `items`: show a readable empty-state message.
- Empty or whitespace query: show the full list.
- Case differences: match case-insensitively.
- If JavaScript fails, the full list stays readable; only filtering is lost.

## Implementation Assumptions

- The full token `<style>` header (light + dark + media fallback) is included verbatim.
- Data is small and embedded directly in the fragment.
- Filter is a single-keyword, single-control concept.

## Acceptance Checks

- `widget-code.html` starts with `<style>`.
- Root is `.widget` with `data-dynamic-ui-widget` and `data-template="live-filter"`.
- Full list is visible before the script runs; no initial filtering.
- Input and list rows exist in static HTML.
- Final `<script>` finds the root via a stable selector and sets `data-mounted=""`.
- No `DOCTYPE`/`<html>`/`<head>`/`<body>`/inline handlers/`position: fixed`.
- Uses token colors; no hard-coded light-only colors in component CSS.
- `templates/manifest.json` marks `live-filter` as ready.
