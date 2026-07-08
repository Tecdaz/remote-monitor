# Wear OS Get Started — Comprehensive Catalog

Master catalog of all official Wear OS pages under `/training/wearables/` that fall under the "Get started" umbrella — the developer workflow, principles, and core APIs. Per-page summaries for the most-referenced entries live in `pages/` (linked below where available).

> Last scanned: 2026-07-08. URL list extracted from the side-nav of the get-started/creating page.

## Sections

- [Get started workflow](#get-started-workflow)
- [Apps](#apps)
- [Compose for Wear OS](#compose-for-wear-os)
- [Data layer](#data-layer)
- [Cross-cutting topics](#cross-cutting-topics)

---

## Get started workflow

The end-to-end developer workflow — create, run, debug, test.

| URL | Description | Local summary |
| --- | --- | --- |
| [/training/wearables](https://developer.android.com/training/wearables) | Wear OS training hub | — |
| [/training/wearables/get-started/creating](https://developer.android.com/training/wearables/get-started/creating) | Create and run your first Wear OS app | [pages/get-started-creating.md](pages/get-started-creating.md) |
| [/training/wearables/get-started/connect-phone](https://developer.android.com/training/wearables/get-started/connect-phone) | Pair emulator/physical watch with phone | [pages/connect-phone.md](pages/connect-phone.md) |
| [/training/wearables/get-started/emulator](https://developer.android.com/training/wearables/get-started/emulator) | Test on the Wear OS emulator | [pages/emulator.md](pages/emulator.md) |
| [/training/wearables/get-started/debugging](https://developer.android.com/training/wearables/get-started/debugging) | Debug a Wear OS app | [pages/debugging.md](pages/debugging.md) |
| [/training/wearables/get-started/debug-wifi](https://developer.android.com/training/wearables/get-started/debug-wifi) | Debug over Wi-Fi (no USB) | — |
| [/training/wearables/get-started/screenshots](https://developer.android.com/training/wearables/get-started/screenshots) | Capture screenshots from the watch | — |
| [/training/wearables/principles](https://developer.android.com/training/wearables/principles) | Principles of Wear OS development | [pages/principles.md](pages/principles.md) |

## Apps

App-level concerns: build configuration, runtime behavior, system integrations.

| URL | Description | Local summary |
| --- | --- | --- |
| [/training/wearables/apps](https://developer.android.com/training/wearables/apps) | Apps section hub | — |
| [/training/wearables/apps/standalone-apps](https://developer.android.com/training/wearables/apps/standalone-apps) | Standalone vs non-standalone apps | [pages/standalone-apps.md](pages/standalone-apps.md) |
| [/training/wearables/apps/permissions](https://developer.android.com/training/wearables/apps/permissions) | Request permissions on Wear OS | [pages/permissions.md](pages/permissions.md) |
| [/training/wearables/apps/power](https://developer.android.com/training/wearables/apps/power) | Conserve power and battery | [pages/power.md](pages/power.md) |
| [/training/wearables/apps/launcher](https://developer.android.com/training/wearables/apps/launcher) | Appear in Recents and app resume | [pages/launcher-recents.md](pages/launcher-recents.md) |
| [/training/wearables/apps/splash-screen](https://developer.android.com/training/wearables/apps/splash-screen) | Splash screens on Wear | — |
| [/training/wearables/apps/audio](https://developer.android.com/training/wearables/apps/audio) | Audio on Wear apps | — |
| [/training/wearables/apps/auth-wear](https://developer.android.com/training/wearables/apps/auth-wear) | Auth flows on Wear | — |
| [/training/wearables/apps/location-detection](https://developer.android.com/training/wearables/apps/location-detection) | Location detection on Wear | — |
| [/training/wearables/apps/test-bluetooth-audio](https://developer.android.com/training/wearables/apps/test-bluetooth-audio) | Test Bluetooth audio on Wear | — |

## Compose for Wear OS

The recommended UI framework — UI primitives, lists, navigation, performance, input.

| URL | Description | Local summary |
| --- | --- | --- |
| [/training/wearables/compose](https://developer.android.com/training/wearables/compose) | Use Jetpack Compose on Wear OS | [pages/compose.md](pages/compose.md) |
| [/training/wearables/codelabs/compose-for-wear-os](https://developer.android.com/codelabs/compose-for-wear-os) | Compose for Wear OS codelab | [pages/compose-codelab.md](pages/compose-codelab.md) |
| [/training/wearables/compose/lists](https://developer.android.com/training/wearables/compose/lists) | Lists with `TransformingLazyColumn` | [pages/compose-lists.md](pages/compose-lists.md) |
| [/training/wearables/compose/navigation](https://developer.android.com/training/wearables/compose/navigation) | Navigation with `SwipeDismissableNavHost` | [pages/compose-navigation.md](pages/compose-navigation.md) |
| [/training/wearables/compose/navigation3](https://developer.android.com/training/wearables/compose/navigation3) | Navigation 3 (latest) | — |
| [/training/wearables/compose/performance](https://developer.android.com/training/wearables/compose/performance) | Compose performance on Wear | [pages/compose-performance.md](pages/compose-performance.md) |
| [/training/wearables/compose/rotary-input](https://developer.android.com/training/wearables/compose/rotary-input) | Rotary input (RSB / bezel) | [pages/compose-rotary-input.md](pages/compose-rotary-input.md) |
| [/training/wearables/compose/screen-size](https://developer.android.com/training/wearables/compose/screen-size) | Compose for multi-size screens | — |
| [/training/wearables/compose/migrate-to-material3](https://developer.android.com/training/wearables/compose/migrate-to-material3) | Migrate to Material 3 | — |
| [/training/wearables/compose/migrate-to-navigation3](https://developer.android.com/training/wearables/compose/migrate-to-navigation3) | Migrate to Navigation 3 | — |
| [/training/wearables/views](https://developer.android.com/training/wearables/views) | Legacy View-based UI (deprecated) | — |

## Data layer

Watch-only persistence and phone↔watch sync.

| URL | Description | Local summary |
| --- | --- | --- |
| [/training/wearables/data/overview](https://developer.android.com/training/wearables/data/overview) | Overview of Data Layer API | [pages/data-layer.md](pages/data-layer.md) |
| [/training/wearables/data/data-items](https://developer.android.com/training/wearables/data/data-items) | Data Items API | — |
| [/training/wearables/data/events](https://developer.android.com/training/wearables/data/events) | Message events | — |
| [/training/wearables/data/client-types](https://developer.android.com/training/wearables/data/client-types) | Client types | — |
| [/training/wearables/data/network-communication](https://developer.android.com/training/wearables/data/network-communication) | Network communication across devices | — |
| [/training/wearables/data/dynamic](https://developer.android.com/training/wearables/data/dynamic) | Dynamic features (on-demand modules) | — |
| [/training/wearables/data/discover-devices](https://developer.android.com/training/wearables/data/discover-devices) | Discover nearby devices | — |
| [/training/wearables/data/sync](https://developer.android.com/training/wearables/data/sync) | Data sync patterns | — |
| [/training/wearables/data/cloud-backup-restore](https://developer.android.com/training/wearables/data/cloud-backup-restore) | Cloud backup and restore | — |
| [/training/wearables/data/transfer-to-new-mobile](https://developer.android.com/training/wearables/data/transfer-to-new-mobile) | Transfer to a new phone | — |

## Cross-cutting topics

| URL | Description | Local summary |
| --- | --- | --- |
| [/training/wearables/accessibility](https://developer.android.com/training/wearables/accessibility) | Accessibility on Wear OS | [pages/accessibility.md](pages/accessibility.md) |
| [/training/wearables/always-on](https://developer.android.com/training/wearables/always-on) | Always-on display (ambient mode) | [pages/always-on.md](pages/always-on.md) |
| [/training/wearables/wear-v-mobile](https://developer.android.com/training/wearables/wear-v-mobile) | Wear vs Mobile — how they differ | — |
| [/training/wearables/creating-app-china](https://developer.android.com/training/wearables/creating-app-china) | Special steps for the China market | — |
| [/training/wearables/packaging](https://developer.android.com/training/wearables/packaging) | App packaging for Wear | — |
| [/training/wearables/kids/develop](https://developer.android.com/training/wearables/kids/develop) | Develop Wear experiences for kids | — |

---

## How to use this catalog

1. Identify the topic by section (workflow / apps / compose / data / cross-cutting).
2. Read the 1-line description to confirm the page is the right one.
3. Open the canonical URL for full content.
4. If a per-page summary exists, read it first — it captures the page's structure in 4 sections (purpose / sections / when to consult / source quote).

## Maintenance

The side-nav on `developer.android.com/training/wearables/get-started/creating` is the source of truth for this section's URL list. Re-scan when a new sidebar entry appears.