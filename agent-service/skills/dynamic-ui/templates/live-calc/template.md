# Live Calculator

Use this template when the reader should toggle a small set of options (checkboxes) and see a live computed total — selections drive a running sum, count, or share.

Source inspiration: the external `可视化模板/03-交互组件模板.html` calculator skeleton. Adapted to the Dynamic UI chat-widget contract.

## Use When

- The reader picks/checks 2-8 options and a single computed result (sum, count, share) updates live.
- A checkbox list plus computed total is the clearest way to explore "what if" selections.
- The widget needs exactly one local control concept (toggles driving one result). No other independent controls may be added.

## Avoid When

- The result needs more than one kind of computation (sum + average + max, etc.) that competes for focus.
- The reader needs sliders, numeric entry, or multi-step forms.
- The selection depends on external data or persistence.
- A static list or table answers the question without interaction.

## Data Shape

```json
{
  "totalLabel": "Selected total",
  "unit": "GB",
  "max": 7.8,
  "items": [
    { "name": "Temp files", "value": 4, "checked": true },
    { "name": "Package manager cache", "value": 1.6, "checked": true },
    { "name": "IDE cache", "value": 2.2, "checked": false }
  ]
}
```

Each option renders as a labeled checkbox row with a numeric value. A computed total (and optional remaining/bar) updates live from the checked items. All displayed numbers are rounded (`Math.round` / `toFixed`) and show units.

## Visual Rules

- Use `--surface` for the panel, `--surface-muted` for rows or the bar track.
- Checkbox accent uses `--chart-series-1`/`--brand`; the computed total uses the metric hierarchy.
- Token typography: option label `--text-body`, option value `--text-body`/medium, result number `--text-title`/`--font-metric`.
- Use `--radius` for rows, `--radius-full` for the progress bar; spacer tokens for gaps/padding.
- Keep the panel to the compact widget budget (target height ≤340px).

## Required Interaction

- Options render checked/unchecked precisely as given; the computed total matches the initial checked set before JavaScript runs.
- Toggling any checkbox recomputes the total, remaining (if used), and bar width live.
- Use native `<input type="checkbox">` with explicit `aria-label`; buttons/rows are toggled via the checkbox.
- Bind only in the final `<script>` via the stable root selector; no inline handlers.
- Wrap the bar width/result transitions in `@media (prefers-reduced-motion: no-preference)`; use `width`/`opacity`, max 200ms.

## Edge Cases

- No items checked: total shows `0` + unit; still readable.
- `max` present but sum exceeds it: clamp the bar to 100% and keep the numeric total honest.
- Negative/missing values: clamp to 0 or omit; keep rounding consistent.
- If JavaScript fails, the initial checked state and its computed total stay visible; only live updates are lost.

## Implementation Assumptions

- The full token `<style>` header (light + dark + media fallback) is included verbatim.
- Data is small and embedded directly in the fragment.
- One result (total) is the focal output; optional remaining/bar is subordinate.

## Acceptance Checks

- `widget-code.html` starts with `<style>`.
- Root is `.widget` with `data-dynamic-ui-widget` and `data-template="live-calc"`.
- Initial checked set matches the initial total in static HTML; no computation dependent on the script for first paint.
- Checkbox rows, total display, and optional bar exist in static HTML.
- Final `<script>` finds the root via a stable selector and sets `data-mounted=""`.
- No `DOCTYPE`/`<html>`/`<head>`/`<body>`/inline handlers/`position: fixed`.
- Uses token colors; no hard-coded light-only colors in component CSS.
- `templates/manifest.json` marks `live-calc` as ready.
