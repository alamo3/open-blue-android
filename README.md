# Open Blue

A small, personal, **unofficial** Android client for Hyundai **Bluelink Canada** (mybluelink.ca), written in Kotlin + Jetpack Compose.

It exists because the official app hides heated/ventilated seat controls for some cars: it requires per-seat feature flags (`RshvDriverSeat` etc.) that Hyundai Canada's servers don't send for every vehicle, even though the API and the website support remote seat control.

## Features

- **Home:** a 3D model of the car, sitting on the ground, whose doors, frunk and tailgate open to match the real car. While climate runs, warm or cool air streams into the intake and through the cabin (and up the windshield when defrosting); while plugged in, a charging cable is in the port with current running down it. Drag to look around, pinch to zoom, double-tap to reset; the view swings round to whatever just changed; battery and range, charging time, and one-tap controls for lock/unlock, climate, camera and waking the car. Pull down to refresh.
- **Climate:** temperature, the car's real cabin seen from above (roof cut away) where you tap each seat to cycle heat/cool levels and watch it warm up or breathe cool air, front defrost, heated wheel and rear defrost, run time, start/stop. Matches what your car supports (combined or split heating, heat-only rear seats).
- **Presets:** your presets from mybluelink.ca plus presets saved on the phone; start in one tap or load into the controls.
- **Charging:** open or close the charge port, set AC and DC charge limits, and turn battery preconditioning on or off before DC fast charging.
- **Public charging:** Hyundai's in-app charging: your plan and cards, chargers nearby on a map with live availability and prices, starting and stopping a paid charge, charging history with receipts, and trip planning with charging stops (handed to Google Maps).
- **Horn and lights:** flash the hazards, with or without the horn, to find the car.
- **Surroundings:** asks the car for surround-view photos and shows the top, front, rear, left and right views. Every set is kept on the phone (Bluelink only keeps the latest), browsable in a history strip, and can be saved to the gallery (Pictures/Open Blue) whole or one view at a time.
- **3D surroundings:** turns a set of surround-view photos into a 3D scene you can orbit, with your car in the middle. "Painted": every pixel within about 11 m put back where its depth (estimated on the phone) says it was, on a floor and in front of a 360° backdrop of the far surroundings whose unseen parts (mostly sky) are painted in by an inpainting model. "Simple": walls traced from the depth and grey stand-in cars where it spotted vehicles. Uses three on-device models downloaded on first use (Depth Anything V2 Small, Apache-2.0; YOLO11n, AGPL-3.0; LaMa from OpenCV's model zoo, Apache-2.0) and a camera calibration solved from the car's own top view (`tools/svm3d`).
- **Vehicle:** locks, doors, windows, trunk/frunk, charge limits, 12V battery, tires, odometer.
- **Engine sound:** a virtual engine (inline-4 turbo, inline-5, V6 or V8, all synthesized, no recordings) that follows the car: the phone's motion sensors and GPS drive a 6-speed virtual gearbox with shifts, kickdowns, pops on lift-off and a rev limiter. Revs fall back to idle when you're not accelerating (or hold in gear, if you prefer). Plays through the phone, or the car's speakers via Android Auto; runs as a foreground service so it keeps going with the phone locked. A delay meter times the speakers with the microphone and a look-ahead setting predicts that far ahead to make up for it.
- **Engine sound:** a synthesized sound that follows the car, played through the phone or the car's speakers via Android Auto. Three sounds made for an EV (Starship, a sci-fi drone; Turbine, a jet-style spool; Electric+, the motor's own whine exaggerated) whose pitch follows speed like the real direct-drive motor, plus four classic engines with optional virtual gearshifts. The phone's motion sensors and GPS drive it; it gets louder and brighter the harder you accelerate and settles back when you're not. Runs as a foreground service so it keeps going with the phone locked under Android Auto. It can start and stop by itself when your car's Bluetooth connects and disconnects, and it remembers how the phone sits in its mount between drives. A delay meter times the speakers with the microphone, and a look-ahead setting predicts that far ahead to make up for Android Auto's delay.
- **Notifications:** commands keep waiting for the car after you leave or close the app, and tell you how they went. Optional background checks (every 15, 30 or 60 minutes, reading what Bluelink already has so the car isn't woken) alert you when charging finishes or stops early, the car is left unlocked or open for 10 minutes, the 12V battery runs low, or the tire pressure warning comes on. Each alert can be switched off.
- **Security:** optional fingerprint/screen-lock confirmation before unlocking.
- Handles Bluelink's one-time email/SMS device verification, and re-logs in automatically when the token expires or your phone changes networks.

Not possible from a third-party app: **Digital Key pairing**. The official app passes a pairing password to Samsung's/Google's car-key service, which only accepts the carmaker's signed app. Pairing is a one-time step; afterwards the key lives in your wallet app.

## Screenshots

CI renders every screen with made-up demo data (light and dark) using Paparazzi and pushes them to the [`screenshots`](../../tree/screenshots) branch.

## Install

Every push to `main` builds an APK and publishes it to the [**latest** release](../../releases/tag/latest). Open that page on your phone, download `OpenBlue.apk`, and allow installs from your browser when Android asks.

### Stable signing (so updates install over the old version)

Without signing secrets, CI signs with a throwaway debug key and you'd have to uninstall before each update. To fix that once:

```sh
keytool -genkeypair -keystore openblue.jks -storetype PKCS12 -alias openblue \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Open Blue"
base64 -w0 openblue.jks   # macOS: base64 -i openblue.jks
```

Add two repository secrets (Settings → Secrets and variables → Actions):

- `OPENBLUE_KEYSTORE_B64` – the base64 output
- `OPENBLUE_KEYSTORE_PASSWORD` – the password you chose

## Privacy

Your email, password and Bluelink PIN are stored only on the phone, in EncryptedSharedPreferences (key in the Android Keystore), and are sent only to `mybluelink.ca`. App backup is disabled. There is no analytics or other network traffic.

## How it talks to Bluelink

Endpoints, headers and payloads mirror the official Android app (v2.0.47), cross-checked against the open-source [hyundai_kia_connect_api](https://github.com/Hyundai-Kia-Connect/hyundai_kia_connect_api). Key calls (`https://mybluelink.ca/tods/api/`):

| Purpose | Endpoint |
| --- | --- |
| Login / device verification | `v2/login`, `mfa/selverifmeth`, `mfa/sendotp`, `mfa/validateotp`, `mfa/genmfatkn` |
| Vehicles, features | `vhcllst`, `sltvhcl` |
| Status (cached / live) | `lstvhclsts`, `rltmvhclsts` |
| PIN → `pAuth` | `vrfypin` |
| Climate start / stop | `evc/rfon` (`hvacInfo` or `remoteControl` with `seatHeaterVentCMD`), `evc/rfoff` |
| Lock / unlock | `drlck`, `drulck` |
| Command result | `rmtsts` (with `transactionId`) |
| Server presets | `gtfvsttng` |
| Surround view | `rfc/fndmcrsvm` (request), `rfc/lastmcrsvm` (base64 JPEG strip + `imageSize`) |

Seat codes: `0` off, `6/7/8` heat low/medium/high, `3/4/5` cool low/medium/high.

## Credits

- 3D car: based on ["2024 Hyundai Ioniq 5 N"](https://skfb.ly/ptJQN) by [Ddiaz Design](https://sketchfab.com/ddiaz-design), licensed under [CC BY-NC 4.0](http://creativecommons.org/licenses/by-nc/4.0/). Modified: doors, frunk and tailgate separated into hinged parts, repainted Atlas White with neutral trim, interior/wheels/frunk simplified for phones, ground shadow and charging cable added (`tools/model`). Non-commercial use only.
- Studio lighting environment: original, generated by `tools/env/make_studio_hdr.py`.
- Typeface: [Barlow](https://github.com/jpt/barlow), SIL Open Font License 1.1 (`licenses/Barlow-OFL.txt`).

## Disclaimer

Not affiliated with or endorsed by Hyundai. This uses an undocumented API that can change at any time. Use at your own risk, and avoid hammering "wake car", since each live refresh wakes the car's modem and draws on the 12V battery.
