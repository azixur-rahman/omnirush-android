# OmniRush Android

This directory contains the Android client for OmniRush, built with Jetpack
Compose. It uses a dark, translucent material system inspired by Apple Liquid
Glass: layered surfaces, restrained color, thumb-friendly controls, and short
interruptible press/entry motion.

## Build locally

From this directory, with Java 17 and the Android SDK installed:

```sh
gradle --no-daemon :app:assembleDebug
```

The APK is written to
`app/build/outputs/apk/debug/app-debug.apk`.

## GitHub Actions

`.github/workflows/android-apk.yml` builds the debug APK on changes under
`android/` and also supports **Run workflow**. Pushing a tag matching
`android-v*` attaches the APK to a GitHub release.

The mobile surface connects to a real OpenCode-compatible server. Enter the
server URL and bearer token on the first screen, then the app calls `/api/info`,
creates a real session at `/api/session`, and sends prompts to
`/api/session/{id}/prompt`. Assistant text comes from the server response; the
APK contains no demo responder or hardcoded AI answer.

For an emulator, a local server at `localhost:4096` is reached with
`http://10.0.2.2:4096`. For a physical phone, run the server on a reachable
LAN address and use that address in the app. Use HTTPS for production servers.
