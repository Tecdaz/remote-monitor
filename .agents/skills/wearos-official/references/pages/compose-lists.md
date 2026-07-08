# Lists with Compose for Wear OS

**URL**: https://developer.android.com/training/wearables/compose/lists
**Section**: Get started → Compose

## What this page is

The official guide for **lists on Wear** with Compose — covers `TransformingLazyColumn` (the Wear-specific `LazyColumn` that scales/morphs items as they near the edge of a round screen), plus snap-and-fling and reverse layout.

## Sections

- Add a snap-and-fling effect
- Reverse layout (RTL support)

## When to consult

When building any list screen on Wear. The default `LazyColumn` from mobile Compose doesn't handle round screens well — use `TransformingLazyColumn` instead. This is the canonical reference.

## Source quote

> Many Wear OS devices use round screens, which makes it more difficult to see list items that appear near the top and bottom of the screen. For this reason, Compose for Wear OS includes a version of the LazyColumn class called TransformingLazyColumn, which supports scaling and morphing animations.