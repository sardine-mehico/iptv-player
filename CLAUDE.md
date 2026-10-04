# CLAUDE.md

IPTV player for low-spec Android TV boxes, modelled on IBO Player Pro.
MVP document: https://claude.ai/code/artifact/f7b3b895-aef4-4a3d-9555-9848fd1bd09e

## Product rules
- **IBO parity:** if IBO Player Pro doesn't have a feature, don't build it. Exceptions: auto-start on boot, and an
  optional 6-digit PIN per playlist that guards its details (URL, username, password) and deletion. That PIN is
  not a parental lock: it never gates watching.
- **Not built:** cloud/website playlist management, activation/licensing, parental PIN, hidden categories,
  recent-channels list, multi-screen, recording, Stalker portals, PiP, USB playlist files, number-key zap.
- No built-in content, playlists or provider links, ever.

## Hard limits
- **Release APK < 15 MB** (CI fails at 15 MB; expect 5–8 MB). Check size impact before adding any dependency.
- **No native code** (no FFmpeg, no libVLC). Unsupported streams go to an external player, as IBO does.
- minSdk 24, targetSdk 36. English only (`resourceConfigurations = en`) until translations exist.

## Performance rules (target: 1 GB RAM, Cortex-A53, Mali-400/450)
- The UI thread only draws. No disk, network, JSON or DB work on it.
- Never hold a whole playlist in memory: parsers stream (`onEach` callbacks), screens page from SQLite.
- One ExoPlayer instance for the app's lifetime; zapping swaps the MediaItem. One `SurfaceView`, never hidden.
- Plain Views + RecyclerView, no Compose, no Fragments, no AppCompat/Material Components.
- Flat row layouts, no elevation/shadows/blur, fixed-size rows, stable ids.
- Images decoded at view size; no transformations.

## Code layout
- `data/source/` parsers are pure Kotlin (no Android imports) so they run as JVM unit tests.
- Xtream panels are inconsistent: read every field leniently (`JsonPull.nextStringOrNull/nextLongOrNull`),
  treat every field as optional.

## Build and test
- `./gradlew testReleaseUnitTest assembleRelease`
- Releases: bump `versionCode`/`versionName`, build locally, push, then create a GitHub Release (tag = version,
  pre-release) with `iptv-player-<version>.apk` attached.
- CI: `.github/workflows/build.yml` is manual-only (`workflow_dispatch`) to save Actions minutes. It runs tests,
  builds the release APK, enforces the size cap, uploads the APK.
- Release builds use the committed **test** keystore (`keystore/test-release.jks`). Never ship that key.
