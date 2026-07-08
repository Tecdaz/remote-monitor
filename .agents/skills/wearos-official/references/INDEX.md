# Wear OS Official Pages — Index

Local index of every official Android Wear OS page referenced by this skill. Each entry points to a structured summary under `pages/` (purpose + sections + when to consult + source quote) and to the canonical URL.

> When you're about to deep-dive on a topic, start with the local summary to decide if the official page is the right source, then follow the URL for the full content.

## Get started

> **Full catalog**: see [`GET-STARTED-INDEX.md`](GET-STARTED-INDEX.md) for all ~50 get-started pages (workflow, apps, compose, data layer, cross-cutting) with descriptions and pointers to local summaries.

| Page | One-line purpose | Local summary | Source |
| --- | --- | --- | --- |
| Create and run your first Wear OS app | End-to-end setup: template → emulator → watch | [get-started-creating.md](pages/get-started-creating.md) | [developer.android.com](https://developer.android.com/training/wearables/get-started/creating) |
| Principles of Wear OS development | The canonical principles every feature must respect | [principles.md](pages/principles.md) | [developer.android.com](https://developer.android.com/training/wearables/principles) |
| Test on the Wear OS emulator | Emulator features, testing capabilities, known issues | [emulator.md](pages/emulator.md) | [developer.android.com](https://developer.android.com/training/wearables/get-started/emulator) |
| Debug a Wear OS app | Standard debugging flow + Wear-specific setups | [debugging.md](pages/debugging.md) | [developer.android.com](https://developer.android.com/training/wearables/get-started/debugging) |
| Connect a watch to a phone | Pair emulator/physical watch with phone for testing | [connect-phone.md](pages/connect-phone.md) | [developer.android.com](https://developer.android.com/training/wearables/get-started/connect-phone) |
| Use Jetpack Compose on Wear OS | The recommended UI framework — what's different from mobile Compose | [compose.md](pages/compose.md) | [developer.android.com](https://developer.android.com/training/wearables/compose) |
| Lists with Compose for Wear OS | `TransformingLazyColumn` — scaling/morphing for round screens | [compose-lists.md](pages/compose-lists.md) | [developer.android.com](https://developer.android.com/training/wearables/compose/lists) |
| Navigation with Compose for Wear OS | `SwipeDismissableNavHost` + system swipe-to-dismiss | [compose-navigation.md](pages/compose-navigation.md) | [developer.android.com](https://developer.android.com/training/wearables/compose/navigation) |
| Jetpack Compose performance on Wear OS | Performance patterns for resource-constrained watches | [compose-performance.md](pages/compose-performance.md) | [developer.android.com](https://developer.android.com/training/wearables/compose/performance) |
| Rotary input with Compose | RSB / bezel input — required for essential interactions | [compose-rotary-input.md](pages/compose-rotary-input.md) | [developer.android.com](https://developer.android.com/training/wearables/compose/rotary-input) |
| Compose for Wear OS Codelab | Hands-on tutorial building a Wear app from the template | [compose-codelab.md](pages/compose-codelab.md) | [developer.android.com](https://developer.android.com/codelabs/compose-for-wear-os) |
| Standalone vs non-standalone apps | The app-model decision + multi-module setup | [standalone-apps.md](pages/standalone-apps.md) | [developer.android.com](https://developer.android.com/training/wearables/apps/standalone-apps) |
| Request permissions on Wear OS | Runtime permissions, scenarios, services | [permissions.md](pages/permissions.md) | [developer.android.com](https://developer.android.com/training/wearables/apps/permissions) |
| Conserve power and battery | Power efficiency patterns — sensors, CPU, wakelocks, jobs | [power.md](pages/power.md) | [developer.android.com](https://developer.android.com/training/wearables/apps/power) |
| Always-on apps and system ambient mode | AOD lifecycle, ambient vs active, battery cost | [always-on.md](pages/always-on.md) | [developer.android.com](https://developer.android.com/training/wearables/always-on) |
| Accessibility on Wear OS | Font scaling, rotary, TalkBack, touch targets | [accessibility.md](pages/accessibility.md) | [developer.android.com](https://developer.android.com/training/wearables/accessibility) |
| Overview of Data Layer API | Phone↔watch sync via Google Play services | [data-layer.md](pages/data-layer.md) | [developer.android.com](https://developer.android.com/training/wearables/data/overview) |

## Surfaces

> **Full catalog**: see [`SURFACES-INDEX.md`](SURFACES-INDEX.md) for all ~60 surface pages (tiles, complications, notifications, widgets, watch faces + WFF, user input) with descriptions and pointers to local summaries.

| Page | One-line purpose | Local summary | Source |
| --- | --- | --- | --- |
| Wear OS user interfaces — Surfaces | The surfaces taxonomy (App / Tile / Widget / Notification / Launcher / Watch face / Complication) | [surfaces.md](pages/surfaces.md) | [developer.android.com](https://developer.android.com/training/wearables/user-interfaces) |
| Tiles | Swipeable, non-scrollable quick-action cards | [tiles.md](pages/tiles.md) | [developer.android.com](https://developer.android.com/training/wearables/tiles) |
| Tile lifecycle and analytics | `TileService` lifecycle — bound service + tile-specific callbacks | [tile-lifecycle.md](pages/tile-lifecycle.md) | [developer.android.com](https://developer.android.com/training/wearables/tiles/lifecycle) |
| About complications | Data on a watch face — both data source and consumer | [complications.md](pages/complications.md) | [developer.android.com](https://developer.android.com/training/wearables/complications) |
| Notifications on Wear OS | Wear-specific notification patterns, bridger | [notifications.md](pages/notifications.md) | [developer.android.com](https://developer.android.com/training/wearables/notifications) |
| Display ongoing activities | `OngoingActivity` API for long-running tasks (workouts, media) | [ongoing-activity.md](pages/ongoing-activity.md) | [developer.android.com](https://developer.android.com/training/wearables/notifications/ongoing-activity) |
| Wear Widgets | New (Wear OS 7+) Glance-based widget surface | [widgets.md](pages/widgets.md) | [developer.android.com](https://developer.android.com/training/wearables/widgets) |
| Build watch faces | Hub — Watch Face Format is mandatory since Jan 2026 | [watch-faces.md](pages/watch-faces.md) | [developer.android.com](https://developer.android.com/training/wearables/watch-faces) |
| Appear in Recents and app resume | Launcher/Recents for tile/complication/notification entry points | [launcher-recents.md](pages/launcher-recents.md) | [developer.android.com](https://developer.android.com/training/wearables/apps/launcher) |

## Design

> **Full catalog**: see [`DESIGN-INDEX.md`](DESIGN-INDEX.md) for all ~80 design pages (foundations, surfaces, styles, patterns, legacy M2-5 components) with descriptions and pointers to local summaries.

| Page | One-line purpose | Local summary | Source |
| --- | --- | --- | --- |
| Design for Wear OS — hub | Top-level design entry point | [design-hub.md](pages/design-hub.md) | [developer.android.com](https://developer.android.com/design/ui/wear) |
| Design principles (M2-5 canonical) | Five Wear principles: critical tasks, wrist, better together, relevant, offline | [design-m2-5-principles.md](pages/design-m2-5-principles.md) | [developer.android.com](https://developer.android.com/design/ui/wear/guides/m2-5/foundations/design-principles) |
| Material 3 Expressive — Overview | The current design system for Wear | [design-get-started.md](pages/design-get-started.md) | [developer.android.com](https://developer.android.com/design/ui/wear/guides/get-started) |
| Material 3 Expressive — Design language | How to apply color, typography, shape, motion | [design-apply.md](pages/design-apply.md) | [developer.android.com](https://developer.android.com/design/ui/wear/guides/get-started/apply) |
| Levels of expression | Foundational / excellent / transformational tiers | [design-levels-expression.md](pages/design-levels-expression.md) | [developer.android.com](https://developer.android.com/design/ui/wear/guides/get-started/levels-expression) |
| Figma Design Kits | Pre-built kits for apps + tiles | [design-figma-kits.md](pages/design-figma-kits.md) | [developer.android.com](https://developer.android.com/design/ui/wear/guides/get-started/design-kits) |
| Adaptive design — Overview | Responsive vs adaptive — definitions, sizes, tiers | [design-adaptive-design.md](pages/design-adaptive-design.md) | [developer.android.com](https://developer.android.com/design/ui/wear/guides/foundations/adaptive-design) |
| Design quality tiers | Quality tiers for multi-size/shape Wear UIs | [quality-tiers.md](pages/quality-tiers.md) | [developer.android.com](https://developer.android.com/design/ui/wear/guides/foundations/quality-tiers) |
| Common adaptive design layouts | Canonical layouts for all watch shapes | [common-layouts.md](pages/common-layouts.md) | [developer.android.com](https://developer.android.com/design/ui/wear/guides/foundations/common-layouts) |
| Common layouts — non-scrolling | Media players, dialogs, pickers | [design-layouts-non-scrolling.md](pages/design-layouts-non-scrolling.md) | [developer.android.com](https://developer.android.com/design/ui/wear/guides/foundations/common-layouts/apps-non-scrolling) |
| Common layouts — scrolling | Lists (TransformingLazyColumn) + dialogs | [design-layouts-scrolling.md](pages/design-layouts-scrolling.md) | [developer.android.com](https://developer.android.com/design/ui/wear/guides/foundations/common-layouts/apps-scrolling) |
| Common layouts — tiles | Canonical tile layouts | [design-layouts-tiles.md](pages/design-layouts-tiles.md) | [developer.android.com](https://developer.android.com/design/ui/wear/guides/foundations/common-layouts/tiles) |
| Apps surface — Overview | App surface principles + container types | [design-apps-surface.md](pages/design-apps-surface.md) | [developer.android.com](https://developer.android.com/design/ui/wear/guides/surfaces/apps) |
| Apps — best practices | Eight opinionated rules for app screens | [design-apps-best-practices.md](pages/design-apps-best-practices.md) | [developer.android.com](https://developer.android.com/design/ui/wear/guides/surfaces/apps/best-practices) |
| Tiles surface — Overview | Three principles: Immediate, Predictable, Relevant | [design-tiles-surface.md](pages/design-tiles-surface.md) | [developer.android.com](https://developer.android.com/design/ui/wear/guides/surfaces/tiles) |
| Tiles — best practices | Ten tile rules (design on black, single use-case, etc.) | [design-tiles-best-practices.md](pages/design-tiles-best-practices.md) | [developer.android.com](https://developer.android.com/design/ui/wear/guides/surfaces/tiles/bestpractices) |
| Tiles — states | Sign-in / error / empty / ongoing state machine | [design-tiles-states.md](pages/design-tiles-states.md) | [developer.android.com](https://developer.android.com/design/ui/wear/guides/surfaces/tiles/states) |
| Color | Color principles — black-first, contrast, semantic roles | [color.md](pages/color.md) | [developer.android.com](https://developer.android.com/design/ui/wear/guides/styles/color) |
| Color — apply | Pair and layer M3 color tokens correctly | [design-color-apply.md](pages/design-color-apply.md) | [developer.android.com](https://developer.android.com/design/ui/wear/guides/styles/color/apply) |
| Typography | Type roles, variable font axis, tokens | [design-typography.md](pages/design-typography.md) | [developer.android.com](https://developer.android.com/design/ui/wear/guides/styles/typography) |
| Type scale | 21-style M3 Expressive type scale (Display/Title/Label/Body/Numeral/Arc) | [design-type-scale.md](pages/design-type-scale.md) | [developer.android.com](https://developer.android.com/design/ui/wear/guides/styles/typography/type-scale-tokens) |
| Typography accessibility | Contrast + user-configurable sizes | [design-type-accessibility.md](pages/design-type-accessibility.md) | [developer.android.com](https://developer.android.com/design/ui/wear/guides/styles/typography/accessibility) |
| Media design principles | Media-app-specific principles | [design-media-principles.md](pages/design-media-principles.md) | [developer.android.com](https://developer.android.com/design/ui/wear/guides/patterns/media/principles) |
| Design experiences for kids on Wear OS | Specialized principles for kid-targeted experiences | [wear-os-for-kids.md](pages/wear-os-for-kids.md) | [developer.android.com](https://developer.android.com/design/ui/wear/guides/m2-5/foundations/wear-os-for-kids) |

## Essentials & quality

> **Full catalog**: see [`ESSENTIALS-INDEX.md`](ESSENTIALS-INDEX.md) for all essentials / versions / quality pages (Wear OS 4-7, quality checklist, packaging) with descriptions and pointers to local summaries.

| Page | One-line purpose | Local summary | Source |
| --- | --- | --- | --- |
| Wear OS essentials — Overview | Platform pitch + entry points + latest version | [essentials.md](pages/essentials.md) | [developer.android.com](https://developer.android.com/wear) |
| Wear OS gallery | Curated showcase of real Wear apps | [gallery.md](pages/gallery.md) | [developer.android.com](https://developer.android.com/wear/gallery) |
| Wear OS 7 overview | Current version landing page (dynamic widgets + Live Updates) | [wear-os-7.md](pages/wear-os-7.md) | [developer.android.com](https://developer.android.com/training/wearables/versions/latest) |
| Wear OS 7 — features | Wear Widgets + Live Updates deep-dive | [wear-os-7-features.md](pages/wear-os-7-features.md) | [developer.android.com](https://developer.android.com/training/wearables/versions/7/features) |
| Wear OS 7 — behavior changes | Background audio hardening, local network permissions | [wear-os-7-changes.md](pages/wear-os-7-changes.md) | [developer.android.com](https://developer.android.com/training/wearables/versions/7/changes) |
| Wear OS app quality | Submission checklist for Google Play | [app-quality.md](pages/app-quality.md) | [developer.android.com](https://developer.android.com/docs/quality-guidelines/wear-app-quality) |

## How to use this index

1. Identify the page you need (by section + table).
2. Read the local summary first to confirm the page matches your question.
3. Click through to the canonical URL for full content.

If a summary is missing the topic you need, the page has changed since this index was generated, or the section you want isn't covered — follow the URL and update the local summary.

## Maintenance

These summaries were generated against `developer.android.com` in July 2026. Re-run the extractor for any page whose source appears to have shifted (new h2 sections, new content) and update the corresponding `pages/*.md`.