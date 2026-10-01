# Rokid Terminator HUD

A fan-made sci-fi HUD for **Rokid Glasses**. It tracks a face with the glasses' camera, runs a short scan sequence, and draws a translucent reticle over the view. It is a playful visual demo, not a person-identification or safety tool.

**[Download the v0.8 APK](https://github.com/oldmelnick-afk/rokid-terminator-hud/releases/download/v0.8/rocket-terminator-hud-v0.8.apk)** · [Source ZIP](https://github.com/oldmelnick-afk/rokid-terminator-hud/releases/download/v0.8/rokid-terminator-hud-source-v0.8.zip) · [All releases](https://github.com/oldmelnick-afk/rokid-terminator-hud/releases)

![Green HUD concept visualization over a generated person on a Moscow street](screenshot-moscow-concept-v0.8.png)

*Concept visualization: the person and street scene were generated. The green HUD layout is based on a screen capture from the actual glasses. The dossiers shown are fictional.*

[View a green color preview without a face](screenshot-glasses-green-preview.png). The [unaltered screen capture from the glasses](screenshot-glasses-no-face.png) is from v0.7; it appears white in the digital capture. Version 0.8 draws the HUD in phosphor green, but has not yet been recaptured on the glasses.

## What it does

- Searches for a face, scans for about four seconds, shows `SCAN COMPLETE`, then switches to `PRIMARY TARGET` and the film-style `JOHN CONNOR` label after about five seconds.
- Follows the face with a translucent reticle aimed toward the head. The reticle shrinks when the target is locked and disappears when the face is lost.
- Draws the whole HUD in phosphor green (`#60FF91`) on black, including the compass, telemetry and reticle.
- Shows an approximate distance based on camera geometry. This is an uncalibrated estimate. Height, weight, address, birth date, sex, race and job are **fictional game data**, drawn from 16 distinct profile cards. A new capture draws another card; the app does not recognize a returning person.
- Gives a playful `AGGRESSION` score based on the face detector's smile probability. A locally detected raised middle finger briefly sets the score to 100%. It does not measure anyone's actual mood or intent.
- Refreshes the simulated `CPU / PROC / BUS / ADD` rows: every five seconds the block clears and the lines return from top to bottom. Numbers continue changing while visible.
- Rotates an eight-direction radar display from the glasses' rotation sensor. On the tested unit there is no magnetic compass, so its azimuth is **relative to the starting orientation**, not true north. The Moscow latitude and longitude are animated display values, not GPS.
- Processes camera frames locally with Android Camera2, ML Kit Face Detection and MediaPipe Hand Landmarker. No account or cloud connection is required; the app requests only camera permission.

## Install on Rokid Glasses

This APK is for the glasses, **not the companion iPhone app**. It was built and launched on `RG-glasses` running Android 12 with a 480×640 display. Other Rokid models are untested.

1. Download [rocket-terminator-hud-v0.8.apk](https://github.com/oldmelnick-afk/rokid-terminator-hud/releases/download/v0.8/rocket-terminator-hud-v0.8.apk) from the release assets.
2. Enable developer access / USB debugging on the glasses and connect them to a computer with Android Platform Tools (`adb`).
3. Install with `adb install -r rocket-terminator-hud-v0.8.apk`.
4. Open **Terminator HUD** from the glasses' Android app launcher and grant camera access.

Tap once to restart the scan. Double-tap within 400 ms to return to the launcher. The built-in Hi Rokid assistant has not been confirmed to launch third-party APKs by voice on this firmware.

**SHA-256:** `65AB51D989AEFCE290804E02565FF888FF8BF5374783D5283C0D2C083D4EDF90`

## Build from source

Download and unpack the [v0.8 source ZIP](https://github.com/oldmelnick-afk/rokid-terminator-hud/releases/download/v0.8/rokid-terminator-hud-source-v0.8.zip). Install an Android SDK and a JDK supported by the Gradle wrapper. Open the project in Android Studio, or run `./gradlew :app:assembleDebug` (`gradlew.bat :app:assembleDebug` on Windows). The result is `app/build/outputs/apk/debug/app-debug.apk`. `build-preview.ps1` is a Windows helper; pass `-Offline` only after dependencies are cached.

The package ID is `com.rocketglasses.terminatorpreview`. The included `hand_landmarker.task` is used for local hand tracking.

## Scope and limitations

Version 0.7 was installed and launched on the glasses with an active camera and rotation sensor. Version 0.8 compiles successfully but the glasses are disconnected, so its green output still needs an on-device check. An earlier version tracked a face shown in a photograph. Reticle alignment, behavior with multiple people, and the gesture response still need hands-on testing. Camera and eye viewpoints differ. This project is an unofficial fan-made demo and is not affiliated with Rokid or the Terminator film rights holders.
