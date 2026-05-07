# Contributing

Thanks for your interest in `react-native-cover`. This guide covers the development workflow, the example app, and the end-to-end test suite.

## Development workflow

```bash
npm install                # installs deps and runs nitrogen
npm run typecheck          # TypeScript
npm run specs              # re-run nitrogen after editing *.nitro.ts
npm run build              # bob build (commonjs + module + types)
```

If you change the TS spec in `src/specs/cover.nitro.ts`, **commit the regenerated `nitrogen/generated/` files alongside it** — that's how autolinking discovers the module on iOS and Android. CI will fail if `nitrogen/generated` is out of sync with the spec.

After any change to the example app or the native code, re-run the Maestro suite (see below) on at least one platform.

See the [PR template](.github/PULL_REQUEST_TEMPLATE.md) and [issue templates](.github/ISSUE_TEMPLATE/) for what to include when you open one.

## Module layout

```text
react-native-cover/
├── src/
│   ├── index.ts                       # public JS API
│   └── specs/
│       └── cover.nitro.ts             # Nitrogen TypeScript spec
├── ios/
│   └── HybridCover.swift              # iOS implementation
├── android/
│   ├── build.gradle, CMakeLists.txt, …
│   └── src/main/java/
│       ├── com/margelo/nitro/cover/HybridCover.kt   # Android implementation (must live in this package — autolinking lookup)
│       └── com/cover/CoverPackage.kt                # ReactPackage shell
├── nitrogen/generated/                # checked-in Nitrogen output
├── nitro.json                         # autolinking + module config
├── Cover.podspec
├── example/                           # Expo example app
└── .maestro/
    └── flows/                         # e2e tests
```

## Example app

There is a fully working example in [`example/`](./example) — an Expo app that consumes the local module and demonstrates every API surface.

### Run it

You need:

-   Node 22+
-   Xcode 15+ and an iOS simulator (for iOS)
-   Android Studio + an emulator or a physical device (for Android)
-   CocoaPods 1.16+ (`brew install cocoapods`)

```bash
# from the repo root
npm install                 # installs the module's dev deps + runs nitrogen
cd example
npm install                 # installs the example app's deps
npx expo prebuild --clean   # generates ios/ and android/

# iOS
npm run ios

# Android
npm run android
```

The example surfaces every API:

| Control                                    | What it does                                                                                        |
| ------------------------------------------ | --------------------------------------------------------------------------------------------------- |
| **Enable / Disable cover**                 | `Cover.enable()` / `Cover.disable()`                                                                |
| **Black / Indigo / Crimson / Transparent** | `Cover.setColor("#…")`                                                                              |
| **Add icon as overlay**                    | `Cover.setImage({ uri, resizeMode, x, y, width, height })` (toggles `clearImage()` on a second tap) |
| **Resize: Cover/Contain/Stretch/Center**   | Re-issues `setImage` with the new resize mode                                                       |
| **Position X / Y**                         | Re-issues `setImage` with the new anchor                                                            |
| **Size: Full / 120 / 240**                 | Re-issues `setImage` with width=height=value (square box)                                           |
| **Light / Dark / Regular / ExtraLight**    | `Cover.setBlur(style, intensity?)`                                                                  |
| **Off / 200ms / 600ms**                    | `Cover.setFade(durationMs)`                                                                         |
| **Show cover now**                         | `Cover.show()` and presents an alert; tapping OK calls `Cover.hide()`                               |

Sticky header shows `Status` (`ENABLED`/`DISABLED`) and `Mode` (a live summary like `COLOR #1E1B4B + IMG contain/cb/240` or `BLUR DARK`).

Try this:

1. Tap **Enable cover**.
2. Build the cover you want — e.g. **Indigo** + **Add icon as overlay** + **Center / Bottom** + **240**.
3. Press the home button (iOS simulator: `Cmd ⇧ H`, Android emulator: home toolbar button).
4. Open the App Switcher / Recents screen — the thumbnail is your cover.
5. Tap the app to come back; the cover fades out per the configured `setFade(...)` duration.

## End-to-end tests (Maestro)

End-to-end coverage lives in [`.maestro/flows/`](./.maestro/flows) and runs via [Maestro](https://maestro.dev/).

| Flow                    | What it asserts                                                                                                                                                                                                                                                   |
| ----------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `cover-toggle.yaml`     | `enable()` / `disable()` flip the status text and `setColor(hex)` updates the visible mode summary correctly.                                                                                                                                                     |
| `manual-show-hide.yaml` | `Cover.show()` mounts the cover; dismissing it via `hide()` makes the React UI interactable again — proves the JS API binding round-trips.                                                                                                                        |
| `lifecycle-cover.yaml`  | After `pressKey: Home` and re-foregrounding, the React UI is reachable and no `CoverOverlay` is left on screen — proves the foreground hook removes the view it added in `didEnterBackground` (iOS) / from the `ACTION_CLOSE_SYSTEM_DIALOGS` broadcast (Android). |
| `modes.yaml`            | Color + image combine; switching to blur visually replaces them; switching back via `setColor` exits blur. Mode label tracks every transition and a manual `show()` smoke-tests each combination.                                                                 |
| `image-position.yaml`   | Re-issuing `setImage` with each resize mode, each (x, y) anchor, and each size (`full` / `120` / `240`) updates the live mode summary — proves the native side accepts and applies every combination.                                                             |
| `fade.yaml`             | `setFade(durationMs)` accepts every duration the example exposes (`0`, `200`, `600` ms) and a subsequent show/hide round-trip with a non-zero duration completes without leaving the cover behind.                                                                |
| `blur-intensity.yaml`   | The four exposed `setBlur` intensities (`0.2`, `0.4`, `0.7`, `1.0`) all round-trip through the JS bridge and update the live mode summary; a subsequent `show()` smoke-tests each.                                                                                |
| `modal-coverage.yaml`   | While a React Native `<Modal>` Dialog is open, mounting the cover paints above the Dialog and absorbs taps targeted at modal controls — guards against the cover regressing below RN's separate top-level Dialog window.                                          |

### Caveat — what Maestro can _not_ test

Maestro reads the accessibility tree of your app's process. It cannot inspect the iOS App Switcher or the Android Recents thumbnail — those are rendered by the OS and aren't in your hierarchy. The flows therefore assert the **lifecycle behavior** that the OS uses to decide what to put in the thumbnail. To eyeball the thumbnail itself, use the manual steps in the example app section above.

### Run the suite

```bash
# install Maestro once
curl -Ls "https://get.maestro.mobile.dev" | bash

# boot a device, install the example app (see "Example app" above), then:
maestro test .maestro/flows/

# or a single flow
maestro test .maestro/flows/lifecycle-cover.yaml
```

The flows are written to run unchanged on **both iOS and Android** — Maestro abstracts the driver and `testID` props in `App.tsx` map to both `accessibilityIdentifier` (iOS) and `resource-id` (Android).
