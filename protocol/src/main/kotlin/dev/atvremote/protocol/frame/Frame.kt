package dev.atvremote.protocol.frame
import dev.atvremote.protocol.crypto.ChaCha
enum class FrameType(val value: Int) {
    Unknown(0), NoOp(1), PS_Start(3), PS_Next(4), PV_Start(5), PV_Next(6),
    U_OPACK(7), E_OPACK(8), P_OPACK(9), PA_Req(10), PA_Rsp(11),
    SessionStartRequest(16), SessionStartResponse(17), SessionData(18),
    FamilyIdentityRequest(32), FamilyIdentityResponse(33), FamilyIdentityUpdate(34);
    companion object { fun from(v: Int) = entries.firstOrNull { it.value == v } ?: Unknown }
}
object Frame {
    const val HEADER = 4; const val TAG = 16
    fun encode(type: FrameType, payload: ByteArray, cipher: ChaCha?): ByteArray {
        val encrypt = cipher != null && payload.isNotEmpty()
        val declaredLen = payload.size + if (encrypt) TAG else 0
        val header = byteArrayOf(type.value.toByte(),
            ((declaredLen shr 16) and 0xFF).toByte(), ((declaredLen shr 8) and 0xFF).toByte(), (declaredLen and 0xFF).toByte())
        val body = if (encrypt) cipher!!.encryptOut(payload, header) else payload
        return header + body
    }
    /** Returns (type, payload, bytesConsumed) or null if buffer incomplete. */
    fun decode(buf: ByteArray, cipher: ChaCha?): Triple<FrameType, ByteArray, Int>? {
        if (buf.size < HEADER) return null
        val len = ((buf[1].toInt() and 0xFF) shl 16) or ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
        val total = HEADER + len
        if (buf.size < total) return null
        val header = buf.copyOfRange(0, HEADER)
        var body = buf.copyOfRange(HEADER, total)
        if (cipher != null && body.isNotEmpty()) body = cipher.decryptIn(body, header)
        return Triple(FrameType.from(buf[0].toInt() and 0xFF), body, total)
    }
}
