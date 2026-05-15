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

## Status

### Plan 1 (`:protocol` foundation): COMPLETE & pushed
Tasks 1–17 done. Discovery, HAP/SRP pair-setup, pair-verify, encrypted
Companion session, one HID command, CLI smoke tool. **55 tests green** (both
modules) from clean. Public API in `protocol/src/main/.../Api.kt` is LOCKED —
Plans 2/3 depend on those exact types; do not break them. Synthetic
golden-trace fixtures (Task 10 in-repo oracle) validate protocol logic without
a device; authoritative real-device validation = Task 17.

### Real-device validation (Task 17) — IN PROGRESS on the actual hardware
Test Apple TV is on the LAN: mDNS name **客厅**, model **AppleTV14,1**,
**192.168.7.134:49153**. Host LAN interface: `en1` = **192.168.7.131**
(many `utun*` VPN ifaces present; `InetAddress.getLocalHost()` → 127.0.0.1).

Build/run the CLI:
```
./gradlew :trace-tools:installDist
./trace-tools/build/install/trace-tools/bin/trace-tools scan
# pair/menu: see below
```
NOTE: when running mDNS/network via Claude Code Bash, the sandbox must be
disabled (real multicast). Optional override: env `ATVREMOTE_MDNS_ADDR=192.168.7.131`.

- **`scan` (discovery): ✅ FIXED & REAL-DEVICE VALIDATED.**
  Root cause: `JmdnsDiscovery` called `JmDNS.create()` no-arg → bound the mDNS
  socket to a VPN `utun` interface on this multi-homed Mac → never saw the
  Apple TV. Fixed (commits `6a44334` + `93aeab3`): LAN-interface bind via pure
  `selectBindAddress()` (env/ctor override + `JmDNS.create()` fallback),
  case-insensitive TXT (real keys are camelCase `rpMd`/`rpFl`, not
  `rpmd`/`rpfl`), IPv4-only host (skip IPv6/link-local resolves), and
  `SmokeCli scan` now accumulates over a 10s window. Shipped CLI now lists:
  `客厅@192.168.7.134:49153  客厅  192.168.7.134:49153  AppleTV14,1  true`.

- **`pair` / `menu`: ❌ OPEN BUG — this is the next task to fix.**
  Real-device-reasoned defect (NOT yet empirically reproduced — do that first):
  `PairingHandleImpl` **defers the entire M1..M6 exchange into `submitPin()`**
  because `PairSetup`'s constructor takes the PIN and `PairSetup.buildM1()`
  calls `srp.step1(pin)` (see `PairingHandleImpl.kt` init/submitPin and
  `PairSetup.kt:73`). But in HAP pair-setup the Apple TV only **displays its
  PIN after it receives M1 and replies M2**. So `SmokeCli pair` sets
  `AwaitingPin` and prompts "Enter PIN shown on TV" **before M1 is ever sent**
  → the TV is not showing a PIN → real pairing cannot proceed. The synthetic
  golden test cannot catch this (the fixture supplies a fixed PIN upfront and
  scripts M2/M4/M6).
  **Fix direction:** send M1 and consume M2 (which makes the ATV display the
  PIN) BEFORE transitioning to `AwaitingPin`/prompting; only M3..M6 need the
  PIN. Constraint: the LOCKED `PairSetupGoldenTest` constructs
  `PairSetup(seed, pairingId, pin)` and calls `buildM1()..consumeM6()` in
  order — must stay byte-identical. Likely options: (a) split `PairSetup` so
  `buildM1`/`consumeM2` are pin-free and the PIN is supplied before
  `buildM3` (keep the locked test satisfiable, e.g. via an overload/secondary
  entry), or (b) have `PairingHandleImpl` drive M1/M2 over the connection
  itself before constructing the pin-bearing `PairSetup`. Use
  systematic-debugging: first reproduce against the real device, then one
  root-cause fix + TDD, then re-validate.
  Pairing is interactive (PIN shows on the TV screen): attempt via the user
  running `! ./trace-tools/build/install/trace-tools/bin/trace-tools pair "客厅@192.168.7.134:49153"`
  and typing the PIN shown on the TV. `menu` needs a successful `pair` first
  (writes `~/.atvremote/credentials`). A successful pair→connect→menu is the
  authoritative end-to-end check of Tasks 6/11/12/14/16 against real tvOS 18.

After real-device pairing works, also replace the synthetic fixtures with a
real pyatv capture per `trace-tools/.../CaptureGuide.md` so the golden tests
become a real-device baseline.

## Resume checklist (next session)
1. `git pull` (work is on `main` @ origin).
2. Re-confirm discovery still works: build CLI, `bin/trace-tools scan` (sandbox
   off / real LAN) → expect the `客厅 … AppleTV14,1 … true` line.
3. Fix the `pair` ordering bug (see above) under systematic-debugging: reproduce
   on the real device first (interactive PIN), root-cause, single fix + TDD
   (keep locked `PairSetupGoldenTest` byte-identical, 55 tests green), then
   re-validate pair→menu on the real Apple TV.
4. Project memory: `/Users/shinya/.claude/projects/-Users-shinya-Downloads-apple-tv-controller/memory/`
   (`MEMORY.md` index) has roadmap + workflow notes.
