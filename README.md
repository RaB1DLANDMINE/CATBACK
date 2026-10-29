<div align="center">

# 🐱 CATBACK

### An Android Auto replacement that was 100% necessary.

**Android Auto for petalOS — no Google apps, no Play Services, not even Android Auto installed.**

![Platform](https://img.shields.io/badge/platform-petalOS-ff6f61)
![Google Apps](https://img.shields.io/badge/Google%20apps%20required-0-success)
![Necessity](https://img.shields.io/badge/necessity-100%25-blueviolet)
![License](https://img.shields.io/badge/license-GPLv3-blue)

</div>

---

## What is this?

CATBACK lets your petalOS phone talk to any Android Auto head unit in your car — wired or wireless — without a single Google app on the device.

No Google Play Services. No Play Store. No Android Auto app installed. Just CATBACK.

Your car thinks it's talking to Android Auto. Your phone knows better.

## Why?

Because petalOS doesn't ship with Google Mobile Services, and Android Auto refuses to run without them. That leaves you with a perfectly good car head unit and a perfectly good phone that won't speak to each other.

So, naturally, we rewrote the phone side.

Was it necessary? **100%.**

## How it works

Android Auto head units only accept connections from a phone that can complete the protocol's authentication handshake. That handshake needs credentials that live inside Google's Android Auto app.

CATBACK doesn't need the app *installed* — it only needs the APK file:

1. **You supply an Android Auto APK.** Download it yourself and point CATBACK at it.
2. **CATBACK extracts the credentials** it needs for the handshake from the APK. The APK is never installed or run.
3. **CATBACK speaks the Android Auto protocol** to your head unit directly, handling projection, input, audio and everything else itself.

> **Note:** CATBACK does not bundle or redistribute any Google files. You provide your own APK.


## Features

- ✅ Zero Google apps or services required
- ✅ Works on stock petalOS
- ✅ Wired Android Auto over USB
- ✅ Wireless Android Auto *(if your head unit supports it)*
- ✅ Media, calls and navigation projection
- ✅ Touchscreen and rotary-knob input
- ✅ Steering-wheel controls
- 🚧 _Add / remove features to match your current build_

## Requirements

- A phone running **petalOS** (version 1.0.1 or later) (You CAN use CATBACK on other ROMs but various features will be limited due to system restrictions.)
- An **Android Auto APK** file (not installed — just the file)
- A car or aftermarket head unit with **Android Auto** support
- A decent USB cable (seriously, it matters)

## Installation

1. Grab the latest CATBACK release from the [Releases](../../releases) page. (Stock petalOS includes CATBACK but may need updating.)
2. Install it on your petalOS device.
3. Open CATBACK and select your Android Auto APK when prompted.
4. Wait for CATBACK to extract what it needs.
5. Plug into your car. Or be lazy and use bluetooth. 

### Building from source

```bash
git clone https://github.com/<your-username>/catback.git
cd catback
./gradlew assembleRelease
```

## FAQ

**Do I need Google Play Services?**
No.

**Do I need the Android Auto app installed?**
No. Just the APK file sitting on your phone.

**Do I need *any* Google apps?**
No. That's the whole point.

**Will this work in my car?**
If your car supports Android Auto, probably. Open an issue with your make, model and head unit if it doesn't.

**Why is it called CATBACK?**
_Add your explanation here._

## Contributing

PRs and issues are welcome. If you're reporting a bug, please include:

- petalOS version
- Phone model
- Car / head unit make and model
- Android Auto APK version you supplied
- Logs (Settings → Export logs)

## Disclaimer

CATBACK is an independent project and is not affiliated with, endorsed by, or sponsored by Google or Huawei. Android Auto is a trademark of Google LLC. Users are responsible for obtaining the Android Auto APK themselves and for complying with the laws and terms that apply to them.

## License

_TBD — add your license here._

---

<div align="center">
Made with questionable necessity. 🐱
</div>
