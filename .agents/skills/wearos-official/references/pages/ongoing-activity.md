# Display ongoing activities

**URL**: https://developer.android.com/training/wearables/notifications/ongoing-activity
**Section**: Surfaces → Notifications → Ongoing

## What this page is

The official guide for **Ongoing Activities** — the pattern for user-initiated long-running tasks on Wear. Pairs an ongoing notification with the `OngoingActivity` API so the user sees a tappable icon on the watch face and gets back to the app in one tap.

## Sections

- Setup
- Create an Ongoing Activity
- Add dynamic status text to the launcher
- Additional customizations
- Update an Ongoing Activity
- Stop an Ongoing Activity
- Key considerations
- Publish media notifications when playing media on Wear OS devices

## When to consult

Whenever you have a user-initiated long-running task (workout, run, timer, media playback). Required for any feature where users need to return to the app from the watch face. Companion to `wearos-specialist`'s foreground service guidance.

## Source quote

> Wear OS devices are often used for long-running experiences, such as tracking a workout. This presents a user experience challenge: if a user starts a task and then navigates away to the watch face, how do they get back?... The solution is to pair an ongoing notification with an OngoingActivity.