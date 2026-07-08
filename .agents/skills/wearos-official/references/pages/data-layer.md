# Overview of Data Layer API

**URL**: https://developer.android.com/training/wearables/data/overview
**Section**: Get started → Data layer

## What this page is

The official entry point to the **Wearable Data Layer API** — Google Play services' channel for syncing data and messages between a Wear OS watch and a paired Android phone (Bluetooth + Wi-Fi). Covers use cases, transport options, security, setup, and client lifecycle.

## Sections

- Common use cases (DataItems, Messages, Channels)
- Options for communication (Data Item / Asset / Message / Channel)
- Security of communications
- Setup (dependencies, permissions, manifest entries)
- Access the data layer (getClient / getCapabilityClient)
- Use a minimal client
- Recreate client instances as necessary (lifecycle)

## When to consult

When designing phone↔watch sync (config, state, files, real-time events). NOT for watch-only persistence (use DataStore/Room). iPhone-paired watches are NOT supported by this API.

## Source quote

> The Wearable Data Layer API, which is part of Google Play services, provides a communication channel between wearable devices (like smart watches) and connected handheld devices (usually smartphones).