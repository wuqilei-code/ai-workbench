# Tab Switch

Use this template to switch between a small set of complementary views of the same subject (overview / detail / compare, or a few aspects of one thing) with a local tab control.

Source inspiration: the external `可视化模板/03-交互组件模板.html` tab skeleton. Adapted to the Dynamic UI chat-widget contract.

## Use When

- The reader needs to switch between 2-4 views that are each partial facets of one subject.
- A local tab control is the clearest, lightest way to show those facets inline.
- The widget needs exactly one local control concept (view switching). No other independent controls may be added.

## Avoid When

- The views are better shown side by side or as separate sequential widgets.
- The reader needs multi-page navigation or real routing (not supported in the widget environment).
- The subject is a comparison that belongs with `comparison-cards` or a data question that belongs with a chart template.
- More than 4 views are needed — group or summarize.
- Views depend on external APIs, persistence, or form submission.

## Data Shape

```json
{
  "tabs": [
    { "id": "overview", "label": "Overview", "body": "Core summary of the subject." },
    { "id": "detail", "label": "Detail", "body": "Itemized facts and numbers." },
    { "id": "compare", "label": "Compare", "body": "Side-by-side trade-offs." }
  ]
}
```

The first tab is the default active view and must be fully visible in static HTML. The active tab label maps to `aria-selected="true"` and its panel is visible; inactive panels use a hidden-but-present technique (not `display:none` on first paint).

## Visual Rules

- Use `--surface` for panels and `--surface-muted` (or a brand-soft treatment) for the active tab.
- Active tab uses token emphasis: `--chart-series-1`/`--brand` text with a neutral fill, or a neutral fill with a brand border. Do not fill panels with brand or semantic colors.
- Token typography: tab label `--text-body`; panel body `--text-body` with `--text-muted` for secondary text.
- Use `--radius` for tabs and panels; `--border` for separators; spacer tokens for gaps/padding.
- Keep to the compact widget budget (target height ≤340px).

## Required Interaction

- Render the first/default tab panel fully visible before JavaScript runs.
- Clicking a tab switches the visible panel and updates `aria-selected` on the tab buttons (use `role="tablist"` / `role="tab"` / `role="tabpanel"`).
- Keyboard reachable: tabs are focusable and activable via arrow keys or Tab+Enter.
- Bind only in the final `<script>` via the stable root selector; no inline handlers.
- Panels toggled with a hidden class (e.g. `[hidden]` set in JS after mount) rather than relying on `display:none` during first paint; the initial default panel is visible.
- Wrap panel transitions in `@media (prefers-reduced-motion: no-preference)`; use `opacity`, max 150ms.

## Edge Cases

- Empty `tabs`: show a readable empty-state fallback.
- A `body` referencing a missing panel id: ignore that panel instead of breaking the tab list.
- Long panel text: wrap within the panel rather than shrinking font or fixed clipping.
- If JavaScript fails, the first/default panel stays readable; only switching is lost.

## Implementation Assumptions

- The full token `<style>` header (light + dark + media fallback) is included verbatim.
- Data is small and embedded directly in the fragment.
- Tab count stays within 2-4.

## Acceptance Checks

- `widget-code.html` starts with `<style>`.
- Root is `.widget` with `data-dynamic-ui-widget` and `data-template="tab-switch"`.
- Default panel is visible before the script runs; active tab has `aria-selected="true"`.
- Tab buttons and panels exist in static HTML.
- Final `<script>` finds the root via a stable selector and sets `data-mounted=""`.
- No `DOCTYPE`/`<html>`/`<head>`/`<body>`/inline handlers/`position: fixed`.
- Uses token colors; no hard-coded light-only colors in component CSS.
- `templates/manifest.json` marks `tab-switch` as ready.
