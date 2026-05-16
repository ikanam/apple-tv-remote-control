# Plan 2 — Device Session Runbook

> Plan 2 was executed **code-first** (per user decision, 2026-05-16). All
> device-independent code is done, committed per-task on `main` and **pushed
> to origin** (`aee81a2`), **101 tests green** from clean. This runbook is the remaining
> device-dependent work. Tasks reference
> `docs/superpowers/plans/2026-05-15-atv-remote-plan-2-companion-commands.md`.
> Read the **Protocol-debugging rule** and the **Plan 2 pyatv-wins
> corrections** in `CLAUDE.md` first — pyatv is decisive.

## Concrete environment (from CLAUDE.md — no placeholders)

- Device: **客厅** `AppleTV14,1` `192.168.7.134:49153` tvOS ≈715.2
- `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home`
- mDNS via Claude Bash needs the sandbox disabled (real multicast); optional
  `export ATVREMOTE_MDNS_ADDR=192.168.7.131`
- CLI build: `JAVA_HOME=$JAVA_HOME ./gradlew :trace-tools:installDist` then
  `TT=./trace-tools/build/install/trace-tools/bin/trace-tools`,
  `DEV='客厅@192.168.7.134:49153'`
- Per-task commits on `main`; **push only after the device session passes**.

## Prerequisite (NEW — Plan 1 never did this): pair pyatv with 客厅

Plan 1 only paired *our* Kotlin client. pyatv itself was never paired and is
required to capture golden traces.

```bash
export ATV_HOST=192.168.7.134
python3 -m venv .venv && . .venv/bin/activate && pip install -U pyatv
atvremote scan 2>/dev/null                       # note 客厅's Identifier
export ATV_ID="<identifier printed for 客厅>"
atvremote --id "$ATV_ID" --protocol companion pair   # type PIN shown on TV
atvremote --id "$ATV_ID" --protocol companion --debug playing 2>/dev/null | head -1  # no auth error ⇒ paired
```

## Regression gate first (Task-17 + ResilientSession transparency)

```bash
JAVA_HOME=$JAVA_HOME ./gradlew clean test      # expect 114 green, byte-identical goldens
JAVA_HOME=$JAVA_HOME ./gradlew :trace-tools:installDist
$TT scan ; $TT pair "$DEV" ; $TT menu "$DEV"   # OK + TV reacts ⇒ ResilientSession wrap transparent when Connected
```

## pyatv-conformance changes needing real-tvOS re-validation (2026-05-16)

The whole-stack pyatv audit (see CLAUDE.md "Plan-2 whole-stack pyatv-conformance
audit") corrected several layers; three touch Task-17-validated handshake/touch
and are correct-by-construction vs pyatv but **unproven on real 客厅** — confirm
during `pair→connect→menu` + commands here:
- **`_systemInfo._i` is now a distinct random `rp_id`** (`os.urandom(6).hex()`
  12-char hex), no longer the pairing `clientId`. Confirm tvOS still accepts the
  handshake (connect succeeds, `menu` reacts).
- **Touch `_ns` is now session-relative** to the connect-time `_touchStart`;
  per-gesture `_touchStart`/`_touchStop` removed. Confirm `swipe`/`touch` move
  focus correctly on the real device (T6/T20).
- **`close()` now sends `_touchStop` after `_sessionStop`.** Confirm a clean
  disconnect (no tvOS error; reconnect still works).
Also exercised by the resilience end-to-end section below.

## Deferred tasks (do in order)

- **T5 — capture golden traces** (`Task 5`): with pyatv paired, capture
  `swipe`/`select`/`app_list`/`launch_app`/`power_state`/`play` into
  `protocol/src/test/resources/goldentrace/{touch-swipe,hid-click,apps-list,launch-app,power-status,media-play}.json`.
  When recording `swipe`/`select`, note that `_hidT` is an **event** (`_t=1`,
  fire-and-forget) — confirm the captured frames show this (validates the
  code-first pyatv-wins `_hidT` correction against real tvOS).
- **T6 — touch swipe golden conformance** (`Task 6`): add `outDecoded()` to
  `GoldenTrace`; assert `TouchTransport.swipe` name/phase sequence vs
  `touch-swipe.json`.
- **T8 — click golden conformance** (`Task 8`): assert `HidCommands.click`
  vs `hid-click.json`. **Verify on real tvOS the code-first click
  corrections**: DoubleTap = 2 Click-touches (Click-touch inside the per-tap
  loop), Hold has a trailing Click-touch, click-touch `_cx:1000,_cy:1000`.
- **T10 — apps golden conformance** (`Task 10`): add `inDecoded()`; assert
  `AppsController` vs `apps-list.json`/`launch-app.json`.
- **T12 golden part** (`Task 12`): add `powerStatusAndMediaPlayMatchFixtures`
  to `CommandsGoldenTest` (the `MediaController` code is already done as T12c).
  **Confirm `FetchAttentionState` real `_c` is a map `{"state":int}`** (the
  code-first pyatv-wins correction) and the 0x01/0x02/0x03/0x04 mapping.
- **T14 — capture real `_tiD` + `text_set`** (`Task 14`): put a text field on
  screen on 客厅; `atvremote ... text_set=HelloWorld` / `text_get`; record
  `keyed-archiver-tiD.json` + `text-set.json`; port `KeyedArchiver`. **Watch
  the documented `Plist` limits**: UID/refs >255 and 4-byte `0x22` float — if
  the real `$objects` graph exceeds 255 entries or carries `Float`, widen
  `Plist` (per its KDoc) and the bplist tests.
- **T15 — RtiPayloads** (`Task 15`): port `plist_payloads.py`; golden-validate
  the `_tiC` payload vs `text-set.json` (authoritative real bytes).
- **T16 — KeyboardController + focus flow** (`Task 16`): implement the keyboard
  members currently `NotImplementedError` stubs in `CompanionSessionImpl`
  (`textGet/textSet/textClear/textAppend`, `keyboardFocus` StateFlow). Depends
  on T14/T15.
- **T19 — flows integration + zero-stubs gate** (`Task 19`): the
  `noStubsAndFlowsExposed` test; finalize `connectionState` exposure; full
  `:protocol:test` regression (no `NotImplementedError` remaining).
- **T20 — CLI + real-device smoke** (`Task 20`): add
  swipe/click/text/apps/launch/power/media subcommands to `SmokeCli`; exercise
  each against 客厅. Record verified results + any tvOS quirks in
  `docs/PROTOCOL.md`.

## Resilience end-to-end (validate the T18 reconnect on real device)

The reconnect supervisor + drop-signal are code-complete and unit-tested but
**never exercised against a live socket**. Confirm on 客厅:

- Real TCP drop while connected → `CompanionConnection.awaitClosed()` fires
  (`SocketException`) → `ResilientSession` goes `Reconnecting` → exponential
  backoff → `PairVerify`+`SessionHandshake` (C3/C5/C6) re-auth on tvOS 26.5 →
  `onReconnected` swaps the delegate + flips state→`Connected` (**no replay** —
  owner-approved Plan-2 §7 change 2026-05-16; buttons issued mid-reconnect were
  dropped, caller re-issues) → session resumes.
  **STIMULUS CAVEAT (2026-05-16, validated):** a brief macOS Wi-Fi off/on OR a
  short Mac sleep does **NOT** sever the idle Companion TCP — the read loop just
  stalls, in-flight `exchange` hits its 5 s timeout, then resumes on the SAME
  session; `awaitClosed()` never fires (not a bug — under-stimulated). To
  exercise the reconnect path you must force a real socket close (TCP RST via
  `pfctl`/a kill tool, the TV side dropping, or a long/hard outage).
- During `Reconnecting`: touch/button/click **dropped silently (no queue, no
  replay)**; keyboard/app/power/media throw `CompanionUnavailableException`.
- Deliberate `close()` → supervisor cancelled → **no spurious reconnect**
  (✅ live-validated on 客厅 2026-05-16). Transparent-when-Connected also
  ✅ live-validated; the drop-while-Reconnecting + reconnect-recovers path is
  unit-tested only (live socket-sever stimulus unresolved — see caveat).
- Credentials invalidated → `Disconnected`, loop stops (no infinite retry).

## On success

Update `docs/PROTOCOL.md` with any real-tvOS findings, run the full suite, and
push (code-first was already pushed `aee81a2`; push the device-session commits
once green on real 客厅).
