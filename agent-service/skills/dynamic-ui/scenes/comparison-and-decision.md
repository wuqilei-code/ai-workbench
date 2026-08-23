# Comparison & Decision

Shared contracts: apply `SKILL.md`, this scene file, `templates/manifest.json`, selected `templates/<id>/template.md`, and `tokens/visual-tokens.md`.

## When to enter this scene

The user intent involves weighing alternatives before a choice:

- Option comparison: A vs B trade-offs across fixed dimensions
- Tech selection: framework/library/tool choice with evidence
- Risk summary: threats ranked by likelihood x impact
- Decision matrix: scored options across weighted criteria
- Plan trade-offs: pricing tiers, migration paths, build-vs-buy

Typical trigger words: 对比, 选型, 方案比较, 哪个好, 优缺点, 权衡, 决策, 推荐, comparison, trade-off, pros and cons, which should I choose.

## When to reject

Do NOT generate a visualization in the following cases - reply with Markdown text instead:

- Only 2 options differing on 1 dimension (a sentence suffices)
- The user supplies no concrete attributes and wants a generic overview
- A recommendation is already obvious and uncontested
- The user asks for an implementation, not a decision aid
- More than ~6 options or more than ~6 dimensions: summarize to top candidates first, or answer with a Markdown table plus prose
- Evidence is subjective opinion only with no measurable dimensions: keep it conversational

## Generation principles

* Lead with the decision, not the data dump. One widget answers which option wins on what grounds; exhaustive spec sheets go to response text.
* Every option card / matrix row is a real box: title, key metric rows, and status tags must fit at token sizes before anything is drawn.
* Neutrality discipline: neutral surfaces carry the content; `--brand` marks the recommended option; `--accent` marks runner-up or contested cells. Semantic tokens only when the dimension itself is status/risk (license risk, deprecation).
* Do not color every cell. Max 2 non-neutral meaning colors without a legend; if you need a legend, reserve space for it outside the matrix body.

## Mandatory generation workflow

This workflow is mandatory after routing into this scene.

1. Confirm that a compact visual clarifies the choice better than a Markdown table or prose verdict.
2. Identify the decision shape: pairwise trade-off, multi-option matrix, ranked shortlist, or risk register.
3. Check `templates/manifest.json` for ready materials: prefer `comparison-cards` whenever 2-4 options are compared across shared dimensions.
4. Reject `comparison-cards` only for concrete reasons: more than 4 options, dimensions need numeric scoring bars rather than text rows, or the user asked for a scored matrix where a compact table visualizes better.
5. If no ready material matches, choose one fallback primitive (`decision-cards`, `compact-table-visual`, or `chart-card`) and keep output on the same token/color/typography/spacing/radius contract.
6. Before writing SVG or HTML, budget boxes: option cards/columns, dimension rows, score bars, winner badge, legend. If any box cannot fit at token sizes, cut dimensions or options instead of shrinking type.

### Hard readability gates

* Option cards: 2-4 cards per widget; each card has at most 5 metric rows plus 2 tags.
* Decision matrices: at most 4 options x 6 visible dimensions; collapse minor dimensions into a single summary row pointing to response text.
* Score bars: one bar per dimension max, labeled with the number, single series color unless highlighting the winner advantage.
* One focal point per widget: the recommended option (badge/border) or the deciding row. Everything else stays neutral.
* Winner marking uses border treatment or a badge on neutral fill; never flood the winner card with brand background.

## Composition guidelines

1. Direction: options as columns (2-4) or cards in a row; dimensions as rows beneath. Vertical stacking for narrow hosts.
2. Select primary material:
   - Trade-off shortlist -> `comparison-cards` skeleton
   - Scored matrix -> `compact-table-visual` with score bars
   - Single recommendation + alternatives -> `decision-cards`
3. Color restraint: neutral structure first; `--brand` on the recommended option marker and its winning dimension highlights; `--chart-series-*` only for per-option peer identity across charts. No rainbow cells.
4. Legend: needed only when non-neutral colors exceed 2; place it below the matrix, never floating over cells.
5. Silent layout planning: internally treat every card, row label, score bar, badge, tag, and legend as a bounding box. If any overlap, cut content or answer in Markdown; do not shrink type or squeeze spacing.
6. Fallback discipline: custom decision widgets still use neutral surfaces, tokenized typography/spacing/radius, and at most two meaning colors without legend. Verdict text stays in the response; the widget shows evidence, not conclusions beyond the winner mark.
