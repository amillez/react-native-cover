<!--
Thanks for opening a PR! Please make sure your changes follow the
contribution guidelines and that the checklist below is satisfied.
-->

## Summary

<!-- One or two sentences describing what this PR does and why. -->

## Type of change

- [ ] Bug fix (non-breaking change which fixes an issue)
- [ ] New feature (non-breaking change which adds functionality)
- [ ] Breaking change (fix or feature that would cause existing behavior to change)
- [ ] Documentation only
- [ ] Build / CI / tooling

## Scope

- [ ] iOS implementation (`ios/HybridCover.swift` and friends)
- [ ] Android implementation (`android/src/main/java/com/margelo/nitro/cover/HybridCover.kt` and friends)
- [ ] TypeScript spec (`src/specs/cover.nitro.ts`) — **regenerate with `npm run specs`**
- [ ] Example app (`example/`)
- [ ] Maestro flows (`.maestro/flows/`)
- [ ] Documentation (`README.md`)

## Checklist

- [ ] I ran `npm run typecheck` and it passes
- [ ] I ran `npm run specs` after editing any `*.nitro.ts` file and committed the regenerated `nitrogen/generated/` files
- [ ] I added or updated Maestro flows for any user-visible behavior change
- [ ] I tested the change in the example app on iOS **and** Android
- [ ] I updated the README if the public API or example app changed

## How to test

<!-- Concrete steps for a reviewer to verify the change locally. -->

```bash
cd example
npm install
npm run prebuild
npm run ios     # or: npm run android
```

## Related issues

<!-- Closes #N, fixes #N, refs #N -->
