# Tile states

**URL**: https://developer.android.com/design/ui/wear/guides/surfaces/tiles/states
**Section**: Design → Surfaces → Tiles → States

## What this page is

The catalog of tile states — every condition a tile must design for. Sign-in, error, empty (no data), empty (content), empty (progress), and ongoing (reference) states.

## Sections

- Sign-in states
- Error states
- Empty states (no data)
- Empty states (content)
- Empty states (progress)
- Ongoing (reference) states

## When to consult

When implementing a tile's state machine. The "empty states" are NOT just for error paths — the happy empty state is often the most-shown state and deserves its own design treatment. Every state must have a clear call-to-action.

## Source quote

> Empty states aren't always caused by an error or lack of data, but could be a logged out state, or a happy message... In some cases the empty state is the most common state.