# AGENTS.md

Guide for AI coding agents and for contributors who
want the technical background. People installing the app should start with [README.md](README.md).

## What this is

TV Ambient is an ambient-light app for Android TV 10+ (minSdk 29, target 34). It samples the edges of the
picture and drives an addressable LED strip behind the TV through a USB serial controller speaking the
**Adalight** protocol. Plain Java, no AndroidX UI, two small dependencies.

Reference setup it was tuned on: Haier K66 43" (MediaTek/MStar `m7332` board, Android TV 14, firmware
`UKNV.260408.001`, 1.4 GB RAM), a Wemos D1 mini-style ESP8266 board with a CH340 USB chip running Adalight at
230400 baud, and a 184-LED WS2812B strip (56 top, 36 right, 56 bottom, 36 left).

## Repo map

| Path | What it is |
|---|---|
| `app/src/main/java/dev/tvambient/AmbiService.java` | Foreground service: capture paths, frame hand-over, smoothing, LED output loop, USB connect |
| `.../ShellCapture.java` | Runs as the **shell user** via `app_process`; one-shot scaled screenshots to stdout |
| `.../FrameAnalyzer.java` | Per-LED averages from a frame, black-bar (letterbox) detection with hysteresis |
| `.../LedLayout.java` | Sampling rectangle per physical LED: sides, bottom gap, direction, offset |
| `.../ColorProcessor.java` | Black threshold, saturation, then one LUT per channel (gamma × balance × brightness) |
| `.../AdalightPort.java` | USB serial (usb-serial-for-android) and the Adalight packet |
| `.../Effects.java` | Colour and effect modes |
| `.../Prefs.java` | Every setting, its range and **default** (the calibrated values); schema reset on renumbering |
| `.../MainActivity.java` | D-pad settings screen (built in code, no layouts) |
| `.../ProjectionActivity.java` | Asks for screen recording consent (fallback capture path) |
| `.../SettingsReceiver.java` | ADB-only (`android.permission.DUMP`) broadcast to change settings or start capture |
| `.../BootReceiver.java` | Start on boot |
| `docker/Dockerfile`, `scripts/build.sh`, `scripts/build.ps1` | Reproducible build: JDK 17 + Android SDK 34 in Docker |
| `docs/screenshots/` | Images used by the README |

## Build and install

```bash
scripts/build.sh                                  # dist/tv-ambient-<version>.apk, debug-signed
KEYSTORE_DIR=/path/to/keys scripts/build.sh       # release-signed (keys/keystore.properties)
adb push dist/tv-ambient-1.3.0.apk /data/local/tmp/tv-ambient.apk
adb shell pm install -r /data/local/tmp/tv-ambient.apk
```

`adb install` can hang on Android 14 waiting for an on-screen confirmation; `push` + `pm install -r` avoids it.
An APK signed with a different key will not install over an existing one: uninstall first (the app's ADB key
and USB permission are then requested again).

Before a PR: the build must pass, the APK must install, and a change to the capture or output path needs the
numbers from "Measuring" below, before and after.

## How capture works, and why

On this class of TV (MediaTek/MStar), decoded video sits on a hardware video plane. Every time Android
composes a screen capture that includes it, the vendor display service `mstar.hardware.mmdisp@1.0-service`
copies the video plane **on the CPU**.

* A **MediaProjection mirror** (what other ambient-light apps use) is composed on every video frame, so the copy
  runs 24-30 times a second: `mmdisp` at 60-80% of a core, and 1080p video in the player drops to 13-17 fps.
* **Shell screenshots** (default, `METHOD_SHELL`): the app connects to the TV's own adbd at `127.0.0.1:5555`
  with its own RSA key (`files/adbkey`, generated on first run) using `dadb`, and runs
  `CLASSPATH=<apk> app_process / dev.tvambient.ShellCapture 128 72 <fps>` through the `exec:` service.
  ShellCapture calls `ScreenCapture.captureDisplay` (hidden API, allowed for the shell user) with a 128×72 size,
  the same path `screencap` uses, and writes frames `"TVAF", w, h, w*h*3 RGB` to stdout. Nothing is composed
  between screenshots, and the player keeps its frame rate.
  * Android 14 moved the display token to `com.android.server.display.DisplayControl` in `services.jar`;
    ShellCapture loads it with `ClassLoaderFactory` and `libandroid_servers`, falling back to
    `SurfaceControl.getInternalDisplayToken()` on older releases.
  * Native libraries may print to stdout first; the reader resyncs on the `TVAF` marker.
  * Writes go to the raw fd 1 so the helper exits when the app closes the stream.
  * Requirements: USB debugging on (on many TVs that also keeps network ADB open on 5555) and "Allow USB debugging"
    accepted once for the app's key. That gives the app shell-level access, the same model as Shizuku.
* **Screen recording** (`METHOD_PROJECTION`): MediaProjection fallback for TVs without ADB. Same cost as the
  other apps.

LED output runs on its own thread at `out_fps` (30): exponential smoothing between captured frames, frames sent
only when they change (plus a 500 ms keep-alive). A black-frame guard drops a single all-black capture after a lit
one unless the next capture agrees.

### Measured on the reference TV

Nuvio, 1080p AVC 23.98 fps, frames delivered by the player's SurfaceView layer over ~126 frames:

| Capture | `mmdisp` | Video delivered | Gaps > 60 ms |
|---|---|---|---|
| none | ~0% | 23.98 fps | 0 |
| MediaProjection mirror | 58-79% | 13-17 fps | 70-126 |
| Shell screenshots 10/s | 60% | 23.97 fps | 3 |
| Shell screenshots 20/s | 75% | 23.38 fps | 14 |

SmartTube, shell screenshots at 30/s, LEDs on. SmartTube draws these videos into its own window rather than a
SurfaceView, so pacing comes from `dumpsys SurfaceFlinger --latency` on the PlaybackActivity window layer, and
`mmdisp` stayed out of the top processes:

| Video | Delivered (3 samples of ~126 frames) | Frames over 20 ms | Worst frame | ShellCapture / app CPU |
|---|---|---|---|---|
| 1080p 60 fps | 57.9-58.4 fps, mean 58.3 | 3-4 | 34.0 ms | 19% / 9% |
| 2K 60 fps | 55.8-58.9 fps, mean 57.4 | 2-9 | 34.2 ms | 20% / 10% |

4K 25 fps: the window layer mixes 60 Hz redraws with video frames, so frame timing is unreliable there. Count the
decoder's drops instead: `logcat | grep -E 'MediaCodecVideoRenderer: (dropOutputBuffer|skipOutputBuffer)'`.
Result: 4 dropped in 60 s (~0.3% of ~1,500 frames). Check `dumpsys media_session` for `BUFFERING(6)` before
blaming capture: on a slow network a 4K stream rebuffers and the gaps look like stutter.

### Tried and removed; do not reintroduce without new evidence

| Idea | Result |
|---|---|
| Duty-cycling the virtual display (`setSurface(null)` between frames) | `mmdisp` 20%, but every attach/detach is a display state change broadcast to all apps: SurfaceFlinger, system_server and the player rose to 40-46%. Worse overall. |
| Holding ImageReader buffers (back-pressure) | SurfaceFlinger composes anyway: no gain. |
| Android 14 `VirtualDisplayConfig.setRequestedRefreshRate` via the hidden `MediaProjection.createVirtualDisplay(VirtualDisplayConfig.Builder, ...)` | Worked (`renderFrameRate 10.0`, video 23.4 fps) but needed `hidden_api_policy=1` and **crashed SurfaceFlinger** on the reference firmware (null pointer in `Output::updateColorProfile`); with start on boot it became a restart loop. |
| Vsync-lateness "adaptive" rate | The app's own Choreographer never saw the player's dropped frames. |
| `dumpsys gfxinfo <player>` as a jank metric | Counts the player's UI, not video frames. Use the SurfaceView layer (below). |

## Measuring

```bash
# CPU per process while a video plays
adb shell top -b -d 5 -n 2 -m 15
# Video frame pacing: find the player's SurfaceView(BLAST) layer, then read present times
adb shell dumpsys SurfaceFlinger --list | grep 'SurfaceView\[.*(BLAST)'
adb shell dumpsys SurfaceFlinger --latency '<layer name>'
# App statistics every 5 s
adb logcat -s TvAmbient
```

From `--latency`, take the second column (actual present time, ns), sort, and look at the intervals: mean
interval → fps, and count intervals over 60 ms (a 24 fps video on a 60 Hz panel alternates 33/50 ms).

Change settings while a video plays (opening the app would pause it), ADB only:

```bash
adb shell am broadcast -a dev.tvambient.SET -n dev.tvambient/.SettingsReceiver --ei rate 2 --ez start true
```

Keys and ranges are in `Prefs.java` (`method` 0 shell / 1 screen recording; `rate` index into 10/15/20/30 fps).

## Rules for changes

- Keep the defaults in `Prefs.java` equal to the reference calibration unless the change says why.
  Renumbering settings means bumping `Prefs.SCHEMA` (stored values are reset).
- No network access beyond the TV's own `127.0.0.1:5555`. No analytics, no telemetry.
- No new dependencies without a reason in the PR.
- Never ship a capture path that can crash or restart system processes; test on a real TV, including a reboot
  with start on boot on.
- Don't commit APKs, keystores, `keystore.properties`, ADB keys, or screenshots that show personal data
  (Wi-Fi names, accounts, faces).
- Commit style: `area: what changed` (for example `capture: drop frames older than one tick`).
