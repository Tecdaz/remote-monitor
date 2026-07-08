# Appear in Recents and app resume

**URL**: https://developer.android.com/training/wearables/apps/launcher
**Section**: Surfaces → Launcher / Recents

## What this page is

The official guide for **launcher & Recents** behavior on Wear — how to ensure activities launched from tiles, notifications, or complications show up correctly in the Wear Recents list.

## Sections

- Label all activities (manifest)
- Configure tasks for Recents (TaskStackBuilder)
- Debug tips

## When to consult

When your app has multiple entry points (tile, complication, notification, voice) and needs them all to show up in Recents with the right label and icon.

## Source quote

> The launcher displays a label and icon for any recently resumed tasks. If your app package has multiple apps as separate launcher activities, the launcher doesn't know which label and icon to display for non-launcher activities, such as activities launched from a tile or a notification.