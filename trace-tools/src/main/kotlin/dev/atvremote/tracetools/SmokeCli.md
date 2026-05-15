# SmokeCli — live-device runbook (Steps 3–4, DEFERRED)

> **Status: DEFERRED — no physical Apple TV is available in this environment.**
>
> Steps 3–4 below are the **authoritative end-to-end validation** of the entire
> protocol stack (Tasks 8–16).  They must be run by the project owner on a
> machine that shares a LAN with a real Apple TV running tvOS 18.  Steps 1–2
> (the CLI code itself) and the device-free `CredentialStore` unit test are
> complete and committed.

---

## Prerequisites

- JDK 17+, Gradle 8.10 (already in the repo).
- The build machine and the Apple TV must be on the **same LAN/Wi-Fi** so that
  mDNS multicast (port 5353) and the Companion TCP port (default 49152) are
  reachable.
- The Apple TV must not be sleeping (`Settings → General → Sleep After → Never`
  during the test run).

---

## Step 3 — Scan & pair

### 3a. Scan (verify discovery works)

```bash
./gradlew :trace-tools:run --args "scan"
```

**Expected output (example):**

```
Scanning for Apple TV devices (8s)…
<ATV_ID>	Living Room	192.168.1.42:49152	AppleTV14,1	true
```

Where `<ATV_ID>` is the Companion device identifier printed by pyatv / shown in
the Apple TV's network settings.  Export it for the next commands:

```bash
export ATV_ID="<paste the id from the scan line>"
```

### 3b. Pair

```bash
./gradlew :trace-tools:run --args "pair $ATV_ID"
```

1. The Apple TV will display a 4-digit PIN on screen.
2. The CLI prints `Enter PIN shown on TV:` — type the PIN and press Enter.

**Expected output:**

```
Scanning for Apple TV devices (8s)…
Enter PIN shown on TV: 1234
Paired: <ATV_ID>
Credentials saved to: /Users/<you>/.atvremote/credentials
```

The credentials file `~/.atvremote/credentials` now contains one line:

```
<ATV_ID>=<base64-encoded HapCredentials>
```

---

## Step 4 — Send Menu button

```bash
./gradlew :trace-tools:run --args "menu $ATV_ID"
```

**Expected output:**

```
Scanning for Apple TV devices (8s)…
OK
```

**Expected Apple TV behaviour:** the Apple TV UI visibly responds to the Menu
button (e.g. dismisses a modal, navigates back, or activates the home screen),
confirming that the full connect → pair-verify → session-handshake → HID-command
pipeline works end-to-end against a real tvOS 18 device.

---

## Why this is the authoritative E2E gate

This CLI exercise exercises **every layer** of the protocol stack implemented in
Tasks 8–16:

| Layer                  | Task | What is exercised                                  |
|------------------------|------|----------------------------------------------------|
| mDNS discovery         | 15   | `JmdnsDiscovery` finds the Apple TV                |
| TCP / frame codec      | 7–8  | `CompanionConnection` frames are sent/received     |
| Pair-setup M1–M6       | 11   | SRP-6a + Ed25519 + ChaCha20-Poly1305               |
| Pair-verify M1–M3      | 12   | X25519 ECDH + Ed25519 + ChaCha20-Poly1305          |
| Session handshake      | 13   | `_systemInfo` / `_systemAction` exchange           |
| HID button command     | 14   | `E_OPACK` Menu down/up frames                      |
| PairingHandle wiring   | 16   | `PairingState` flow + `submitPin`                  |
| CredentialStore        | 17   | Round-trip serialize/deserialize `HapCredentials`  |

The synthetic golden-trace fixtures (Tasks 10–12) validate protocol-logic
self-consistency with deterministic byte sequences. Only this CLI run against
real tvOS 18 hardware can confirm that the on-the-wire byte sequences are
accepted by a genuine Apple TV.

---

## Replacing synthetic fixtures with real captures

After a successful Step 4, follow `CaptureGuide.md` (in the same directory) to:

1. Re-run the `atvremote --debug` commands from `CaptureGuide.md` section 3–4
   to capture `pair.log` and `menu.log`.
2. Extract OPACK hex payloads and produce the three real-device JSON fixtures
   (see `CaptureGuide.md` section 5).
3. Overwrite `protocol/src/test/resources/goldentrace/{pair-setup,pair-verify,hid-menu}.json`.
4. Flip `"mode": "synthetic"` → `"mode": "realDevice"` in each file.
5. Run `./gradlew :protocol:test` and update the `GoldenTraceLoaderTest`
   `assertEquals("synthetic", ...)` assertion to accept `"realDevice"`.
6. Confirm `./gradlew test` is green end-to-end.
