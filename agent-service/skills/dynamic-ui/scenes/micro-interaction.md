# Micro Interaction

Shared contracts: apply `SKILL.md`, this scene file, `templates/manifest.json`, selected `templates/<id>/template.md`, and `tokens/visual-tokens.md`.

## When to enter this scene

The user's intent involves a **locally interactive demonstration**:

- Parameter toggling (toggle between different configs/states to see effects)
- State change display (before/after, step-by-step)
- Data filtering/sorting (switching perspectives on existing data)
- Local animation demos (hover effects, expand/collapse)
- Interactive comparison (tab-switching between different views)

Typical trigger words: interactive demo, toggle to see, clickable, toggle, operable, interactive, demo, try it out.

**Distinction from data-visualization**: Data-viz focuses on chart rendering; micro-interaction focuses on user-operable UI fragments.

## When to reject

Do NOT generate an interactive widget in the following cases — use Markdown or static visualization instead:

- Content can be fully conveyed without user interaction
- Multi-page navigation is needed (widget environment does not support routing)
- Interaction logic depends on external APIs or real-time data sources
- Form submission/persistent storage is required
- Interaction concepts > 1 independent control group (complexity overflow)

## Generation principles

## Mandatory generation workflow

This workflow is mandatory after routing into this scene.

1. Confirm that one local interaction makes the answer clearer than a static visual or Markdown.
2. Pick exactly one control concept.
3. Choose a fallback primitive before output.
4. Choose `explanation-panel`, `decision-cards`, `chart-card`, or `node-flow` as the static structure that remains meaningful before JavaScript runs.
5. Apply tokenized typography, spacing, and radius from `tokens/visual-tokens.md` before adding interaction-specific styling.
6. Add only the minimum script needed to enhance that static structure with the chosen control.
6. If the interaction needs external APIs, persistence, routing, or multiple unrelated controls, do not render a widget.

### Core constraint: ONE Control Concept

Each widget contains only **one local control concept**:

- One toggle (on/off)
- One slider (continuous value adjustment)
- One tab group (view switching)
- One stepper (step forward/backward)
- One filter (select/deselect filtering)

Do not combine multiple independent interaction concepts in the same widget.

### JavaScript state management

- Use widget-internal JavaScript to manage local state (filter, sort, toggle, step, highlight)
- All state changes are reflected via DOM manipulation, no framework dependency
- State is stored in closure variables or `data-*` attributes

### sendPrompt usage rules

```js
window.sendPrompt('...')
```

- **Use only for**: Follow-up questions that require model reasoning (e.g., "explain the detailed principle behind this option")
- **Do NOT use for**: Local UI behaviors (tab switching, expand/collapse, data filtering)
- Decision criterion: Does the result of this action require LLM involvement? If not → handle with local JS

### Streaming compatibility

- **Do NOT use `display:none` to hide content** — all content must be visible during the streaming phase
- Initial state must render as fully visible HTML (e.g., the first step of a stepper, the default tab panel)
- The final `<script>` block handles interaction enhancement (progressive enhancement pattern)
- If JS does not execute, the widget should still display meaningful static content

### Event binding specifications

```javascript
// Correct: bind uniformly in the final script block
const root = document.querySelector('[data-dynamic-ui-widget][data-template="..."]:not([data-mounted])');
root.querySelectorAll('[data-action]').forEach(el => {
  el.addEventListener('click', handleAction);
});

// Wrong: inline onclick
// <button onclick="toggle()">  ← prohibited
```

- No inline event handlers (`onclick`, `onchange`, etc.)
- One final `<script>` block handles all event binding
- Use `querySelectorAll` + `addEventListener` pattern
- Prefer event delegation (listen on container, dispatch via `e.target.closest('[data-action]')`)

### Animation specifications

- Preferred properties: `transform`, `opacity`, `stroke-dashoffset`
- Transition duration: 80–200ms
- Must be wrapped in `@media (prefers-reduced-motion: no-preference)`:

```css
@media (prefers-reduced-motion: no-preference) {
  .panel { transition: opacity 150ms ease, transform 150ms ease; }
}
```

- Prohibited: `height` auto transition (poor performance), layout-triggering animations
- Collapse/expand uses `max-height` + `overflow: hidden` or `grid-template-rows: 0fr/1fr`

### Accessibility

- Interactive elements must have a `role` or semantic HTML tag (`<button>`, `<input>`)
- Tab groups use `role="tablist"` + `role="tab"` + `aria-selected`
- Toggles use `<button aria-pressed="true/false">`
- Keyboard reachable: focusable elements are reachable via Tab

### Interaction silent pre-output constraints

- Pick exactly one local control concept and keep all behavior inside the widget.
- Render a meaningful default state before the final script runs.
- Avoid `display:none` for streamed content; use progressive enhancement after mount.
- Bind events only in the final script with `addEventListener`; no inline handlers.
- Use `window.sendPrompt` only when the click needs model reasoning.
- Use semantic controls or ARIA attributes, and set `data-mounted` after initialization.

## Reference materials

Check `templates/manifest.json` for `scene: micro-interaction` ready materials and read the selected `templates/<id>/template.md` before adapting. The ready interactive materials:

| Data relationship / Intent | Reference material | Usage notes |
|---|---|---|
| Ordered stages / cyclic / loop process | `stepper-guide` | `● ○ ○` indicator, prev/next, wraps from last to first. Use for loops/cycles — never draw a loop as a ring or return arc. |
| Switch between complementary views (overview/detail/compare) | `tab-switch` | `role=tablist` tabs switching hidden panels; keyboard arrow navigation. |
| Filter a short list by keyword | `live-filter` | Single text input filters rows live; empty-state message on no match. |
| Toggle options, see a live computed total | `live-calc` | Checkbox rows drive a running total (and optional progress bar); rounded numbers with units. |

When no ready interactive material matches, build custom from HTML/JS fragments, still choosing one fallback primitive and staying on the token/typography/spacing/radius/theme contract.

## Routing rules (borrowed intent)

- **Cyclic / loop processes** (a Krebs cycle, an event loop, a recurring refresh): route to `stepper-guide` and render the stages as an HTML stepper with `● ○ ○` indicators. Do NOT draw a cycle as a ring diagram or a static return-arc SVG.
- **Database schema / ERD**: express as mermaid.js `erDiagram` markup in the surrounding text/report, not as a hand-drawn SVG entity diagram. (Inline widget diagrams stay SVG for flow/structure/mechanism; a schema belongs to report-style output, not a dynamic widget.)
- **Single-step linear sequence** (no wrap-around intended): `stepper-guide` is still acceptable for an ordered walkthrough; set the wrap-around as the loop-encoding only when the process truly repeats.

## Composition guidelines

1. **Determine interaction type**: What control does the user need? (toggle / slider / tabs / stepper / filter)
2. **Design initial state**: Render a fully visible default view (meaningful even without JS)
3. **Plan state changes**: List user actions → corresponding DOM changes (which elements show/hide/highlight)
4. **Write HTML**:
   - All panels/state content are rendered in the DOM
   - Inactive panels are controlled via class for opacity/visibility (not display:none)
   - Interactive controls carry `data-action` attributes
5. **Write Script**:
   - Get root → bind events → manage state → update DOM
   - After execution, set `root.setAttribute('data-mounted', '')`
6. **Add animation**: Add transitions only at state-switch points, wrapped in reduced-motion media query
7. **Fallback discipline**: The default rendered state must already satisfy the fallback visual contract. JavaScript may change state, but must not introduce a separate color system, font scale, spacing scale, radius scale, hidden-only content, or decorative UI chrome.
