# OpenSMS (Android)

Turn an Android phone into a personal SMS gateway. The app opens an outbound secure WebSocket to your OpenSMS backend, receives send requests, sends them through your SIM cards (dual-SIM aware) and reports the result. No tunnelling software and no open inbound ports.

> This repository contains only the Android app and the public protocol description.

## How it works

1. Install the signed APK from GitHub Releases, accept the terms and grant the permissions.
2. Choose your home country and SIM setup (`sim_1`, `sim_2` or both).
3. The app logs in with its device ID and waits until the administrator approves it.
4. Create an API key in the app. Your website or server calls the backend with that key; the backend routes the SMS to this phone.
5. Optionally enable a callback URL to be notified of `sms.sent`, `sms.delivered`, `sms.failed` and `sms.unknown`.

## Good to know

- Messages sent by the app also appear in the phone's normal *Sent* folder. Android does not let a normal app delete them.
- Carrier charges apply. You are responsible for sending only permitted messages.
- Android may ask for confirmation when too many SMS are sent in a short time; the app paces sending to avoid this.
- Keep the **release signing key** safe. A different key or a factory reset changes the device ID, and the phone then needs to be approved again as a new device.
- Backups are disabled on purpose; credentials never leave the phone.

## Configuration

The only build-time configuration is the backend URL (`https://opensms.wansaw.com`, `wss://opensms.wansaw.com/v1/connect`). **No keys or secrets belong in this repository.**

## Status

Planning complete; the Android project is not created yet.

## License

Apache-2.0, see [`LICENSE`](LICENSE).

## Build and release

- Local: `./gradlew :app:assembleDebug` (JDK 17, Android SDK 37). Point a debug build at a local backend with `-PapiBaseUrl=http://10.0.2.2:8787`.
- Release: GitHub → Actions → **Release** → *Run workflow*. It builds signed APKs for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` and a universal APK, and publishes a GitHub Release ("New release on <date>") with the APKs and `SHA256SUMS.txt`.
