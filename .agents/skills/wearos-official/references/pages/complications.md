# About complications

**URL**: https://developer.android.com/training/wearables/complications
**Section**: Surfaces → Complications

## What this page is

The official guide to **Complications** — data displayed on a watch face alongside the time. Two-sided: explains how to expose your data as a complication data source AND how to consume complications in a watch face.

## Sections

- Complication data source (your app exposing data — `ComplicationDataSourceService`)
- Complications on watch faces (consuming — `ComplicationDrawable`, slot types)
- Complication types (`SHORT_TEXT`, `ICON`, `RANGED_VALUE`, etc.)

## When to consult

When deciding whether the watch-face surface fits your use case. Also when designing a watch face (you'll be the consumer) or making your data available to any watch face (you'll be the data source).

## Source quote

> A complication is any feature that is displayed on a watch face in addition to the time. For example, a battery indicator is a complication. The Complications API is for both watch faces and data source apps.