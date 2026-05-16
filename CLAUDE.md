# Apple TV Android Remote — Project Status & Resume Notes

> Working doc for resuming across sessions/devices. Last updated: 2026-05-16.

## What this project is

Android remote for Apple TV over the Companion protocol, built in 3 phases
(`docs/superpowers/plans/`):
- **Plan 1 — `:protocol` foundation** (pure Kotlin/JVM lib) + `:trace-tools` CLI
- **Plan 2 — companion command set** (touch/keyboard/apps/power/volume/events)
  — **code-first COMPLETE** (Tasks 1–4, 7, 9, 11, 12c, 13, 17, 18 + pyatv-wins
  fixes); device-dependent tasks (5/6/8/10/12-golden/14/15/16/19/20) deferred —
  see `docs/PLAN-2-DEVICE-SESSION-RUNBOOK.md`
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

### Plan 2 (companion command set): code-first COMPLETE (2026-05-16)

Executed code-first (per user decision) via subagent-driven dev with the same
two-stage review gate + pyatv-wins discipline. **101 tests green** (protocol 92
+ trace-tools 9) from a clean build, reproducibly (verified across multiple
`clean test` runs). All work committed per-task on `main` and **pushed to
origin** (`8dc354c..aee81a2`, 2026-05-16 — code-first pushed ahead of device
validation by owner decision; the device session below still gates the
*feature*, not the push). `Api.kt` Plan-1 surface untouched
(append/extend only); `PairSetupGoldenTest`/`PairVerifyGoldenTest`/
`SessionHandshakeTest`/`ButtonTest` byte-identical.

Done: T1 API types; T2 `SessionChannel`+`FakeProtocol`; T3 `TouchTransport`;
T4 wire `touch()`; T7 `HidCommands`+`click()`; T9 `AppsController`; T11
`PowerController`; T12c `MediaController` (code only); T13 `Plist` (bplist00);
T17 `EventSubscriptions`; T18 `ResilientSession`+reconnect supervisor+
drop-signal. New `docs/PROTOCOL.md` is the verified wire reference.

**pyatv-wins corrections made (the plan was wrong; pyatv was decisive — same
discipline as Task-17 Bug C):**
- **`_hidT` is fire-and-forget `sendEvent` (`_t=1`), NOT `exchange`.** The plan
  coded `ch.exchange("_hidT")` in T3/T7; pyatv `hid_event` uses `_send_event`.
  `exchange` blocks ~5 s/frame awaiting a reply tvOS never sends (a swipe ≈
  50 s of timeouts). `_touchStart`/`_touchStop`/`_hidC`/`FetchAttentionState`/
  `_launchApp`/`_mcc`/`_interest`-reg are correctly per-pyatv (`exchange` for
  commands, `sendEvent` for `_interest`). Transport-per-frame table is in
  `docs/PROTOCOL.md`.
- **`click()`**: DoubleTap emits the Click-touch **inside** the per-tap loop (2
  Click-touches); Hold **does** send a trailing Click-touch (plan said none);
  click-touch is `_cx:1000,_cy:1000` + live `_ns`.
- **`FetchAttentionState`** response `_c` is a map `{"state":<int>}` (plan said
  bare int); 0x01→Off, 0x02/03/04→On, else Unknown.
- **`EventSubscriptions`** tracks the active set **after** a successful send
  (pyatv `subscribe_event` order), not before.
- **`Plist.writeInt`** bplist00 length-escape used wrong marker `0x11`
  (2-byte) while writing 1 byte → corrupts any container ≥15 long; fixed to
  `0x10`. (Plan-authored format bug; would have broken T14/15 real blobs.)
- **`CompanionConnection.awaitClosed()`** must emit from `close()` itself, not
  only from `readLoop`'s `finally` — a `close()` racing ahead of readLoop's
  first dispatch (`scope.cancel()` before the coroutine body starts) otherwise
  never signals (the resilience supervisor depends on this).
- Known `Plist` limits (documented, watch in T14/15): UID/refs capped at 255;
  4-byte `0x22` float read as 8-byte double.

**Keyboard members (`textGet/Set/Clear/Append`, `keyboardFocus`) are still
Task-1 `NotImplementedError` stubs BY DESIGN** — T16 depends on the deferred
real `_tiD` capture (T14/15). The `connectionState` of a standalone
`CompanionSessionImpl` is always `Connected`; the live value is owned by the
wrapping `ResilientSession` (T19, deferred, is the zero-stubs/flows gate).

### Plan-2 whole-stack pyatv-conformance audit (2026-05-16)

Per owner directive ("compare ALL implemented protocol layers to pyatv; pyatv
wins"), all layers were diffed line-by-line vs exact pyatv master via 7
parallel read-only audits. Verdicts: **crypto (SRP/ChaCha/HKDF/Curves),
PairSetup/PairVerify, SessionHandshake, FrameType/Connection/Protocol,
discovery, TLV8 — CONFORMANT** (all HKDF salt/info strings, C1 two-nonce
split, FrameType ints, RemoteButton↔HidCommand, pair TLV/wrappers exact).
Genuine divergences found & fixed (each pyatv-verified, TDD, two-stage
reviewed; commits `0f3e814`→`84a99aa`):
- **OPACK decoder object-list**: containers AND empty string/data must be
  excluded/included exactly per pyatv `_unpack` `add_to_object_list` (was
  mis-resolving back-refs); tag `0x06` decoded as 8-byte LE int (was crash);
  dict keys `.toString()` (was `ClassCastException`).
- **`sendEvent` now adds `_x`** from the shared xid counter (pyatv `send_opack`
  injects it on every frame incl. events).
- **Touch model**: `_hidT._ns` is session-relative to the connect-time
  `_touchStart` (base threaded `SessionHandshake`→`TouchTransport`); per-gesture
  `_touchStart`/`_touchStop` removed (`swipe()` = `hid_event` loop, pyatv-exact).
- **`close()` sends `_touchStop` after `_sessionStop`** (pyatv `disconnect()`
  order).
- **`_systemInfo._i`** is a distinct random `rp_id` (`os.urandom(6).hex()`
  format), no longer conflated with the pairing `clientId` (`_idsID` stays
  clientId, `_pubID` deviceId).

**Two deliberate, owner-flagged NON-reversions** (literal pyatv parity would be
worse — exceptions to "pyatv wins", documented for transparency):
- **M6 accessory-signature verification**: we verify it; pyatv has
  `# TODO: verify signature here` and skips. Matching pyatv = deleting a valid
  security check with zero wire/interop effect — we stay stricter.
- **discovery device id** = `name@ip:port` (not pyatv's stable `rpMRtID`):
  intentional Plan-1 CLI simplification; literal parity needs a `CredentialStore`
  key migration — a Plan-3 concern.

**Device re-validation owed (touch model / `_touchStop`-close / `_systemInfo._i`
modify Task-17-validated handshake/touch; correct-by-construction vs pyatv but
unproven on real tvOS):** the deferred device session
(`docs/PLAN-2-DEVICE-SESSION-RUNBOOK.md`) must re-confirm `pair→connect→menu` +
touch/swipe/close on real 客厅.

## Resume checklist (next session)
1. `git pull` (Plan-2 code-first + the whole-stack pyatv-conformance fixes are
   committed per-task on `main`).
2. Set `JAVA_HOME` (Temurin 17 path above). Re-confirm: clean build → **114
   tests green**, `PairSetupGoldenTest`/`PairVerifyGoldenTest` byte-identical.
3. Re-confirm real device: `scan` → `客厅 … AppleTV14,1 … true`; then
   `pair "客厅@192.168.7.134:49153"` (type the PIN shown on the TV) →
   `Paired`; then `menu "客厅@..."` → `OK` + TV reacts (this also regression-
   checks the new `ResilientSession` wrap is transparent when Connected).
4. **Plan-2 device session**: follow `docs/PLAN-2-DEVICE-SESSION-RUNBOOK.md`
   — pair pyatv with 客厅 (never done), capture golden traces (T5/14),
   conformance tests (T6/8/10/12-golden/15), `KeyboardController` (T16),
   zero-stubs/flows gate (T19), CLI + full device smoke (T20).
5. Project memory: `/Users/shinya/.claude/projects/-Users-shinya-Downloads-apple-tv-controller/memory/`
   (`MEMORY.md` index).
