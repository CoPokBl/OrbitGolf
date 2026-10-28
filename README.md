# Orbit Golf

A one-tap space golf game for Android. Your ball orbits a planet. Tap to break away, then let gravity slingshot you through rings and around planets into the cup.

- **One input, lots of depth.** Timing your release is everything. Rings catch you mid-flight so you can chain hops, bare planets bend your path (and crash you), and the green around the cup pulls in near-misses.
- **32 handmade holes** across four worlds:
  - **Launch Pad:** rings, boosters (orange) and fast spinners (purple).
  - **Gravity Well:** slingshots and asteroid fields.
  - **Bank & Warp:** walls, wormholes and pulsars that push you away.
  - **Clockwork:** moving planets.
- **Endless mode:** freshly generated holes that ramp up in difficulty. Each one is checked by a built-in solver before you see it, so it's always beatable. Track your run score against par and your at-or-under-par streak.
- **Pick up and put down.** The app opens straight into the hole you were playing. Leaving mid-shot rewinds to just before that shot, and you tap once to continue.
- **Tiny and clean.** About 70 KB in release, with no Google Play services, no AndroidX, no ads, no network and no tracking. The only permission is `VIBRATE`. Every sound is synthesised on first launch.

## Install on your phone

Grab the APK from the latest GitHub release, or build one yourself (signed with the local debug key unless a release key is configured; see below):

```bash
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/app-release.apk`. Copy it to your phone and open it, allowing "install unknown apps" for your file manager when Android asks. Or install over USB:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Building needs JDK 17+ and the Android SDK (platform 35). Point `local.properties` at it with `sdk.dir=/path/to/sdk`, or set `ANDROID_HOME`.

## Releases (GitHub + Obtainium)

`.github/workflows/release.yml` runs on every push to `main` (pushes that only change docs are skipped). It builds, tests and signs the APK, then publishes it as a new GitHub release tagged `v1.0.N`, where N is the workflow run number. N only ever goes up, so each release installs as an upgrade. You can also trigger it by hand from the Actions tab with "Run workflow".

To get updates in Obtainium, add `https://github.com/CoPokBl/OrbitGolf` as an app.

**One-time setup: a signing key.** Every release must be signed with the same key, or Android refuses the update. Create one and keep a backup somewhere safe:

```bash
keytool -genkeypair -keystore orbitgolf.jks -alias orbitgolf -keyalg RSA -keysize 4096 -validity 20000 -dname "CN=Orbit Golf"
```

Then add these repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `ORBIT_KEYSTORE_BASE64` | output of `base64 -w0 orbitgolf.jks` |
| `ORBIT_KEYSTORE_PASSWORD` | the keystore password |
| `ORBIT_KEY_ALIAS` | `orbitgolf` |
| `ORBIT_KEY_PASSWORD` | the key password (same as the keystore password unless you chose otherwise) |

Never commit the `.jks` file. Local builds use the same key if you set `ORBIT_KEYSTORE`, `ORBIT_KEYSTORE_PASSWORD` and `ORBIT_KEY_ALIAS` in the environment; otherwise they fall back to the debug key.

## How to play

- **Tap anywhere** to launch the ball along its orbit. The dotted preview shows the first moment of your flight.
- **Dotted rings** catch the ball. Tap again to launch from there. Each tap is a stroke.
- **Touching a planet, or flying off the map,** resets you to where you took the shot.
- **Stars:** 3 for par or better, 2 for up to two over, 1 for finishing. Par is always reachable along a comfortable route, and most holes also hide a much harder shortcut.
- The faint line is your previous shot, so you can adjust your timing.
- **Back** or the ❚❚ button pauses (sound and vibration toggles are there). ↻ restarts the hole.

## Project layout

```
app/src/main/java/com/copokbl/orbitgolf/
  sim/World.kt      deterministic 120 Hz physics (pure Kotlin, no Android)
  sim/Levels.kt     the 32 campaign holes, written in a small DSL
  sim/Solver.kt     brute-force solver: tries every release step, needs a human-sized timing window
  sim/Generator.kt  endless-mode hole generator (solver-verified)
  GameView.kt       rendering, input, screens (single Canvas view, Choreographer loop)
  Sfx.kt            synthesised sound effects + haptics
  Save.kt           SharedPreferences persistence
  MainActivity.kt   fullscreen activity + lifecycle
```

The physics is deterministic and independent of frame rate, so the unit tests replay the game exactly:

```bash
./gradlew testDebugUnitTest
```

- `LevelsTest` checks that every campaign hole's par equals its comfortable-route stroke count. Comfortable means every release window is at least 10 sim steps (about 83 ms).
- `GeneratorTest` checks that endless holes generate quickly and rebuild identically from their seed.
- `DebugMap` (run with `ORBIT_DEBUG=3,7`) prints a release-timing map of a level's first shot, which is handy when designing holes.
- `Screenshots` renders real frames to PNGs with Robolectric, without needing a device:

  ```bash
  ./gradlew testDebugUnitTest --tests '*Screenshots*' -Dshots.dir=/some/dir
  ```
