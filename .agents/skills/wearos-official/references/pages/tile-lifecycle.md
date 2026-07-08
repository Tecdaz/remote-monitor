# Tile lifecycle and analytics events

**URL**: https://developer.android.com/training/wearables/tiles/lifecycle
**Section**: Surfaces → Tiles → Lifecycle

## What this page is

The official guide to **`TileService` lifecycle** — how the bound service differs from a regular bound service (TileService-specific lifecycle methods run on a separate async thread from the standard Service callbacks), and what analytics events to emit.

## Sections

- TileService lifecycle (onCreate / onBind / onUnbind / onDestroy + tile-specific methods)
- Analytics events (visibility, interaction)

## When to consult

When implementing a tile and you need to understand when its callbacks fire. Critical for managing resources (e.g., not subscribing to a sensor until the tile is visible).

## Source quote

> TileService is a bound service... However, TileService differs from most other bound services because it also contains TileService-specific lifecycle methods. The Service lifecycle methods and the TileService lifecycle methods are called in two separate asynchronous threads.