# IPTV Player

A lightweight IPTV player for low-spec Android TV boxes (1 GB RAM, ~1 GHz), modelled on IBO Player Pro.
Users bring their own M3U URL or Xtream Codes login. The app ships no channels, playlists or provider links.

**Status:** early skeleton. The playlist parsers (M3U, Xtream) are in place with unit tests; screens come next.

- MVP document: https://claude.ai/code/artifact/f7b3b895-aef4-4a3d-9555-9848fd1bd09e
- Rules for contributors (and Claude Code): [CLAUDE.md](CLAUDE.md)

## Get a test APK

Every push to `main` builds a release APK in GitHub Actions.

1. Open the **Actions** tab, pick the latest green **Build** run.
2. Download the `iptv-player-<run>` artifact (a zip containing the APK).
3. Sideload it onto the box (e.g. with `adb install -r app-release.apk`, or a USB stick and a file manager).

Builds are signed with a **test key** committed in `keystore/` so they install over each other.
Do not publish an APK signed with that key.

## Build locally

Requires JDK 17 and the Android SDK (compileSdk 36).

```bash
./gradlew testReleaseUnitTest assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`. CI fails the build if it reaches 15 MB.

## Layout

```
app/src/main/java/io/github/sardinemehico/iptvplayer/
  MainActivity.kt            single activity, plain Views
  data/model/Content.kt      ContentType, Category, Entry
  data/source/M3uParser.kt   streaming M3U/M3U8 parser
  data/source/Xtream.kt      Xtream login + URL builder
  data/source/XtreamParser.kt streaming parsers for player_api.php responses
  data/source/JsonPull.kt    tiny lenient streaming JSON reader
```
