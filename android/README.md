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

The default mobile surface runs the real OmniRush CLI locally. The APK build
embeds PRoot, both architecture-specific Ubuntu rootfs archives, Node.js 22,
and `omnirush@2.2.1` with its native platform runtime. On first launch the app
unpacks the matching files into its private sandbox, starts the real
`omnirush login` flow, and executes real `omnirush -p` prompts from the native
chat UI.

To prepare the same assets locally before building:

```sh
python3 scripts/prepare-runtime-assets.py
gradle --no-daemon :app:assembleDebug
```

The earlier remote OpenCode API adapter is retained in the source for servers
that you prefer to run elsewhere. Use HTTPS for production remote servers.
