# Clu on the POCO X7 Pro (Android 16, Xiaomi HyperOS)

This is the first target phone. HyperOS is one of the hardest Android versions for accessibility
apps: it freezes and force-stops background apps, and a force stop switches an accessibility
service off. Follow these steps in order. A helper can make the first-time setup easier, because
some confirmations have countdowns and PIN prompts.

## 1. Install

**Option A, on the phone itself (easiest).**

1. On the phone, open
   `https://github.com/mohammadmadrid999-source/Clu/releases/tag/test-build-ccr-2baa9c81-ris2p2`
   and download `clu-<version>.apk`. Every push to the branch rebuilds this release.
2. Open the downloaded file. If asked, allow your browser or the Files app to "Install unknown
   apps".
3. If Google Play Protect warns about the app, choose **Install anyway** if offered. If it
   refuses outright ("blocked because it requests access to sensitive data"), use option B.
   Play Protect's enhanced fraud protection can block internet-sideloaded apps that use
   accessibility.

Installing from a file marks Clu as "restricted", so you'll need step 2b below.

**Option B, from a computer with `adb`.** Not restricted on standard Android, and it bypasses
Play Protect's sideload check.

1. Settings › About phone › tap **OS version** 7 times to enable Developer options.
2. Settings › Additional settings › Developer options: turn on **USB debugging** and
   **Install via USB**. Xiaomi requires a signed-in Mi account for this, and some users also
   needed a SIM / mobile data.
3. `adb install -r clu-<version>.apk`

Updates install over the previous version (same signing key), so the accessibility switch and
your profiles survive. Don't install or set up Clu during a call with a number that isn't in your
contacts: Android 16 blocks app installs during such calls.

## 2. Turn on the accessibility service

1. Open **Clu** and tap **Accessibility settings**. On HyperOS, Clu is listed under
   **Downloaded apps** (or "Installed services").
2. **If you see "Restricted setting"** (expected after option A), do these steps in this order,
   because the menu item in (b) only appears after (a):
   1. Try to turn Clu on, read the "Restricted setting" message and tap **OK**.
   2. In Clu tap **App info** → **⋮** (top right) → **Allow restricted settings**, then confirm
      with your PIN or fingerprint.
   3. Go back to Accessibility settings and turn Clu on.
3. Xiaomi then shows a warning whose **OK button stays locked for a 10-second countdown**. Wait,
   then confirm. Android's own "full control" consent follows.

The Setup section in Clu says "Accessibility service: on" when it worked. If it says "turned on
but not running", toggle Clu off and on again (restart the phone if needed).

*Developer fallback for restricted settings:*
`adb shell cmd appops set com.clu.motion ACCESS_RESTRICTED_SETTINGS allow`. This needs Developer
options › **USB debugging (Security settings)**.

## 3. Keep HyperOS from stopping Clu (once)

Clu shows these items, with buttons, in its **Xiaomi, POCO and Redmi (HyperOS)** section:

1. **Battery saver → No restrictions** (button). This is the setting that exempts an app from
   HyperOS's process freezer. AOSP's battery-optimisation list is not enough on Xiaomi.
2. On the same page, turn off **Pause app activity** if it's there.
3. **Autostart → allow Clu** (button).
4. **Lock Clu in Recents**: open Recents, then pull Clu's card down, or long-press it and tap the
   padlock. Locked apps survive "clear all".
5. **Don't use Ultra battery saver while playing.** It force-stops apps, and a force stop turns
   Clu's accessibility service off. The same goes for **Force stop** in App info and for
   phone-cleaner tools. If that happens, Clu's Setup section says so; just turn the service on
   again.

**Game Turbo**: in its Game DND settings, keep **"Don't open notification shade"** off if you use
Clu's notification buttons (Pause / Recenter / Stop). Turn on **"Restrict screenshot gestures"**
so a three-finger swipe can't interrupt touches. Names can differ slightly between versions.

**Keys and switches**: if a switch interface or keyboard key does nothing, check that
Accessibility › **Mouse keys** is off. Android 16's Mouse keys feature consumes those keys before
Clu sees them.

## 4. Check this phone (5 minutes)

1. Clu › Setup › **Injection test**. The test screen opens in landscape.
2. Tap **Synthetic test**. A virtual stick circles and button A is tapped every 1.2 s, with no
   sensors involved. You should see a continuous yellow trail and green/white dots for A.
3. While it runs, **touch the pad once with a real finger**. You should see a red ✕ (Android
   cancelled Clu's touch), then a new green dot about a third of a second later (Clu re-pressed).
   This checks the Android 16 behaviour described in ARCHITECTURE.md §4, rule 7.
4. Tap **Share**, or **Copy report**, and send the report back.

What good looks like in the report:

| Line | Expect | If not |
|---|---|---|
| Gestures sent / completed | About equal; rejected 0 | Rejected > 0: a continuation rule differs on this ROM |
| Received CANCEL | Only around your own real touch | Extra CANCELs: something else interrupts injection (Game Turbo, an overlay) |
| MOVE gap p95 | About one display frame (8–17 ms) | Much larger: injection stalls; HyperOS may be throttling Clu |
| max pointers | 2 (stick + button A) | 1: new fingers can't join a continuing gesture on this ROM |
| Real touches that forced a re-press | ≥ 1 after step 3 | 0: the outside-touch watch doesn't fire; tell us |
| Seen by Android as accessibility tool | true | false: the installed build is wrong |
| Clu source / measured Hz | GAME_ROTATION_VECTOR, about 100 Hz (start a session first) | Lower: the sensor hub batches or throttles |

Then try a real game: Setup › **Start**, hold still about 2 s, open the game, tap **Layout** on
the Clu panel, drag the stick and buttons onto the game's controls, **Save**, and play.

## 5. If Clu stops working

These read-only `adb` commands tell apart the failure modes reported on HyperOS 3 / Android 16:

```bash
adb shell settings get secure enabled_accessibility_services   # is Clu still enabled?
adb shell dumpsys activity exit-info com.clu.motion | head -40   # why did the process die?
adb logcat -b events | grep -iE "am_kill|force.?stop.*com.clu.motion"
adb shell dumpsys greezer | grep -i clu                         # HyperOS freezer (if present)
```

## Still unknown on this phone (please report)

- Which Xiaomi screens the Battery saver and Autostart buttons actually open on HyperOS 3 global.
  The device report lists it under "Xiaomi screens opened".
- Whether clearing Recents or the Security app's cleanup force-stops unlocked apps on this build.
- Whether HyperOS's freezer pauses a process that holds a bound accessibility service with the
  default battery setting.
- Whether Game Turbo's sidebar or edge gestures react to injected strokes near the screen edge.
- Whether `adb install` builds are also treated as restricted on this ROM.
