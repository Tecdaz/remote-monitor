# Wear OS user interfaces — Surfaces

**URL**: https://developer.android.com/training/wearables/user-interfaces
**Section**: Surfaces

## What this page is

The official **surfaces taxonomy** — every container where a Wear OS experience can live: Apps, Tiles, Widgets, Notifications, App launcher entries, Watch faces, Complications. Use it to decide which surface(s) fit a given user need.

## Sections

- Apps (full app surface)
- Tiles (quick, swipeable, glanceable)
- Widgets (home-screen widgets on watch)
- Notifications (high-value, contextual)
- App launcher entries
- Watch faces
- Complications (data on a watch face)

## When to consult

At surface-selection time, before writing code. The intro paragraph gives the decision heuristic: "main job" → surface. Use the decision table in `SKILL.md` to pick.

## Source quote

> When choosing a surface on Wear OS, keep your experience's main job in mind. For example, if you have a single unit of information that users are likely to want to glance at multiple times a day, consider providing a complication.