# Apple TV Companion Protocol — Wire Behavior Reference

This document records verified protocol behavior from the authoritative pyatv reference
(`github.com/postlund/pyatv`). Corrections from the pyatv-wins rule are noted explicitly.

---

## Plan-2 verified wire behavior

### Companion frame transport (verified pyatv)

Source: `pyatv/protocols/companion/api.py` and `protocol.py` (master, 2026-05-16)

| Frame | pyatv method | `_t` | Our channel method | Notes |
|-------|-------------|------|--------------------|-------|
| `_touchStart` | `_touch_start` (L447) → `_send_command` (L450) | `2` (Request) | `exchange` | awaits `_t=3` reply, DEFAULT_TIMEOUT=5s |
| `_touchStop`  | `_touch_stop` (L456) → `_send_command` (L458) | `2` (Request) | `exchange` | awaits reply |
| `_hidC` (press/release) | `hid_command` (L288) → `_send_command` (L290) | `2` (Request) | `exchange` | awaits reply |
| `_hidT` (touch/swipe/clickTouch) | `hid_event` (L294) → `_send_event` (L300) | `1` (Event) | `sendEvent` | **fire-and-forget, no reply** |

`MessageType.Event = 1`, `MessageType.Request = 2` (`protocol.py` `MessageType` L54, values L57–58).
`_send_event` (L230) calls `send_opack` (no `exchange_opack`/await); `_send_command` (L160) calls
`exchange_opack` (L143) which suspends on `SharedData.wait(timeout)` (`protocol.py`
`_exchange_generic_opack` L155, `wait` call L166).

**ClickTouch payload** (`hid_event` pyatv api.py L294, `_send_event` call L300–309):
`_hidT { _ns: <live monotonic ns>, _tFg: 1, _cx: 1000, _cy: 1000, _tPh: 5 }`

- `_ns = time.time_ns() - self._base_timestamp` (live; pyatv bases on connect-time
  `_touchStart` timestamp — our `HidCommands.clickTouch` uses `nanoClock()` which
  is also live, though not anchored to the same base; not velocity-critical for Click)
- `_cx = int(TOUCHPAD_WIDTH) = 1000`, `_cy = int(TOUCHPAD_HEIGHT) = 1000`
- `TouchAction.Click` = `TouchPhase.Click.value = 5`

### HID commands (Task 7)

Source: `pyatv/protocols/companion/api.py` (master, 2026-05-16)

**HidCommand values** (`HidCommand` enum api.py L35; `Select` L43, `Sleep` L49, `Wake` L50):
- `Select = 6`
- `Sleep = 12`
- `Wake = 13`

**Press / release shape:**
- Press:   `_hidC { _hBtS: 1, _hidC: <cmd_value> }`  → `exchange` (`_t=2`)
- Release: `_hidC { _hBtS: 2, _hidC: <cmd_value> }`  → `exchange` (`_t=2`)

**click() sequencing** (`click` api.py L356, body L356–376):

| Action     | Wire sequence |
|------------|---------------|
| SingleTap  | press(Select) + 20ms + release(Select) + ClickTouch |
| DoubleTap  | (press(Select) + 20ms + release(Select) + ClickTouch) × 2 |
| Hold       | press(Select) + 1000ms + release(Select) + ClickTouch |

**ClickTouch** = `_hidT { _ns: <live ns>, _tFg: 1, _cx: 1000, _cy: 1000, _tPh: 5 }`
(`TouchPhase.Click.value == 5`, TOUCHPAD_WIDTH/HEIGHT == 1000.0; sent via `sendEvent`)

#### Corrections vs plan description (pyatv wins)

1. **DoubleTap — ClickTouch is inside the loop (not after).**
   The plan description said DoubleTap = "(press+release) × 2, then one ClickTouch".
   pyatv runs the ClickTouch inside `for _i in range(count)`, so DoubleTap produces
   two ClickTouch events (one after each tap), not one at the end.

2. **Hold — trailing ClickTouch IS sent.**
   The plan description said Hold = "press + ~1s + release (no trailing Click touch)".
   pyatv sends `hid_event(TOUCHPAD_WIDTH, TOUCHPAD_HEIGHT, TouchAction.Click)` after
   Hold's release (`click` api.py L356, Hold branch L370–376). The trailing ClickTouch is present for Hold.

All three corrections are reflected in `HidCommands.kt` (implementation) and
`HidClickTest.kt` (test expectations).

### Power commands (Task 11)

Source: `pyatv/protocols/companion/api.py` and `pyatv/protocols/companion/__init__.py` (master, 2026-05-16)

**HID power commands** (`HidCommand` enum api.py L35; `Sleep` L49, `Wake` L50):
- `Wake = 13` — `turn_on` (__init__.py L277): `hid_command(False, HidCommand.Wake)` → `_hidC {_hBtS:2, _hidC:13}`
- `Sleep = 12` — `turn_off` (__init__.py L284): `hid_command(False, HidCommand.Sleep)` → `_hidC {_hBtS:2, _hidC:12}`
- `down=False` → `_hBtS:2` (button-UP / release only); `hid_command` api.py L288–291.

**Status — FetchAttentionState** (`fetch_attention_state` api.py L437–445):
- Sent via `_send_command` (L439) → our `exchange()` (awaits reply, `_t=2`).
- Response shape: `resp["_c"]["state"]` — `_c` is a **map** with key `"state"`, NOT a bare int.
- SystemStatus mapping (`_system_status_to_power_state` __init__.py L256–265):

| `_c["state"]` | `SystemStatus` | `PowerStatus` |
|---------------|----------------|---------------|
| `0x01` | Asleep | Off |
| `0x02` | Screensaver | On |
| `0x03` | Awake | On |
| `0x04` | Idle | On |
| `0x00` | Unknown | Unknown |
| other | — | Unknown |

**Event subscriptions** (SystemStatus, TVSystemStatus — __init__.py L228–232) are Task 17 (EventSubscriptions), out of scope for T11.

#### Plan correction (Task 11 — pyatv wins)

The plan described `resp["_c"]` as a bare int. pyatv `fetch_attention_state` (api.py L440–445) reads
`content = resp.get("_c")` then `SystemStatus(content["state"])` — `_c` is a dict/map keyed by `"state"`.
The event handler `_handle_system_status_update` (__init__.py L249) also reads `data["state"]`.
`PowerController.status()` and `PowerControllerTest.statusMapsSystemStatus` are corrected to use
`resp["_c"]["state"]` (map path primary; bare-int fallback kept for defensive coding only).

---

#### Plan erratum (Tasks 3 & 7)

Plan-2 Task 3 (`TouchTransport`) and Task 7 (`HidCommands`) code blocks specified
`ch.exchange("_hidT", ...)`. This is incorrect — pyatv uses `_send_event` for `_hidT`
(`_t=1`, fire-and-forget), not `_send_command` (`_t=2`, awaits reply). On a real device
`exchange("_hidT")` would block for the full 5 s timeout per frame (10-step swipe ≈ 50 s,
then timeout exceptions). Corrected to `ch.sendEvent("_hidT", ...)` in both files.

Plan-2 Task 7 code block also specified `"_ns" to 0L` (constant zero). pyatv uses
`time.time_ns() - self._base_timestamp` (live monotonic ns). Corrected to `nanoClock()`
(injected live clock) in `HidCommands.clickTouch()`.

---

### RTIKeyedArchiver `_tiD` graph (Task 14 — verified vs real tvOS 26.5)

`_tiStart`/`_tiC` carry a `_c._tiD` value that is a **`bplist00` NSKeyedArchiver
blob** (`$archiver = "RTIKeyedArchiver"`, `$version = 100000`, top-level keys
`$version`/`$archiver`/`$top`/`$objects`). `KeyedArchiver`
(`session/rti/KeyedArchiver.kt`) is a faithful port of pyatv
`pyatv/protocols/companion/keyed_archiver.py` `read_archive_properties`.

**pyatv-wins reconciliation (CLAUDE.md rule).** pyatv's resolver is a *lazy
path-follower*: it `plistlib.loads` the blob, starts `element = data["$top"]`,
and for each path key does `element = element[key]`; **if** the element is a
`CF$UID` it dereferences it **once** via `$objects[uid]` and continues. It does
**NOT** collapse `NS.keys`/`NS.objects`/`NS.string`/`NS.uuidbytes` containers,
does **NOT** strip `$class`, and yields `None` on any `KeyError`/`IndexError`.
The Plan-2 Task-14 *draft* `KeyedArchiver.kt` (eager full-graph resolver that
collapsed all `NS.*` containers and stripped `$class`) **diverges from pyatv
and was NOT ported** — our port mirrors pyatv's lazy one-hop semantics exactly.
The plan's draft test path `documentState → docSt → contextBeforeInput` does
**not exist** in the real capture and was likewise discarded (captured bytes
are the authority).

**Verified real `$objects`/`$top` graph — `keyed-archiver-tiD.json`** (App
Store search field focused, empty; 1987 B blob, 44 `$objects`):

- `$top = { documentState: UID(1), documentTraits: UID(5), sessionUUID: UID(43) }`
- `documentState` → `obj[1] = { docSt:UID(2), originatedFromSource:false,
  updateMask:0, $class:UID(4) }` (RTIDocumentState)
- `documentState → docSt` → `obj[2] = { $class:UID(3) }` (TIDocumentState — no
  text fields on an empty search field; **there is no `contextBeforeInput`**)
- `sessionUUID` → `obj[43] = <16 raw bytes>` (the session UUID stored
  **directly as `$objects` data**, NOT wrapped in an `NSUUID{NS.uuidbytes}`)
- `documentTraits` → `obj[5]` = RTIDocumentTraits; nested values are `CF$UID`s
  one hop into `$objects` strings:
  `bId`→`obj[7]="com.wuziqi.SenPlayer"`, `app`→`obj[8]="SenPlayer"`,
  `prompt`→`obj[9]="搜索"` (UTF-16), plus `tiTraits`→TITextInputTraits,
  `traitsMask`/`afMode`/`cfmType` scalars.

**Verified `textOperations` graph — `text-set.json`** (two outbound `_tiC`
`_t=1` events):

- `$top = { textOperations: UID(1) }`
- clear step: `obj[1] = { keyboardOutput:UID(2), targetSessionUUID:UID(5),
  textToAssert:UID(4), $class:UID(7) }` (RTITextOperations);
  `textToAssert`→`obj[4]=""`; `keyboardOutput`→`obj[2]={ $class }`
  (TIKeyboardOutput, no insertion);
  `targetSessionUUID`→`obj[5]={ NS.uuidbytes:<16B>, $class:UID(6) }` (NSUUID —
  here the UUID **is** the `NSUUID{NS.uuidbytes}` wrapper; pyatv keeps the
  wrapper, it does not unwrap `NS.uuidbytes`)
- insert step: `keyboardOutput`→`obj[2]={ insertionText:UID(3), $class }`;
  `insertionText`→`obj[3]="HelloWorld"` (ASCII, not UTF-16)

Note the **two distinct UUID encodings** for the same logical session id: a
bare 16-byte `$objects` data leaf in the `_tiD` documentState blob vs an
`NSUUID{NS.uuidbytes}` wrapper in the `textOperations` blob. pyatv (and our
port) returns each verbatim — callers extract bytes per shape (relevant to
Task 15 `RtiPayloads` / Task 16 `KeyboardController`).

---

### RTI `_tiC` `_tiD` payload builders (Task 15 — verified vs pyatv + `text-set.json`)

`RtiPayloads` (`session/rti/RtiPayloads.kt`) builds the outbound `_tiC`
`_tiD` blob. **Port target / authority correction (pyatv-wins + captured
bytes):** the plan referenced a single
`pyatv/protocols/companion/plist_payloads.py` building a `documentState →
docSt → contextBeforeInput` graph. **That module and that path do not
exist.** pyatv's `plist_payloads` is a *package*; the builder is
`pyatv/protocols/companion/plist_payloads/rti_text_operations.py`
(`get_rti_clear_text_payload(session_uuid: bytes)` /
`get_rti_input_text_payload(session_uuid: bytes, text: str)`) — a
**pre-encoded `RTITextOperations` archive** (`plistlib.dumps(...,
FMT_BINARY, sort_keys=False)`), exactly the `textOperations` graph the real
tvOS-26.5 `text-set.json` capture carries. The plan's draft `RtiPayloads.kt`
(documentState shape, random-UUID default) was discarded — it disagrees with
both pyatv and the captured bytes; pyatv/captured-bytes win.

Caller flow (pyatv `api.text_input_command`, `companion/api.py`):
`_text_input_stop` → `_text_input_start`, read `_c._tiD`, extract
`session_uuid` via `keyed_archiver.read_archive_properties(ti_data,
["sessionUUID"], ["documentState","docSt","contextBeforeInput"])` (the bare
16-byte leaf — Task 14 graph above), then `_send_event("_tiC", {"_tiV":1,
"_tiD": <payload>})`. `text_set` = clear-then-input; `text_clear` = clear;
`text_append` = input (Task 16 `KeyboardController` owns this orchestration;
`RtiPayloads` only builds the two blobs, mirroring pyatv's split).

Envelope (both): `$version = 100000`, `$archiver = "RTIKeyedArchiver"`,
`$top = { textOperations: UID(1) }`. `sessionUuid` is the **raw 16 UUID
bytes**, re-wrapped here as `NSUUID { NS.uuidbytes, $class }` (pyatv keeps
the wrapper; does NOT unwrap). Verbatim pyatv `$objects` index layout:

- **`clearText`** (`get_rti_clear_text_payload`):
  - `[0]="$null"`
  - `[1]={$class:UID(7), targetSessionUUID:UID(5), keyboardOutput:UID(2),
    textToAssert:UID(4)}` (RTITextOperations)
  - `[2]={$class:UID(3)}` (TIKeyboardOutput — **no** insertion)
  - `[3]={$classname:"TIKeyboardOutput", $classes:["TIKeyboardOutput","NSObject"]}`
  - `[4]=""` (textToAssert value — empty)
  - `[5]={NS.uuidbytes:<sessionUuid>, $class:UID(6)}` (NSUUID)
  - `[6]={$classname:"NSUUID", $classes:["NSUUID","NSObject"]}`
  - `[7]={$classname:"RTITextOperations", $classes:["RTITextOperations","NSObject"]}`
- **`inputText`** (`get_rti_input_text_payload`):
  - `[0]="$null"`
  - `[1]={keyboardOutput:UID(2), $class:UID(7), targetSessionUUID:UID(5)}`
    (RTITextOperations — **no** `textToAssert`)
  - `[2]={insertionText:UID(3), $class:UID(4)}` (TIKeyboardOutput — with insertion)
  - `[3]=<text>` (insertionText value)
  - `[4]={$classname:"TIKeyboardOutput", $classes:["TIKeyboardOutput","NSObject"]}`
  - `[5]={NS.uuidbytes:<sessionUuid>, $class:UID(6)}` (NSUUID)
  - `[6]={$classname:"NSUUID", $classes:["NSUUID","NSObject"]}`
  - `[7]={$classname:"RTITextOperations", $classes:["RTITextOperations","NSObject"]}`

**Raw bytes are NOT byte-identical to pyatv/the capture by design.**
`Plist.write` builds its own identity-deduped `$objects`/string table; tvOS
(and our `KeyedArchiver`) resolve by `$top`/`CF$UID`, so the *decoded object
graph* — not the byte layout — is the conformance contract (the plan's own
comment states this). `RtiPayloadsGoldenTest` decodes our `clearText`/
`inputText` output with `KeyedArchiver` and asserts equality vs the real
`text-set.json` clear (`outDecoded()[0]`) / insert (`outDecoded()[1]`) blobs
on the meaningful fields: `textOperations.textToAssert` (`""` for clear,
absent for input), `textOperations.keyboardOutput.insertionText`
(`"HelloWorld"` for input, absent for clear), the `NSUUID{NS.uuidbytes}`
wrapper (16 bytes == the session id extracted from the capture, fed back in
for determinism), and the `RTITextOperations`/`NSUUID` `$class` chains. Text
is written verbatim (ASCII via bplist `0x5n`, non-ASCII via UTF-16 `0x6n`);
the captured `"HelloWorld"` is ASCII.

`KeyedArchiver.readProperty(blob, *path)` ports the single-path form;
`readProperties(blob, paths…)` ports pyatv's variadic `(*paths) -> tuple`
(parses once, independent paths, `null` on miss). Built on `Plist.read`
(≤ 44 total bplist objects after key/string expansion — 8 logical `$objects`
entries — well under the documented 255-UID Plist limit).

---

## Real tvOS 26.5 device-session findings (2026-05-16)

Six golden traces were captured from the real Apple TV (**客厅 / AppleTV14,1 /
192.168.7.134:49153 / tvOS 26.5**) via pyatv 0.17.0 and fixed into
`protocol/src/test/resources/goldentrace/*` (`mode == "realDevice"`). The
conformance suite `CommandsGoldenTest` (runbook T6/T8/T10/T12) drives our
production transports through `FakeProtocol` and asserts the emitted frame
**structure / identifier / phase ordering** against these captures (never the
non-deterministic `_ns`/`_x`).

**Validated against the real device (frame structure confirmed):**

- **touch-swipe** — `_touchStart`(_t2) → 31×`_hidT`(_t1, fire-and-forget:
  Press `_tPh=1` → 29×Hold `_tPh=3` → Release `_tPh=4`) → `_touchStop`(_t2).
  Confirms the pyatv-wins touch model on real tvOS 26.5: `_hidT` is a
  fire-and-forget `sendEvent` and `_ns` is session-relative; `_touchStart`/
  `_touchStop` are session-lifecycle frames sent once by connect()/close(),
  **not** per-gesture by `swipe()`.
- **hid-click (Select BUTTON)** — captured from pyatv `atvremote select` →
  `RemoteControl.select` → `_press_button(HidCommand.Select)`: exactly two
  `_hidC` frames, down `{_hBtS:1,_hidC:6}` then up `{_hBtS:2,_hidC:6}`, each
  acked `_rT:0`. This is the **button** path. It is a *different operation*
  from our `HidCommands.click(InputAction)`, which faithfully ports pyatv
  `CompanionAPI.click()` — a **touch** path emitting `_hidC` down/up **plus a
  trailing `_hidT` Click** (`_cx:1000,_cy:1000,_tPh:5`). `select(button)` =
  2×`_hidC`; `click(touch)` = `_hidC` + trailing `_hidT`. Both are correct;
  `click()`'s trailing `_hidT` is validated against pyatv source separately
  (`HidClickTest`), not against this button fixture.
- **launch-app** — `_launchApp{_bundleID:"com.apple.TVSettings"}`; Settings
  actually opened on the real device.
- **media-play** — `_mcc{_mcc:1}`; acked `_rT:0`.

**UNANSWERED on tvOS 26.5 (request-only fixtures — known upstream Apple regression):**

- **FetchAttentionState** (`power-status`) and
  **FetchLaunchableApplicationsEvent** (`apps-list`) receive **no reply** on
  tvOS 26.5 (request times out). This is a known upstream Apple tvOS-26
  regression — pyatv issue **#2823** / home-assistant **#168210**; **pyatv
  itself fails identically** (pyatv disables `power_state` on this firmware),
  so our port is correct. Only the request shape is capturable/asserted
  (`FetchAttentionState{}` / `FetchLaunchableApplicationsEvent{}`, empty `_c`).
  Response parsing — `_c={state:int}` 0x01–0x04 → PowerStatus, and the
  `{bundleId:name}` app map — is unverifiable on this firmware and remains
  covered by the synthetic-response unit tests
  (`PowerControllerTest`/`AppsControllerTest`).

**Swipe direction is tvOS-content-defined.** The `_cx/_cy` coordinates are not
a stable directional contract — how a swipe is interpreted depends on the
focused tvOS content/app, not on a fixed coordinate→direction mapping. For
deterministic D-pad navigation use the HID buttons (Up/Down/Left/Right/Select
via `_hidC`), not synthetic swipes.
