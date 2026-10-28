# Open Blue

A small, personal, **unofficial** Android client for Hyundai **Bluelink Canada** (mybluelink.ca), written in Kotlin + Jetpack Compose.

It exists because the official app hides heated/ventilated seat controls for some EVs (it requires the vehicle to be flagged `Gen3` *and* to pass a server-side seat feature check), even though the API and the website support them.

## Features

- **Status:** battery %, range, charging/plug state, 12V battery, locks, doors/trunk/frunk, climate and seat states. Cached refresh, or "wake car" for live data.
- **Climate:** temperature, front defrost, heated steering wheel / rear window / mirrors, run time, and **heated/ventilated seats for all four seats**. Start and stop.
- **Presets:** your existing presets from mybluelink.ca (start them as-is, or load them into the editor), plus presets saved on the phone.
- **Lock / unlock.**
- Handles Bluelink's one-time email/SMS device verification, and re-logs in automatically when the token expires or your phone changes networks.

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

Seat codes: `0` off, `6/7/8` heat low/medium/high, `3/4/5` cool low/medium/high.

## Disclaimer

Not affiliated with or endorsed by Hyundai. This uses an undocumented API that can change at any time. Use at your own risk, and avoid hammering "wake car", since each live refresh wakes the car's modem and draws on the 12V battery.
