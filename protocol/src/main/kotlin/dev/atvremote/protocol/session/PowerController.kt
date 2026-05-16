package dev.atvremote.protocol.session

import dev.atvremote.protocol.PowerStatus
import dev.atvremote.protocol.connection.CommandChannel

/**
 * Power over Companion (pyatv-wins, verified 2026-05-16):
 *
 *   power(true)  → hid_command(down=False, Wake)  → _hidC {_hBtS:2, _hidC:13}
 *   power(false) → hid_command(down=False, Sleep) → _hidC {_hBtS:2, _hidC:12}
 *
 * Both use _hBtS:2 (button-UP / release only).
 * pyatv/protocols/companion/__init__.py turn_on (L272–278) / turn_off (L279–284)
 * call hid_command(down=False, ...), where `down=False` → `_hBtS: 2`
 * (pyatv/protocols/companion/api.py hid_command (L288–292)).
 * HID codes: pyatv/protocols/companion/api.py HidCommand enum (L35); Wake=13 (L50), Sleep=12 (L49).
 *
 * status → _send_command "FetchAttentionState" (exchange, awaits reply):
 *   Response shape (pyatv/protocols/companion/api.py fetch_attention_state (L437–445)):
 *     resp["_c"]["state"] is an int (NOT a bare int on _c itself).
 *   SystemStatus mapping (pyatv/protocols/companion/__init__.py _system_status_to_power_state (L256–265)):
 *     Asleep (0x01)                        → Off
 *     Screensaver (0x02) / Awake (0x03) / Idle (0x04) → On
 *     Unknown (0x00) / other               → Unknown
 *
 * pyatv-wins correction vs plan: the plan described _c as a bare int; pyatv reads
 * _c as a map keyed by "state". See docs/PROTOCOL.md "Power commands" section.
 *
 * Divergence: pyatv raises on missing/!map _c; we degrade to Unknown (-1 -> Unknown).
 *
 * Subscriptions for SystemStatus / TVSystemStatus events are Task 17 (EventSubscriptions)
 * — out of scope here.
 */
internal class PowerController(private val ch: CommandChannel) {
    suspend fun power(on: Boolean) {
        val cmd = if (on) HidCommands.WAKE else HidCommands.SLEEP
        ch.exchange("_hidC", mapOf("_hBtS" to 2, "_hidC" to cmd))
    }

    suspend fun status(): PowerStatus {
        val resp = ch.exchange("FetchAttentionState", emptyMap())
        val raw: Int = ((resp["_c"] as? Map<*, *>)?.get("state") as? Number)?.toInt() ?: -1
        return when (raw) {
            0x01 -> PowerStatus.Off
            0x02, 0x03, 0x04 -> PowerStatus.On
            else -> PowerStatus.Unknown
        }
    }
}
