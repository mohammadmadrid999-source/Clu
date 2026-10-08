# Clu — motion controls for accessible gaming

Clu is a native Android app (Kotlin) that lets people with motor and physical disabilities play
touch-screen games with small movements of the phone, hands or head. It turns device orientation
into a virtual joystick drag and button taps, injected over any game by an accessibility
service. It is designed for cerebral palsy, spinal cord injury, muscle weakness and
tremor-prone conditions.

**Highlights**

- **Tremor and spasm cancellation.** An adaptive One Euro filter (or Kalman), a spasm gate that
  freezes and then glides, and auto-tuning from tremor measured at calibration.
- **Any posture is "center".** A one-tap recenter averages a short hold-still window, whether
  sitting, reclined, lying down or head-mounted. "Learn my moves" captures *your* comfortable
  range of motion in each direction, and full speed needs only 70 % of it.
- **Small movements, full range.** Deadzone without a jump at its edge, power and S-curves,
  game-deadzone compensation, and direction snapping or 4/8-way output.
- **Hands-free actions.** Dwell (hold a direction), flicks and twists, switch interfaces, volume
  and headset keys, and an optional mouth-click sound trigger, all bindable to tap, hold or toggle
  on-screen buttons, or to recenter, pause and profile switching.
- **Safety and comfort.** Auto-pause on drops and erratic motion, rest reminders, fatigue hints,
  no injection into Settings, launchers or Clu itself, and no auto-resume after a drop.
- **Floating HUD.** A spirit-level tilt indicator, big Pause/Recenter/Profile/Layout buttons and
  an over-the-game layout editor. Everything is operable by touch, Voice Access, TalkBack,
  switches and notification actions.
- **No `SYSTEM_ALERT_WINDOW`, no `INTERNET` permission.** Overlays are accessibility overlays,
  and motion and audio data never leave the device.

## Documentation

- **[docs/POCO_X7_PRO.md](docs/POCO_X7_PRO.md)**: install, setup and a 5-minute device check on
  the POCO X7 Pro (Android 16, Xiaomi HyperOS), the first target phone.
- **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**: system architecture, signal pipeline,
  continuous gesture injection (including Android 16's changed real-touch handling), latency
  budget, permissions and platform policy, test coverage and verification status.

## Code map

| Path | What |
|---|---|
| `app/src/main/java/com/clu/motion/sensor/MotionProcessor.kt` | Sensor listener, fusion-sensor selection, sensor thread |
| `app/src/main/java/com/clu/motion/core/` | Pure-Kotlin DSP: pipeline, filters, curves, dwell/flick, calibration, safety, touch planner |
| `app/src/main/java/com/clu/motion/input/InputDispatcherService.kt` | AccessibilityService: continuous gesture injection, key/switch filter |
| `app/src/main/java/com/clu/motion/input/GestureStreamer.kt` | `continueStroke` streaming on top of `dispatchGesture` |
| `app/src/main/java/com/clu/motion/overlay/OverlayController.kt` | Floating HUD, touch visualizer, layout editor |
| `app/src/main/java/com/clu/motion/engine/MotionEngine.kt` | Session state machine and trigger → action routing |
| `app/src/main/java/com/clu/motion/service/MotionSessionService.kt` | Foreground service and notification controls |
| `app/src/main/java/com/clu/motion/ui/MainActivity.kt` | Setup checklist, live preview, tuning, bindings |

## Build and test

Requirements: JDK 17+ and the Android SDK (platform 36). Point `local.properties` at it with
`sdk.dir=/path/to/sdk`, or set `ANDROID_HOME`.

**Ready-made APK:** every push to a branch publishes a test-signed build as a GitHub prerelease
named `test-build-<branch>` (see Releases), so you can install it straight from the phone.
Builds share one committed test key and use the commit count as `versionCode`, so they update
each other in place.

```bash
./gradlew test             # 98 JVM unit tests: signal chain, touch planner, self-test, policies
./gradlew lintDebug        # Android Lint
./gradlew assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
```

## Getting started on a phone

On Xiaomi, POCO or Redmi phones, follow [docs/POCO_X7_PRO.md](docs/POCO_X7_PRO.md) instead: HyperOS
needs a few extra settings.

1. Install the APK and open **Clu**.
2. Turn on **Clu motion controls** under **Settings → Accessibility**. If Android says
   "Restricted setting" (APK installed from a file on Android 13+), follow the three ordered
   steps shown in Clu's Setup section.
3. Allow notifications to get Pause, Recenter and Stop in the notification shade.
4. Pick a profile, hold the phone (or your head) comfortably and tap **Start**. Hold still for
   about 2 seconds while Clu sets your center.
5. Open your game, tap **Layout** on the Clu panel, and drag the stick and buttons A–D onto the
   game's own controls (or use the arrow buttons). Tap **Save**.
6. Optional: tap **Learn moves** and follow the prompts so Clu learns your range of motion.

> **Status:** the Android code compiles, passes Lint and builds a minified release, and the
> core logic is unit-tested. It has not yet been run on a physical device or emulator. Clu
> includes its own on-device check (Setup › **Injection test** and **Share device report**); run
> it first on any new phone.
