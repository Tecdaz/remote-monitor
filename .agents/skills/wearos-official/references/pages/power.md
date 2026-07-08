# Conserve power and battery

**URL**: https://developer.android.com/training/wearables/apps/power
**Section**: Get started → Power strategy

## What this page is

The official guide to power efficiency on Wear OS — covers what drains battery (screen-on, CPU, wakelocks, sensors, network), how to monitor it, and patterns to minimize it. Treats power as a first-class design constraint.

## Sections

- Monitor battery usage over time (Battery Historian, dumpsys)
- Events that affect battery life
- Minimize screen-on time (ambient/AOD, dim)
- Minimize CPU usage (release-mode measurements, JIT, dev options)
- Minimize wakelocks
- Inspect how your app becomes inactive
- Analyze your app's scheduled jobs
- Sensors (sample rates, batching)
- Data Layer (sync strategy)
- Health and Fitness apps (special rules)

## When to consult

When designing a feature that uses sensors, network, or background work. When investigating battery complaints or before adding anything that runs while the screen is off. Companion to `wearos-specialist`'s performance rules.

## Source quote

> Power efficiency is especially important on Wear OS. The Wear OS design principles focus significantly on device power usage because the watch is a small form-factor, meant for short interactions.