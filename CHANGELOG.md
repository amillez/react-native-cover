# Changelog

## 0.1.2 - 2026-05-07

- Android: fix soft keyboard input being swallowed by the pre-mounted cover panel. The invisible panel now sets `FLAG_ALT_FOCUSABLE_IM` alongside `FLAG_NOT_TOUCHABLE` so the IME sits above it and keystrokes reach the focused `TextInput`; both flags are cleared again when the cover becomes visible so it still paints over any lingering keyboard.

## 0.1.1 - 2026-05-07

Initial release.

- Native privacy cover for React Native built on Nitro Modules.
- iOS: hides app content behind an overlay in the App Switcher.
- Android: hides app content in the Recents screen.
