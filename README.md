# OmniRush Android

An Android chat surface for OmniRush with a calm, Apple-inspired liquid-glass
visual system and Jetpack Compose motion.

## Build

GitHub Actions builds the debug APK on every change under `android/` and from
**Actions → Android APK → Run workflow**. The generated artifact is named
`omnirush-android-debug`.

```sh
cd android
gradle --no-daemon :app:assembleDebug
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

## UI skills

The requested UI/UX references are installed under `.opencode/skills/`:

- UI UX Pro Max
- Apple Design
- Animate
- Mobile Native

The first mobile surface uses a local preview responder so the chat flow can be
tested without credentials. The existing OmniRush transport can be connected
behind the same Compose presentation in a later adapter.
