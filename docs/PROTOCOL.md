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
