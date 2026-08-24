# KMapper — Keyboard/Mouse → Touch mapper (root, Android)

A starter Android Studio project that lets you play touch-only games with a
keyboard/mouse on a **rooted** device, by reading raw input events from
`/dev/input` and injecting synthetic touches into the touchscreen device node.

This is a real, working architecture (the same one apps like Panda Mouse Pro
use) — but you should treat this as a **solid foundation you tune on your
specific device**, not a drop-in finished product. Raw `/dev/input` layouts
vary by kernel/OEM, and you will very likely need to adjust a few things
after your first build. See "Known rough edges" below.

## How it works

1. **Root shell** (`RootShell.kt`) keeps one persistent `su` process open so
   commands execute with near-zero latency (spawning `su -c` per key press is
   too slow for real-time input).
2. **Device scan** (`InputDeviceScanner.kt`) runs `getevent -i` and parses out:
   - the touchscreen node (has `ABS_MT_POSITION_X/Y`) and its raw coordinate range
   - your external keyboard/mouse nodes (have `KEY`/`REL` capabilities, no `ABS_MT`)
   - if auto-detection picks the wrong thing (or nothing), the **Fix Devices**
     screen lets you manually tick the correct device(s) by name; that choice
     is saved and overrides the heuristic from then on.
3. **Capture** (`KeyCaptureService.kt`) streams `getevent -l <nodes>` in a
   foreground service, parsing `EV_KEY` (key up/down) and `EV_REL` (mouse
   dx/dy) lines as they happen — this bypasses Android's normal focus-based
   input routing entirely, so it works even while the game app has focus.
4. **Injection** (`TouchInjector.kt`) writes raw `sendevent` commands to the
   touchscreen node using Multitouch Protocol B, on a spare high-numbered
   slot (20+) so it never collides with your real fingers if you also touch
   the screen.
5. **Floating live editor** (`OverlayEditorService.kt`) is a small draggable
   "KM" bubble that sits on top of whatever game is running. Tap it to open
   a mini panel: add a key mapping (press the physical key you want, then
   drag the new dot to where it should tap on screen), set the mouse-look
   center, jump to device troubleshooting, or stop. Existing zone dots are
   draggable and tappable (tap to edit/remove) live, over the game.
6. Mouse movement is turned into a virtual "aim finger" that drags within a
   clamped radius around the center point you set, then recenters shortly
   after you stop moving the mouse.

## Setup — new flow

1. Open in Android Studio (Giraffe+), let Gradle sync.
2. `minSdk 26`. Build & install on your rooted device.
3. Launch KMapper, tap **Add Game** → pick the game from your installed
   apps list (this is a real app picker now, not typing a name).
4. Select it in the list, grant the overlay ("display over other apps")
   permission when prompted, then tap **Start**.
   - This launches the actual game.
   - Accept the root (`su`) prompt.
   - A small floating **"KM"** bubble appears on top of the game.
5. Tap the KM bubble → **+ Add key mapping** → press the physical key you
   want to bind → choose Tap/Hold/Toggle → a new dot appears in the middle
   of the screen → **drag it** to wherever it should tap in-game. Repeat for
   every key. Use **Set mouse-look center** the same way for aim.
6. Everything is live: press the key on your keyboard right now and you
   should see the touch land in the game immediately, so you can tune
   positions in real time instead of guessing blind.
7. Tap the KM bubble → **Stop mapping** (or use Stop from the KMapper app)
   when done — this releases any held touches cleanly.

## Troubleshooting "no external keyboard/mouse detected"

This means the auto-detection heuristic didn't recognize your device from
its `getevent -i` capabilities — common on some OEM kernels with unusual
formatting. Fix it directly instead of guessing:

1. From the KMapper app (or the KM bubble → "Fix device detection"), open
   the **Fix Devices** screen.
2. It lists every `/dev/input` node it can see, with its name and
   capabilities (`KEY`, `REL`, `ABS_MT`), plus the raw `getevent -i` text at
   the bottom for reference.
3. Tick the checkbox next to your actual keyboard and/or mouse (their name
   usually gives it away, e.g. contains "keyboard", "mouse", a vendor name,
   or "HID").
4. Tap **Save selection** — this is stored and used instead of the
   heuristic on every future scan, for every profile.
5. If the list is completely empty, root's `getevent` access itself is
   being blocked on your ROM (SELinux/Magisk policy) — that needs a ROM/root
   management fix, not an app fix; test with `adb shell su -c getevent -i`
   directly to confirm.



## Known rough edges (read before you assume something is "broken")

- **Device node paths are unstable across reboots.** The scanner re-resolves
  nodes by capability + name every time the service starts, so this should
  self-heal, but if detection ever picks the wrong device, add a name filter
  in `InputDeviceScanner.findExternalKeyboardsAndMice()` for your specific
  keyboard/mouse (check `getevent -i` output over `adb shell` to find the
  exact reported name).
- **Raw touchscreen coordinate ranges vary by panel.** `TouchInjector` reads
  `min`/`max` from `getevent -i` automatically, but a few OEM panels report
  ranges that don't linearly match screen pixels at the very edges — if taps
  land slightly off near screen corners, tweak `toRawX`/`toRawY`.
- **SELinux / Magisk denials.** Some ROMs restrict `sendevent`/`getevent`
  even under root. If nothing happens, run `getevent -i` and
  `sendevent /dev/input/eventX 1 1 1` manually over `adb shell su -c` first
  to confirm raw injection is even permitted on your ROM before debugging
  the app.
- **Bluetooth keyboards/mice**: these usually surface as normal
  `/dev/input` nodes just like wired ones, so they should Just Work — but a
  few Android BT HID stacks route input through a virtual "uinput" bridge
  device with a generic name; if scanning doesn't find it, check
  `getevent -i` while the BT device is connected and pressing keys.
- **This is single-profile, single-session.** No per-app auto-switching,
  no cloud sync, no GUI polish — intentionally, so the core input pipeline
  stays easy to read and modify.
- **Not tested on a physical device by me** — I don't have an Android
  device/emulator in this environment. Treat first boot as a debugging
  session, not a guaranteed-working install.

## Getting an APK without installing anything locally (GitHub Actions)

This project includes `.github/workflows/build.yml`, which compiles a debug
APK on GitHub's free cloud runners — no Android Studio, SDK, or Gradle
install needed on your own machine.

1. Create a new **empty** repo on github.com (don't add a README/gitignore
   through GitHub's UI, keep it empty).
2. Unzip this project locally, then from inside the `KMapper` folder:
   ```
   git init
   git add .
   git commit -m "initial commit"
   git branch -M main
   git remote add origin https://github.com/<your-username>/<your-repo>.git
   git push -u origin main
   ```
3. On GitHub, open your repo → **Actions** tab. The `Build APK` workflow
   runs automatically on push (or trigger it manually via "Run workflow").
4. Once it finishes (green check, a few minutes), click into the run →
   scroll to **Artifacts** → download `KMapper-debug-apk` (a zip containing
   `app-debug.apk`).
5. Transfer that APK to your rooted device (email it to yourself, Google
   Drive, `adb push`, whatever's easiest) and install it — you'll need to
   allow "install from unknown sources" for whatever app you use to open it,
   since it's not from the Play Store.
6. Debug builds are self-signed automatically by the Android Gradle Plugin,
   so no signing setup is required for this to install and run.

No local Android SDK, Gradle wrapper, or emulator required — GitHub's
runners have all of that pre-provisioned.



- Add per-app auto-launch: watch `dumpsys window` for the foreground
  package and auto-start/stop `KeyCaptureService` with the matching profile.
- Add scroll-wheel → weapon-swap mapping (`EV_REL REL_WHEEL`).
- Add a proper draggable overlay editor (edit zones live, over the running
  game) instead of the separate `ZoneEditorActivity` screen — the pieces
  (`OverlayEditorService`, `TouchInjector`) are already there to build on.
