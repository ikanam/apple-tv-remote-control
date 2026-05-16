package dev.atvremote.protocol.session

import dev.atvremote.protocol.PowerStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * TDD tests for PowerController.
 *
 * pyatv wire facts (verified Step 0 — see docs/PROTOCOL.md "Power commands" section):
 *   turn_on  → hid_command(down=False, Wake)  → _hidC {_hBtS:2, _hidC:13}
 *   turn_off → hid_command(down=False, Sleep) → _hidC {_hBtS:2, _hidC:12}
 *   status   → _send_command "FetchAttentionState" → resp["_c"]["state"] (map, not bare int)
 *
 * Response shape (pyatv api.py L439–445 fetch_attention_state):
 *   resp = {"_c": {"state": <int>}}
 *   SystemStatus(content["state"])
 */
class PowerControllerTest {
    @Test fun powerOnSendsWakeUp() = runTest {
        val fake = FakeProtocol()
        PowerController(fake).power(true)
        assertEquals("_hidC", fake.exchanges.last().first)
        assertEquals(2, fake.exchanges.last().second["_hBtS"])
        assertEquals(13, fake.exchanges.last().second["_hidC"]) // Wake
    }

    @Test fun powerOffSendsSleepUp() = runTest {
        val fake = FakeProtocol()
        PowerController(fake).power(false)
        assertEquals(2, fake.exchanges.last().second["_hBtS"])
        assertEquals(12, fake.exchanges.last().second["_hidC"]) // Sleep
    }

    /**
     * pyatv-wins: _c is a MAP {"state": <int>}, not a bare int.
     * fetch_attention_state (api.py L439-445): content = resp.get("_c"); SystemStatus(content["state"])
     *
     * SystemStatus mapping (__init__.py L256-265):
     *   Asleep(0x01) → Off
     *   Screensaver(0x02) / Awake(0x03) / Idle(0x04) → On
     *   Unknown(0x00) / other → Unknown
     */
    @Test fun statusMapsSystemStatus() = runTest {
        suspend fun status(v: Int): PowerStatus {
            val fake = FakeProtocol()
            fake.onExchange = { name, _ ->
                assertEquals("FetchAttentionState", name)
                mapOf("_c" to mapOf("state" to v))
            }
            return PowerController(fake).status()
        }
        assertEquals(PowerStatus.Off, status(0x01))
        assertEquals(PowerStatus.On, status(0x02))
        assertEquals(PowerStatus.On, status(0x03))
        assertEquals(PowerStatus.On, status(0x04))
        assertEquals(PowerStatus.Unknown, status(0x00))
        assertEquals(PowerStatus.Unknown, status(0x99))
    }

    @Test fun statusReturnsUnknownWhenContentMissing() = runTest {
        val fake = FakeProtocol() // default onExchange -> emptyMap(), no "_c"
        assertEquals(PowerStatus.Unknown, PowerController(fake).status())
    }
}
