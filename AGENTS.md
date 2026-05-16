# Agent Guide

This repository contains the Fall Guardian native Wear OS app.

Also follow the workspace-level guide at `../AGENTS.md` when working from the parent folder.

`CLAUDE.md` must stay a thin pointer to this file.

## Project

- Native Android Wear OS app for fall detection and watch-side alert handling
- Keep sensor/fall detection code explicit and testable
- Keep phone/backend communication boundaries narrow and easy to review

## Engineering Rules

Always:

- keep watch-to-phone and watch-to-backend contracts aligned with the assisted app and backend
- keep Gradle, Android Studio, and local SDK generated files out of Git
- prefer readable, explicit code over clever Android/Wear OS platform tricks
- add concise comments for Wear OS concepts, sensors, foreground services, wake locks, permissions, background message delivery, and safety-critical alert behavior when they are not obvious to a non-mobile developer
- keep automated line coverage at or above 90%; coverage must come from useful behavior, contract, edge-case, and regression tests, not shallow line execution
- run relevant Gradle checks after Kotlin/Java or Android configuration changes when feasible

Ask first:

- adding Gradle dependencies or Android plugins
- changing package ID, signing, permissions, sensors, foreground service behavior, or deployment targets
- changing fall detection thresholds or alert handoff behavior

Never:

- hardcode API secrets, tokens, or local machine paths
- hide important fall-detection workflow in framework entrypoints

## Verification

Common commands:

```sh
make check
```
