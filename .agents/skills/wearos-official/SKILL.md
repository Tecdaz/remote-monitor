---
name: wearos-official
description: "Trigger: Wear OS, smartwatch app, Android watch, watch face, tile, complication. Ground Wear OS work in the official Android developer guides (creating apps, design system, essentials)."
license: Apache-2.0
metadata:
  author: "santiago"
  version: "1.0"
---

# Wear OS — Official Guides

## Activation Contract

Use this skill when:

- **Scoping or starting a Wear OS app** — picking the template, surface, app model, design system.
- **Architecting** — UI framework, data layer, long-running work, surfaces, power strategy.
- **Needing the canonical source-of-truth** — linking PRs, RFCs, or design decisions back to official Android docs.
- **Designing** — referencing Material 3 Expressive for Wear, watch-specific UX patterns, surface taxonomy.

Do NOT use this skill for:

- Tactical Kotlin / Compose / performance / debugging patterns → use `wearos-specialist`.
- General Android (mobile, tablet, TV, Auto) → use `android-native-dev`.

## Hard Rules

- **Compose for Wear OS is the recommended UI framework** — not mobile `compose`, not Views.
- **Default app model = Hybrid** — core works without a phone, enhanced when a phone is connected.
- **Plan architecture BEFORE adding features** — app model → UI → data → long-running → surfaces → power.
- **Use the release build variant for performance measurements** — debug is misleading (especially with Compose).
- **Offline-first**: store critical data on the watch. Use the Data Layer API only for phone sync.
- **User-initiated long-running work** → Foreground Service + Ongoing Activity API (persistent notification + watch-face icon).
- **Deferrable background work** → WorkManager (battery-aware, Doze-respecting).
- **Glanceable UX**: optimize for one-thumb, one-tap primary actions on a small screen.
- **Empty Wear App template** is standalone by default — switch to hybrid only if the experience requires phone features.

## Decision Gates

| Decision | Options | Pick when |
| --- | --- | --- |
| **Surface** | App / Tile / Complication / Watch Face | Persistent at-a-glance data → Tile or Complication. Always-on UI → Watch Face. Interactive session → App. |
| **App model** | Hybrid / Standalone / Non-standalone | Core works without phone → Hybrid (default). Fully offline-first → Standalone. Core requires phone → Non-standalone. |
| **UI framework** | Compose for Wear OS / Views | New code → Compose. Legacy migration → Views only if forced. |
| **Lists** | `TransformingLazyColumn` / `LazyColumn` | Wear → `TransformingLazyColumn` (scaling + morphing). Mobile → `LazyColumn`. |
| **Navigation** | `SwipeDismissableNavHost` / `NavHost` | Wear → `SwipeDismissableNavHost` (integrates system swipe-to-dismiss). |
| **Long-running work** | Foreground Service / WorkManager / One-shot | User-initiated continuous → FGS + Ongoing Activity API. Deferrable periodic → WorkManager. Fire-and-forget → coroutine + `goAsync()` or `WorkManager`. |
| **Data** | DataStore / Room / Data Layer API | Key-value / typed → DataStore. Structured / relational → Room. Phone sync (Bluetooth + Wi-Fi) → Data Layer API. |

## Execution Steps (Official Get-Started Workflow)

1. **Create app** — Android Studio → `File > New > New Project` → Templates: `Wear OS` → `Empty Wear App` → `Finish`.
2. **Configure emulator** — SDK Manager → confirm `Android SDK Platform-Tools` (latest) → `Tools > Device Manager` → `Create (+)` → Category: `Wear OS` → pick profile (e.g. `Wear OS Small Round`) → keep defaults → `Finish`.
3. **Run on emulator** — Run Widget → select emulator → click Run ▶. Expect "Hello…" screen in seconds.
4. **(Optional) Run on a physical watch**:
   - Watch: `Settings > Developer options` (if absent: `System > About` → tap Build number 7×) → enable `ADB debugging`.
   - USB: connect cable → on watch tap `Always allow from this computer` → OK.
   - Wireless: follow Android Studio's `Connect to your device using Wi-Fi` guide.
5. **Plan architecture** — before adding features, lock in: app model, UI framework, data layer, long-running strategy, in-scope surfaces, power strategy.
6. **Measure performance** with the **release build variant**, never debug.

## Output Contract

When producing a Wear OS plan, RFC, or design doc:

- Cite the relevant official source URL from `references/INDEX.md` (and link the local page summary under `references/pages/`).
- State the chosen app model + UI framework + surface(s) upfront.
- Include the long-running work strategy (FGS + Ongoing Activity vs WorkManager).
- Defer tactical Kotlin / Compose / perf details to `wearos-specialist`.

When reviewing Wear OS code or PRs:

- Lists use `TransformingLazyColumn`, not mobile `LazyColumn`.
- Navigation uses `SwipeDismissableNavHost`, not mobile `NavHost`.
- User-initiated continuous work runs in a Foreground Service with an Ongoing Activity.
- Background work is lifecycle-safe and battery-aware.
- Any performance claim is backed by a **release** build measurement.

## References

- Local: `references/INDEX.md` — master index of all official Wear OS pages referenced by this skill, each linked to a structured summary in `references/pages/`.
- Local: `references/GET-STARTED-INDEX.md` — comprehensive catalog of all ~50 get-started pages (workflow, apps, compose, data layer, cross-cutting).
- Local: `references/SURFACES-INDEX.md` — comprehensive catalog of all ~60 surface pages (tiles, complications, notifications, widgets, watch faces + WFF, user input).
- Local: `references/DESIGN-INDEX.md` — comprehensive catalog of all ~80 Wear OS design pages (foundations, surfaces, styles, patterns, legacy M2-5 components).
- Local: `references/ESSENTIALS-INDEX.md` — comprehensive catalog of all essentials / versions / quality pages (Wear OS 4-7, quality checklist, packaging).
- Local: `references/pages/` — one structured summary per page (purpose + sections + when to consult + source quote). Start here before opening the canonical URL.
- Companion skill: `wearos-specialist` — tactical Wear OS implementation patterns, performance, debugging.
- Companion skill: `android-native-dev` — general Android native development guidance.