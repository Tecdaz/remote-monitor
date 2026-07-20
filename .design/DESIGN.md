# remote-monitor — Design System ("Iris Void")

> **Status:** Hybrid — editorial dark mode (Dala) + clinical data domain.
> Tailwind CSS v4, CSS-first configuration.
>
> **Reference lock:** Locked to the Dala reference provided by the project
> owner. Token roles are preserved — see the **Reference Lock** section
> before changing anything.

This document is the source of truth for the **remote-monitor** frontend
design tokens. The system adapts the editorial dark aesthetic of Dala
(pure black void, weight-200 display type, single saturated violet
accent) to a clinical patient-monitoring dashboard that needs status
semantics, live data indicators, and tabular numerics.

---

## Reference lock

> Adapted from the **Dala** reference (user-provided, verbatim).
> This lock is **the contract** between research and implementation.
> Do not soften any of these traits into safer defaults.

**Primary reference:** Dala (verbatim — see the project brief).

**Preserve:**

1. Pure black void `#000000` as the only canvas — **no** dark gray
   panels, **no** elevated cards.
2. Electric iris `#8052FF` as a **CTA-only** accent. Filled buttons and
   active nav only. Never surfaces, borders, or large background blocks.
3. Saffron spark `#FFB829` as the **emphasis** accent. Highlight text,
   warn state, attention punctuation. Not for actions.
4. Display type at weight **200** with `-0.04em` tracking at 42px+.
   Hierarchy comes from scale, not weight.
5. Border-radius **24px** consistent across buttons, cards, nav.
6. **No shadows, no elevation.** Cards distinguish themselves with a
   single 1px `deep-verdant` border, not shadows.
7. **One filled violet pill per view** — no button proliferation.

**Borrow only:**

- Status colors from the clinical domain (green / amber / red) — Dala
  doesn't define them, but a patient-monitoring dashboard needs them.
- Mint vital `#00FFAA` for the ok/live state — no Dala equivalent.
- HRV chart palette (VLF / LF / HF) — Dala has no charts.
- Subtle card border in `deep-verdant` `#15846E` — needed to give cards
  definition in dark mode without breaking Dala's flat-hierarchy rule.
- Fira Code for clinical numerics (BPM, IBI, PSD tabular alignment).

**Token roles — strict:**

| Token | Role | Where it may NOT appear |
| --- | --- | --- |
| `electric-iris` | Filled CTA, active nav, logo | Surfaces, borders, charts, large blocks |
| `saffron-spark` | Emphasis text, warn state, latest data point | Filled CTAs, surfaces |
| `mint-vital` | Ok / live status | Actions, large surfaces |
| `bone-white` | Typography only | Chrome (no white borders, no white fills) |
| `ash-gray` / `silver-mist` | Typography hierarchy | Chrome |
| `deep-verdant` | Card border only | Decoration, accents, charts |
| `status-danger` `#EF4444` | Critical signal only | Decoration, accents |
| `card-border` | Card edges | Decorative borders |

**Reject (anti-AI-slop):**

- ❌ Indigo/violet `#6366f1` / `#8b5cf6` defaults — electric-iris
  `#8052FF` is a deliberate brand choice, not the AI average.
- ❌ Cream/ivory + olive/clay "calm editorial" autopilot — research is
  Dala, not generic editorial.
- ❌ Decorative left accent stripes on cards.
- ❌ Standard emoji as icons.
- ❌ Cards-with-shadows. Cards get one border, that's it.
- ❌ Gradients on UI chrome (Dala: gradients belong to the logo + particle
  visualization only).
- ❌ Multiple saturated colors competing in a single button (max 2
  colors per component).
- ❌ Reference averaging — Dala stays dark and bold, no safe centroid.

**Token commitments:**

| Layer | Value |
| --- | --- |
| Canvas | `#000000` |
| Body text | `#FFFFFF` |
| Secondary text | `#BDBDBD` |
| Tertiary text | `#9A9A9A` |
| CTA (filled only) | `#8052FF` |
| Emphasis / warn | `#FFB829` |
| Ok / live | `#00FFAA` |
| Danger | `#EF4444` |
| Card border | `1px solid #15846E` |
| Border-radius | 24px |
| Spacing base | 6px |
| Numerics | Fira Code, tabular |

**Media strategy:**

- Charts (recharts): restrained, no decorative gradients, axis/grid in
  muted gray. Recharts SVG literals must come from `lib/theme-colors.ts`
  (CSS variables are not readable from JS).
- Status pills: real content with semantic colors.
- Particle constellation (Dala's brand signature): NOT implemented.
  Would require WebGL/Canvas particles and is out of scope for a clinical
  dashboard. If added later, must remain atmospheric (not interactive
  chrome) and respect the void background.

---

## Visual identity

- **Canvas:** Pure black `#000000`. The void is the design.
- **Brand accent:** Electric iris `#8052FF` — CTA filled only.
- **Emphasis accent:** Saffron spark `#FFB829`.
- **Status palette (clinical):**
  - Ok / Live: Mint vital `#00FFAA`
  - Warn: Saffron spark `#FFB829`
  - Danger: Standard red `#EF4444` (kept outside the iris palette on
    purpose so critical signals stay visually distinct)
- **Surfaces:** Void canvas with cards that have a **subtle** border
  (`#15846E`) and **no shadow**. Whitespace does the work that borders
  and shadows do in light-mode designs.

Bright chromatic colors (violet, amber, mint, teal) are reserved for
**interactivity and data only**. The void stays dark and quiet.

---

## Raw palette

| Token | Hex | Role |
| --- | --- | --- |
| `void` | `#000000` | Page canvas, section backgrounds |
| `bone-white` | `#FFFFFF` | Headlines, primary text, icon fills |
| `ash-gray` | `#9A9A9A` | Muted nav, ghost links, secondary labels |
| `silver-mist` | `#BDBDBD` | Tertiary text, captions |
| `electric-iris` | `#8052FF` | Filled CTAs, brand accent, active state |
| `saffron-spark` | `#FFB829` | Emphasis text, warn state |
| `deep-verdant` | `#15846E` | Subtle card border, logo gradient stop |
| `mint-vital` | `#00FFAA` | Ok / live status, healthy range |

### Standard additions (clinical + dark mode helpers)

| Token | Hex | Why |
| --- | --- | --- |
| `void-lift` | `#0A0A0A` | Hover / disabled fill (imperceptible lift). |
| `card-border` | `#15846E` | Card edges. Same value as `deep-verdant`. |
| `card-border-strong` | `#1F5A4A` | Hovered / focused cards. |
| `grid-light` | `#1A1A1A` | Chart grid lines. |
| `chart-axis` | `#9A9A9A` | Axis labels (alias of `ash-gray`). |
| `status-danger` | `#EF4444` | Critical signals. Outside the iris palette on purpose. |
| `status-danger-bg` | `#3A0F0F` | Danger background — deep red wash that works on black. |
| `status-ok-bg` | `#0F2A22` | Mint-tinted background, dark. |
| `status-warn-bg` | `#2A1F0A` | Amber-tinted background, dark. |
| `status-info-bg` | `#1A0F2A` | Iris-tinted background, dark. |

---

## Semantic namespaces

The raw palette is exposed to components through three semantic
namespaces. All defined in `frontend/app/styles.css` under the `@theme`
block, mapped to the raw palette via CSS `var()` chains.

### `clinical-*`

| Token | Maps to | Use |
| --- | --- | --- |
| `clinical-surface` | `void` | Page canvas — pure black |
| `clinical-panel` | `void` | Card background — same as canvas |
| `clinical-subtle` | `void-lift` | Hover / disabled fills |
| `clinical-border` | `deep-verdant` | Subtle card / input border |
| `clinical-border-strong` | `card-border-strong` | Hovered / focused card |
| `clinical-ink` | `bone-white` | Primary text, headlines |
| `clinical-ink-muted` | `silver-mist` | Helper text |
| `clinical-ink-faint` | `ash-gray` | Secondary text, labels |
| `clinical-accent` | `electric-iris` | Links, CTAs (filled pill only) |
| `clinical-accent-strong` | `#5E3FCC` | Hover / pressed (darker iris) |
| `clinical-accent-soft` | `#1F1545` | Selected backgrounds (iris wash) |

### `status-*`

| Token | Value | Use |
| --- | --- | --- |
| `status-live` | `mint-vital` | Streaming / connected |
| `status-ok` | `mint-vital` | Confirmed, healthy range |
| `status-warn` | `saffron-spark` | Pending, caution |
| `status-danger` | `#EF4444` | Critical, out of range |
| `status-info` | `electric-iris` | Informational |

Each status has a dark-tuned `*-bg` companion for tinted chips and pills.

### `chart-*`

| Token | Maps to | Use |
| --- | --- | --- |
| `chart-line` | `electric-iris` | Primary data series |
| `chart-line-latest` | `saffron-spark` | Latest / most recent point |
| `chart-axis` | `ash-gray` | Axis labels |
| `chart-grid` | `grid-light` | Grid lines (barely visible lift) |
| `chart-identity` | `silver-mist` | Reference / identity lines |
| `chart-vlf` | `electric-iris` | VLF frequency band |
| `chart-lf` | `saffron-spark` | LF frequency band |
| `chart-hf` | `mint-vital` | HF frequency band |

---

## Typography

Web fonts loaded from Google Fonts at build time (see
`frontend/app/styles.css`).

| Role | Stack | Weight |
| --- | --- | --- |
| Display / headlines | **Inter** | 200 (Dala signature — hierarchy by scale) |
| Body | **Inter** | 400 (legible for dense clinical data) |
| Numerics | **Fira Code** | 400 / 500 (BPM, IBI, PSD tabular alignment) |

Dala's signature: weight 200 carries 113px display headlines **and**
15px body text. The brand trusts scale, not weight, for hierarchy. We
follow that for display but **NOT** for body — weight 200 across a
clinical dashboard strains the eye. Body stays weight 400.

### Type scale (Dala-inspired)

| Role | Size | Line Height | Letter Spacing | Use |
| --- | --- | --- | --- | --- |
| caption | 12px | 1.5 | — | Smallest meta, chip labels |
| nav-label | 14px | 1.2 | 0.35px | Section labels, uppercase nav |
| body-sm | 14px | 1.44 | 0.28px | Dense clinical labels |
| body | 18px | 1.5 | — | Long-form body |
| subheading | 18px | 1.44 | -0.54px | Section titles |
| heading-2xs | 24px | 1.25 | -0.48px | Card headers |
| heading-xs | 27px | 1 | — | Patient name (Poincaré card) |
| heading-sm | 42px | 1.2 | -1.68px | Section hero |
| heading | 48px | 1.1 | -1.68px | Reserved |
| heading-lg | 78px | 1.1 | -3.12px | Reserved (Dala display) |
| display | 92px | 0.92 | -6.9px | Reserved (Dala display) |
| display-xl | 124px | 0.92 | -9.3px | Reserved (Dala display) |

Display sizes (42px+) apply **-0.04em** letter-spacing.

### Legacy scale

The previous system had `heading-sm/heading/heading-lg/heading-xl` at
slightly different sizes (24/46/54/66 px). Those tokens are kept as
`text-heading-legacy-*` for backwards compatibility with existing
components.

---

## Spacing

Base unit: **6 px** (Dala's choice — finer than Tailwind's 4px default).

Scale: `6 / 12 / 18 / 24 / 30 / 36 / 60 / 96 / 120` plus legacy
`8 / 16 / 20 / 32 / 40 / 48 / 116 / 220`. Maps to `--spacing-{n}` tokens
and generates `p-{n}` / `m-{n}` utilities.

---

## Border radius

Dala enforces a consistent **24px** across buttons, cards, and nav
elements. We follow that convention.

| Token | Value | Use |
| --- | --- | --- |
| `radius-md` | 7px | Tight controls, inputs (legacy) |
| `radius-card` | 24px | Cards, the project default (Dala) |
| `radius-pill` | 9999px | Tags, pill buttons (Dala) |
| `radius-2xl` | 16px | Reserved |
| `radius-3xl` | 24px | Modals (Dala) |
| `radius-3xl-2` | 32px | Hero panels (Dala) |

---

## Surfaces (Dala flat hierarchy)

| Level | Name | Value | Purpose |
| --- | --- | --- | --- |
| 0 | Void Canvas | `#000000` | Page background, every section background |
| 1 | Subtle Lift | `#0A0A0A` | Hover / disabled fills — almost imperceptible |
| 2 | Card Border | `#15846E` | Subtle verdant border that defines card edges |
| 3 | Active Action | `#8052FF` | Filled buttons, active interactive elements only |

The hierarchy is **flat on purpose**. Dala does not use shadows; the
absence of cards-with-shadows is deliberate. Cards distinguish
themselves from the void through a **single 1px border** in
`deep-verdant`, not elevation.

---

## Animations

| Token | Definition | Use |
| --- | --- | --- |
| `animate-pulse-live` | `pulse-live 1.8s ease-out infinite` | Mint pulse on live indicators |
| `animate-pulse-warn` | `pulse-warn 1.8s ease-out infinite` | Amber pulse on warning state |
| `animate-pulse-danger` | `pulse-danger 1.8s ease-out infinite` | Red pulse on critical state |
| `animate-fade-in` | `fade-in 220ms ease-out both` | Card / panel entry |

All `pulse-*` keyframes are tinted with the corresponding status color.

---

## Implementation

- **CSS framework:** Tailwind CSS v4.3+ (CSS-first config via `@theme`).
- **Build tool:** Vite 7 + `@tailwindcss/vite` plugin (no PostCSS).
- **Runtime:** Node 22 (TanStack Start 1.168 requirement).
- **Package manager:** `bun` (single lockfile: `bun.lock`).
- **Single source of truth:** `frontend/app/styles.css` for CSS tokens,
  `frontend/lib/theme-colors.ts` for Recharts / SVG constants that need
  literal color strings.

### Recharts and JS-bound colors

Recharts `stroke` / `fill` props and SVG attributes cannot read Tailwind
CSS variables at module-evaluation time, so the same palette is
duplicated in `frontend/lib/theme-colors.ts` as exported constants. If
a value changes in `styles.css`, update `theme-colors.ts` too.

### Component utilities

Two shared utilities are defined at the bottom of `styles.css`:

- `.clinical-card` — card surface, border, transition.
- `.btn` / `.btn-primary` / `.btn-ghost` — button family.

Both are kept for backwards compatibility; new code should prefer the
Tailwind utilities directly.

---

## Do's and don'ts

- ✅ **Do** use `#8052FF` (Electric Iris) **exclusively** for filled
  action buttons. No other saturated color should appear as a button
  background.
- ✅ **Do** set every headline at weight **200**, never bold. Hierarchy
  comes from scale (42–78px) and tracking (-0.04em), not weight. Body
  text is weight 400 for sustained reading.
- ✅ **Do** use Fira Code for clinical numerics so BPM, IBI, and PSD
  values align tabularly.
- ✅ **Do** maintain pure `#000000` black as every section background.
  Never use dark gray panels.
- ✅ **Do** use `#FFB829` (Saffron Spark) for emphasis highlights and
  `#8052FF` (Electric Iris) for interactive elements.
- ✅ **Do** apply `-0.04em` letter-spacing on all display sizes 42px and
  above.
- ✅ **Do** use 24px border-radius for buttons, cards, and nav
  consistently.
- ❌ **Don't** use filled violet (`#8052FF`) for large background
  blocks — it is a button and accent color, not a surface.
- ❌ **Don't** set body text at weight 200 — it strains the eye across
  a clinical dashboard. Reserve weight 200 for display headlines only.
- ❌ **Don't** introduce card containers with shadows. The void is the
  design; cards distinguish themselves with a single 1px
  `deep-verdant` border.
- ❌ **Don't** use `#0000EE` (default browser link blue) — use
  `#FFB829` amber or `#FFFFFF` for links.
- ❌ **Don't** add gradients to UI components. The palette is flat;
  gradients belong only in the logo and the particle visualization.
- ❌ **Don't** place multiple filled buttons in proximity — the violet
  pill is reserved for singular primary actions per view.
- ❌ **Don't** use emoji as icons. Use Lucide, Phosphor, Heroicons,
  Unicode symbols, or simple text characters.
- ❌ **Don't** drift token roles. Electric iris stays CTA. Saffron stays
  emphasis. Mint stays ok/live. Bone-white stays typography.

---

## Decision ledger

| Decision | Source | Source rule / role | Why |
| --- | --- | --- | --- |
| Canvas `#000000` (pure black, no panels) | Dala reference | "The void is the design" | Matches Dala's flat dark hierarchy |
| Electric iris `#8052FF` for CTA | Dala reference | "Filled action buttons only" | Single saturated accent for interactivity |
| Saffron spark `#FFB829` for emphasis | Dala reference | "Highlight emphasis text" | Warm accent that creates chromatic tension |
| Deep verdant `#15846E` for card border | Borrowed (not in Dala) | Card edges only | Dark mode needs something to define cards; Dala doesn't have data viz |
| Mint vital `#00FFAA` for ok/live | Borrowed (clinical domain) | Status only | Clinical semantics need green for healthy range |
| Status danger `#EF4444` red | Standard accessibility | Critical signal only | Distinct from iris palette; semantically universal |
| Display weight 200 | Dala reference | Trust scale, not weight | Signature trait; cannot soften |
| Body weight 400 (NOT 200) | User constraint | Legible for data | Dala body 200 strains eyes across clinical dashboard |
| Border-radius 24px | Dala reference | Consistent across UI | Dala's pill + card uniformity |
| No shadows | Dala reference | "Flat on purpose" | Hierarchy by whitespace, not elevation |
| Fira Code for numerics | Borrowed (clinical domain) | Tabular alignment | BPM, IBI, PSD need monospaced digits |
| 6px spacing base | Dala reference | Finer rhythm | Dala's spacing density |
| Bg of status pills dark-tinted | Standard dark-mode pattern | Wash on black | Light-mode tints look washed out on `#000` |
| Particle constellation NOT implemented | Out of scope | Brand signature | Would require WebGL; not justified for clinical dashboard |
| Card border `#15846E` instead of "invisible border" | Refero color.md guidance | Subtle border needed in dark mode | "In dark UI, surfaces communicate through subtle borders" |

---

## Anti-AI-slop audit

```
[X] Accent color is NOT indigo/violet default — electric-iris is a
    deliberate Dala reference, not the AI average.
[X] Cards justified by interaction — they hold live data (charts,
    metrics), not used as default decoration.
[X] No decorative left/side accent stripes.
[X] No standard emoji used as icons.
[X] Color mode is dark — explicitly justified by Dala reference.
[X] No warm ivory / olive / clay autopilot.
[X] No decorative one-word serif/italic/color highlight.
[X] Strong reference traits preserved (void, weight-200 display,
    24px radius, single saturated accent, no shadows).
[X] Source token/component roles preserved (CTA-only violet,
    emphasis-only saffron, status-only mint).
[X] Charts (the only "media") restrained, no decorative gradients.
[X] ALL CAPS text uses tracking (nav-label, caption-style chips).
[X] Card test passed — cards have border, removing the border loses
    chart/metric readability.
[X] Editorial test passed — replacing the logo with a coffee shop
    would NOT make this hero feel plausible. The clinical data viz
    (tachogram, Poincaré, frequency chart) is unmistakably THIS
    product.
```