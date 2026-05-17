# Apple TV Remote Control

An Android remote for Apple TV built on the Apple TV Companion protocol. It pairs directly with an Apple TV on the local network and provides a focused remote-control UI without requiring the official Apple TV Remote app.

## Features

- Discover Apple TVs on the local Wi-Fi network with mDNS.
- Pair with the PIN shown on the Apple TV.
- Control focus navigation with deterministic HID direction steps.
- Choose between two remote layouts:
  - Physical remote style
  - iPhone remote style
- Use the large iPhone-style trackpad with realtime direction changes.
- Send Back/Menu, TV/Home, Play/Pause, volume up/down, wake, and sleep.
- Save UI settings, including layout style and trackpad step threshold.
- Use an in-app keyboard surface when the Apple TV exposes a text input session.

## Download

Download the latest APK from GitHub Releases:

[Apple TV Remote Control 1.1](https://github.com/senshinya/apple-tv-remote-control/releases/tag/v1.1)

Direct APK:

[apple-tv-remote-control-1.1.apk](https://github.com/senshinya/apple-tv-remote-control/releases/download/v1.1/apple-tv-remote-control-1.1.apk)

## Requirements

- Android 8.0 or later.
- Apple TV and Android device on the same local network.
- Local network multicast/mDNS must be available for automatic discovery.

## Build

This project uses Gradle with Kotlin, Android, and Jetpack Compose.

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:assembleRelease
```

Run the main verification suite:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :app:testDebugUnitTest :protocol:test :trace-tools:test
```

## Project Layout

- `app/` - Android app and Compose UI.
- `protocol/` - Apple TV Companion protocol implementation.
- `trace-tools/` - command-line tools for discovery, pairing, and protocol smoke tests.
- `docs/` - protocol notes, plans, and development runbooks.

## Notes

The protocol layer was implemented against real Apple TV behavior and cross-checked against `pyatv`, which is the reference implementation used for wire-level compatibility decisions.

This project is not affiliated with or endorsed by Apple.
