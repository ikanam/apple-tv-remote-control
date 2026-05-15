package dev.atvremote.protocol.frame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
class FrameTest {
    @Test fun enumValues() {
        assertEquals(3, FrameType.PS_Start.value); assertEquals(8, FrameType.E_OPACK.value)
        assertEquals(FrameType.PV_Next, FrameType.from(6))
    }
    @Test fun headerEncodeDecodePlaintext() {
        val payload = byteArrayOf(1,2,3,4,5)
        val framed = Frame.encode(FrameType.U_OPACK, payload, null)
        // header: type byte + 3-byte BE length (= 5, no +16 since not encrypted)
        assertEquals(FrameType.U_OPACK.value, framed[0].toInt())
        assertEquals(5, ((framed[1].toInt() and 0xFF) shl 16) or ((framed[2].toInt() and 0xFF) shl 8) or (framed[3].toInt() and 0xFF))
        val (ft, body, consumed) = Frame.decode(framed, null)!!
        assertEquals(FrameType.U_OPACK, ft); assertTrue(body.contentEquals(payload)); assertEquals(framed.size, consumed)
    }
}
