# Apple TV Android Remote — Project Status & Resume Notes

> Working doc for resuming across sessions/devices. Last updated: 2026-05-16.

## What this project is

Android remote for Apple TV over the Companion protocol, built in 3 phases
(`docs/superpowers/plans/`):
- **Plan 1 — `:protocol` foundation** (pure Kotlin/JVM lib) + `:trace-tools` CLI
- **Plan 2 — companion command set** (touch/keyboard/apps/power/volume/events) — NOT started
- **Plan 3 — Android app + UI** — NOT started (no Android module exists yet)

Git: branch `main`, pushed to `origin`
(`ssh://git@ssh.git.shinya.click/shinya/apple-tv-controller.git`). Commits go
per-task directly to `main`. Execution uses subagent-driven development
(implementer → spec review → code-quality review → fix/re-review).

## Protocol-debugging rule (READ FIRST)

**Any Companion-protocol issue — framing, ChaCha/nonce, HKDF/keys, OPACK,
pair-setup/verify, session handshake, HID — FIRST compare our implementation
line-by-line against the exact `pyatv` source, before hypothesizing or
fixing.** pyatv (`github.com/postlund/pyatv`, raw: `raw.githubusercontent.com/
postlund/pyatv/master/...`) interoperates with real Apple TVs and is the
authoritative reference this code is ported from. The in-repo synthetic
Task-10 oracle/fixtures are NOT real-device truth — when they disagree with
pyatv/real tvOS, **pyatv wins** (correct our code AND the synthetic fixture).
Every Task-17 bug (discovery, PIN timing, ChaCha nonce, pair-verify M3
wrapper, M4 ordering, OPACK float32, session identity, `_sessionStop`) was
pinpointed only by reading the precise pyatv file — guessing wasted rounds;
the pyatv diff was always decisive. Reuse this for Plan 2 command work.

## Status

### Plan 1 (`:protocol` foundation): COMPLETE & real-device validated
Tasks 1–17 done. Discovery, HAP/SRP pair-setup, pair-verify, encrypted
Companion session, HID command, CLI smoke tool. **64 tests green** (protocol 55
+ trace-tools 9) from clean. Public API in `protocol/src/main/.../Api.kt` is
LOCKED — Plans 2/3 depend on those exact types; do not break them.
`PairSetupGoldenTest` stays **byte-identical** (locked). The pair-verify
synthetic fixture (`pair-verify.json`) WAS corrected vs real-device truth under
Task 17 (see C2 below) — that is exactly what Task 17 is for.

### Real-device validation (Task 17) — ✅ COMPLETE end-to-end
`pair → connect → menu` all confirmed against the real **客厅**
(**AppleTV14,1**, **192.168.7.134:49153**, tvOS ≈715.2). This is the
authoritative end-to-end check of Tasks 6/11/12/14/16.

Env: this machine had **no JDK**; Temurin 17 installed at
`/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home` — prefix
Gradle/CLI with `JAVA_HOME=...temurin-17.jdk/Contents/Home`. Host LAN iface
`en1`=192.168.7.131 (VPN `utun*` present). Build/run:
```
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :trace-tools:installDist
JAVA_HOME=...temurin-17.jdk/Contents/Home ./trace-tools/build/install/trace-tools/bin/trace-tools scan
# pair (interactive PIN on TV) / menu — see below
```
NOTE: mDNS/network via Claude Bash needs the sandbox disabled (real
multicast). Optional override: env `ATVREMOTE_MDNS_ADDR=192.168.7.131`.

**The authoritative protocol reference is pyatv** (`postlund/pyatv`); this
codebase is ported from it. Every Bug C fix below was pinpointed by reading the
exact pyatv source. The synthetic Task-10 oracle/fixtures are NOT real-device
truth — when they disagree with pyatv/real tvOS, pyatv wins.

Bugs fixed (all real-device validated; each has a TDD regression test):
- **A — discovery (`pair`/`menu`)**: used `first { isNotEmpty() }`, returned
  before the Apple TV resolved (local Mac advert wins). → `awaitDevice` waits
  for the target id (`SmokeCli.kt`). [`scan` itself was fixed earlier in
  `6a44334`/`93aeab3`.]
- **B — pair PIN timing**: M1..M6 was deferred into `submitPin()`, so the CLI
  prompted before M1 → TV showed no PIN. → `PairingHandleImpl` sends M1 +
  consumes raw M2 eagerly at construction (defaulted test-scheduler scope);
  `submitPin` joins then runs M3..M6. `PairSetup`/`Srp` untouched → locked
  golden byte-identical.
- **C1 — connection ChaCha nonce**: must be base `Chacha20Cipher`
  `counter.to_bytes(12,"little")` (LE counter in bytes 0..7, no pad). The
  `[4 zero||8-byte LE]` layout is the `Chacha20Cipher8byteNonce` subclass —
  pair-verify PV-Msg only, NOT the connection.
- **C2 — pair-verify M3 wrapper**: M3 must be OPACK `{ _pd }` **only** (NO
  `_auTy`); M1 keeps `{ _pd, _auTy:4 }`. tvOS rejected M3-with-`_auTy`. Oracle
  + `pair-verify.json` + its loader/golden tests corrected to match pyatv.
- **C3 — pair-verify M4 ordering**: must consume the accessory M4 (`PV_Next
  {State:4}`) BEFORE `enableEncryption` (else the plaintext M4 hits the cipher
  and kills the read loop). `RemoteConnect.connect` positionally skips the
  replay-cached M2 and takes the 2nd `PV_Next`.
- **C4 — OPACK float32**: decoder lacked tag `0x35` (real `_systemInfo` uses
  it) → added (pyatv `struct.unpack("<f")`).
- **C5 — session identity**: `_idsID`/`_i`/`_pubID` were a double-hex-encoded
  clientId + the ATV's mDNS id; must send the verbatim pairing-id string
  (`String(credentials.clientId)`), the identity pair-verify authenticated.
- **C6 — `_sessionStop`**: was sent with empty content → tvOS "No sessionID".
  Now sends `{ _srvT:"com.apple.tvremoteservices", _sid:<combined sid> }`
  (`SessionHandshake.sid` threaded into `CompanionSessionImpl`).

Robustness: ✅ FIXED. `CompanionConnection.readLoop` no longer dies on a bad
frame — a decode/decrypt failure of one frame is skipped (resync by the
plaintext-header length; Companion is length-prefixed) and the reader keeps
going (regression test
`CompanionConnectionTest.readLoopSkipsUndecryptableFrameAndContinues`). Note:
`Frame.decode` decrypting every frame once `cipher!=null` is intentional —
pyatv does the same (`if self._chacha and len>0`); correct given the C3
ordering fix, so it is NOT changed (changing it would diverge from the
authoritative reference).

## Resume checklist (next session)
1. `git pull` (work is on `main` @ origin).
2. Set `JAVA_HOME` (Temurin 17 path above). Re-confirm: clean build → **64
   tests green**, `PairSetupGoldenTest` byte-identical.
3. Re-confirm real device: `scan` → `客厅 … AppleTV14,1 … true`; then
   `pair "客厅@192.168.7.134:49153"` (type the PIN shown on the TV) →
   `Paired`; then `menu "客厅@..."` → `OK` + TV reacts.
4. Optional next work: replace synthetic fixtures with a real pyatv capture
   per `trace-tools/.../CaptureGuide.md` (makes the golden tests a real-device
   baseline); then Plan 2 (companion command set).
5. Project memory: `/Users/shinya/.claude/projects/-Users-shinya-Downloads-apple-tv-controller/memory/`
   (`MEMORY.md` index).
