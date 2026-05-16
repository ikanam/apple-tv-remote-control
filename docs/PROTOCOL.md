# Apple TV Companion Protocol — Wire Behavior Reference

This document records verified protocol behavior from the authoritative pyatv reference
(`github.com/postlund/pyatv`). Corrections from the pyatv-wins rule are noted explicitly.

---

## Plan-2 verified wire behavior

### HID commands (Task 7)

Source: `pyatv/protocols/companion/api.py` (master, 2026-05-16)

**HidCommand values** (api.py lines 43, 49–50):
- `Select = 6`
- `Sleep = 12`
- `Wake = 13`

**Press / release shape:**
- Press:   `_hidC { _hBtS: 1, _hidC: <cmd_value> }`
- Release: `_hidC { _hBtS: 2, _hidC: <cmd_value> }`

**click() sequencing** (api.py lines 356–375):

| Action     | Wire sequence |
|------------|---------------|
| SingleTap  | press(Select) + 20ms + release(Select) + ClickTouch |
| DoubleTap  | (press(Select) + 20ms + release(Select) + ClickTouch) × 2 |
| Hold       | press(Select) + 1000ms + release(Select) + ClickTouch |

**ClickTouch** = `_hidT { _ns: <ns>, _tFg: 1, _cx: 1000, _cy: 1000, _tPh: 5 }`
(`TouchPhase.Click.value == 5`, TOUCHPAD_WIDTH/HEIGHT == 1000.0)

#### Corrections vs plan description (pyatv wins)

1. **DoubleTap — ClickTouch is inside the loop (not after).**
   The plan description said DoubleTap = "(press+release) × 2, then one ClickTouch".
   pyatv runs the ClickTouch inside `for _i in range(count)`, so DoubleTap produces
   two ClickTouch events (one after each tap), not one at the end.

2. **Hold — trailing ClickTouch IS sent.**
   The plan description said Hold = "press + ~1s + release (no trailing Click touch)".
   pyatv sends `hid_event(TOUCHPAD_WIDTH, TOUCHPAD_HEIGHT, TouchAction.Click)` after
   Hold's release (api.py lines 373–375). The trailing ClickTouch is present for Hold.

Both corrections are reflected in `HidCommands.kt` (implementation) and
`HidClickTest.kt` (test expectations).
