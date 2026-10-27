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

The APK now has an on-device mode. The release workflow embeds PRoot, a
verified Termux Ubuntu rootfs, Node.js 22, and the published OmniRush CLI in
the APK. On first launch those files are unpacked locally, the real
`omnirush login` flow opens, and real `omnirush -p` prompts run from the glass
chat UI. It does not ship a fake responder or hardcoded AI answer.

The remote OpenCode-compatible connection remains in the source as a fallback
adapter. The default experience is the local Linux runtime requested for
Android; the final APK is large because it contains both arm64 and x86_64
runtime assets.
