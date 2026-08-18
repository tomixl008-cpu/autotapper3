# Auto Text Tapper

A user-controlled Android **Accessibility Service assistant**, written in Kotlin
with traditional XML Views. When you press **Start**, the app waits 5 seconds,
then repeatedly scans the visible screen for the exact accessible texts
**"Like video"**, **"Skip"** and **"Loading"** and performs a fixed, documented
tap/swipe sequence.

> ### Authorized-use notice
> This app is intended only for apps, screens and accounts that **you** own or
> are **explicitly authorized to automate**. It contains no screenshot capture,
> OCR, root access, overlays, hidden background behaviour, CAPTCHA handling,
> ad-click automation, financial/login/password/OTP automation, protected-screen
> bypasses or anti-detection methods. The Automation only runs after you press
> **Start**, and **Stop** cancels everything immediately.
>
> The Accessibility permission must be **enabled manually by the device owner**
> in Android Settings → Accessibility. The app cannot enable it by itself.

---

## 1. How the automation behaves

Everything below runs inside a finite state machine in
[`app/src/main/java/com/example/autotexttapper/TextAutomationAccessibilityService.kt`](app/src/main/java/com/example/autotexttapper/TextAutomationAccessibilityService.kt).

### Overall flow

```
Start
  └─> wait exactly 5 seconds
        └─> MAIN SCAN (repeats forever until Stop)
              "Like video" always has priority over "Skip"
```

### Main scan rule — "Like video" is always the priority

On every scan (triggered by window changes, with a 1-second fallback):

1. Search visible accessible text **and** content descriptions for an exact,
   case-insensitive, whitespace-trimmed match of **"Like video"**.
2. If found → click it immediately and enter the **Like route**.
3. Only when "Like video" is **absent**, search for **"Skip"**.
4. If "Skip" is found → click it and enter the **Skip route**.
5. If neither is visible → the screen is **not touched**; wait 1 second and scan
   again.

**Critical priority rule:** if "Like video" and "Skip" are visible together,
only "Like video" is ever clicked. "Skip" is never clicked while "Like video"
is visible.

### Like route

```
click "Like video"
  └─> wait 5 seconds
        └─> double-tap the exact centre of the display
              (two 60 ms taps at the same coordinate, 150 ms gap)
              └─> swipe-back #1: left→right, 8% → 82% of width, at 50% height, 300 ms
                    └─> wait 500 ms
                          └─> swipe-back #2 (same gesture)
                                └─> Loading wait route
                                      └─> back to MAIN SCAN
```

Gestures are chained through `GestureResultCallback`: each step starts only
after the previous gesture **completed**. If a gesture is cancelled, the route
is abandoned and the app returns safely to the main scan after 1 second —
it never continues blindly.

### Loading wait route (after the second swipe-back)

1. Look for the exact text **"Loading"** — checked every 1 second and on every
   window/content-change event.
2. If "Loading" appears → status becomes *"Loading detected. Waiting for it to
   finish."* and the app waits while it stays visible.
3. Once it disappears → wait an extra **500 ms** (UI may still be updating),
   then return to the main scan.
4. If "Loading" never appears → a **30-second timeout** (measured with
   `SystemClock.elapsedRealtime()`) returns the app safely to the main scan.
   It never waits forever.

### Skip route

```
click "Skip"
  └─> wait exactly 4 seconds
        └─> back to MAIN SCAN (which checks "Like video" before "Skip" again)
```

No double-tap, no swipes and no Loading wait happen on the Skip route.

### Stop rule

Pressing **Stop** cancels every pending callback, delay, scan, retry, tap and
swipe continuation, sets the state to `IDLE` and shows *"Stopped"*. Nothing
happens until you press Start again.

---

## 2. Building the APK

### Local build

Requirements: **JDK 17** and Android SDK (platform 35) — or just open the
project in Android Studio.

```bash
./gradlew assembleDebug --no-daemon
```

The installable APK is written to:

```
app/build/outputs/apk/debug/app-debug.apk
```

### Build on GitHub (no local Android SDK needed)

The repository includes a ready-made GitHub Actions workflow
[`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml):

1. Push this code to the **`main`** branch of your GitHub repository
   (the workflow also runs on every later push to `main`, or manually via
   **Run workflow**).
2. Open the repository **Actions** tab on GitHub.
3. Open the **Build Android APK** workflow.
4. Wait for the green success check mark.
5. Open the completed workflow run.
6. Scroll to **Artifacts** and download **`AutoTextTapper-debug-APK`**.
7. Extract the ZIP — inside is `app-debug.apk`. Transfer it to your phone and
   install it (allow "install from unknown sources" when Android asks).

Artifacts are kept for 30 days.

---

## 3. Phone setup

1. Install `app-debug.apk` on your phone.
2. Open **Auto Text Tapper**.
3. Tap **Open Accessibility Settings**.
4. Find **Auto Text Tapper** in the accessibility service list and enable it
   (confirm Android's permission dialog).
5. Return to the app — the status shows *"Service enabled and ready"*.
6. Switch to the app/screen you want to automate, or press **Start
   (5-second delay)** and switch during the 5-second countdown.
7. Press **Stop** at any time to cancel everything instantly.

---

## 4. Changing texts, delays and gesture coordinates

All user-configurable values are named constants in the `companion object` at
the bottom of `TextAutomationAccessibilityService.kt`:

```kotlin
// Target texts (exact match, case-insensitive, trimmed)
const val LIKE_VIDEO_TEXT = "Like video"
const val SKIP_TEXT = "Skip"
const val LOADING_TEXT = "Loading"

// Delays / intervals
const val INITIAL_DELAY_MS = 5000L
const val WAIT_AFTER_LIKE_MS = 5000L
const val WAIT_BETWEEN_SWIPES_MS = 500L
const val WAIT_AFTER_SKIP_MS = 4000L
const val MAIN_SCAN_INTERVAL_MS = 1000L
const val LOADING_SETTLE_DELAY_MS = 500L
const val LOADING_TIMEOUT_MS = 30000L

// Gesture geometry
const val TAP_DURATION_MS = 60L
const val DOUBLE_TAP_GAP_MS = 150L
const val SWIPE_START_X_RATIO = 0.08f   // swipe-back starts at 8% of screen width
const val SWIPE_END_X_RATIO = 0.82f     // ...and ends at 82% of screen width
const val SWIPE_Y_RATIO = 0.50f         // at 50% of screen height
const val SWIPE_DURATION_MS = 300L
```

Edit the values, rebuild, reinstall. Ratios are fractions of the current
screen size, so `0.82f` means 82 percent of the width.

---

## 5. Limitations

- Only **accessible** UI text and content descriptions can be detected.
  Text painted inside videos, images, canvases, WebViews without accessibility
  support, protected/secure windows or custom views that do not expose
  accessibility info may be invisible to the app.
- Matching is **exact** (case-insensitive, trimmed). An app showing
  "Like  video" with irregular characters, a different language, or text baked
  into an image will not match.
- The device owner must **manually enable** the Accessibility permission; the
  app cannot turn it on itself.
- A swipe/tap sequence that is cancelled by the system (e.g. by an overlay or
  a display change) safely aborts back to the main scan instead of continuing.
- Debug builds are signed with the standard Android debug key; Android may warn
  about "unknown developers" during install — that is expected.

---

## 6. Project structure

```
.
├── .github/workflows/build-apk.yml   # GitHub Actions: builds the APK on push to main
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/autotexttapper/
│       │   ├── MainActivity.kt                      # UI: 3 buttons + live status
│       │   ├── StatusHolder.kt                      # SharedPreferences status bus
│       │   └── TextAutomationAccessibilityService.kt# FSM: scan / click / gestures
│       └── res/
│           ├── layout/activity_main.xml
│           ├── xml/accessibility_service_config.xml
│           └── values/strings.xml, themes.xml, colors.xml
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradlew / gradlew.bat / gradle/wrapper/          # Gradle 8.11.1 wrapper
```

Tech: Kotlin 2.0.21 · Android Gradle Plugin 8.9.0 · Gradle 8.11.1 ·
minSdk 26 · compileSdk/targetSdk 35 · Java 17 · AndroidX AppCompat + Material.
