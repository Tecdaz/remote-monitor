# Standalone versus non-standalone Wear OS apps

**URL**: https://developer.android.com/training/wearables/apps/standalone-apps
**Section**: Get started → App model

## What this page is

The official guidance for the **app-model** decision (standalone vs hybrid vs non-standalone). Recommends standalone by default, explains how to share code/data with a companion phone, and covers iPhone-paired quirks.

## Sections

- Plan your app (standalone-by-default recommendation)
- Shared code and data storage (multi-module / single Gradle project)
- Detect your app on another device (Data Layer API, CapabilityClient)
- Location data for watches paired to iPhones
- Obtain only necessary data (manifest flags, permissions)
- Additional code samples

## When to consult

At architecture-planning time, before locking the app model. Specifically when deciding whether your app needs a companion phone app, or when configuring the `Wear App` entry in Android App Bundle.

## Source quote

> We recommend that Wear OS apps work independently of a phone so users can complete tasks on a watch without access to an Android or iOS phone. If your watch app requires phone interaction, you must mark your Wear OS app as non-standalone.