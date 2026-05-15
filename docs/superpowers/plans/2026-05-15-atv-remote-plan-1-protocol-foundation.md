# Apple TV Remote — Plan 1: Protocol Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `:protocol` Kotlin/JVM module so it can discover an Apple TV, pair (PIN), establish an encrypted Companion session, and send one HID command — validated byte-for-byte against pyatv golden traces and a real tvOS 18 device.

**Architecture:** Pure Kotlin/JVM module with zero Android dependencies. Layered: OPACK codec → HAP TLV8 → crypto primitives (BouncyCastle) → Companion framing → connection/protocol → pair-setup/verify → session handshake → public `AppleTvRemote` facade. A `:trace-tools` module captures pyatv wire traces and replays them as golden-trace conformance fixtures.

**Tech Stack:** Kotlin 2.0, Gradle 8.x (Kotlin DSL, version catalog), kotlinx-coroutines, BouncyCastle (`bcprov-jdk18on`), jmDNS, JUnit5 + kotlin-test, pyatv (reference oracle, run via `pipx`/`venv`).

---

## Reference: pyatv source map (the byte-level spec)

This plan ports specific pyatv modules. Exact constants below are source-verified from `github.com/postlund/pyatv` master. When a task says "port from pyatv X", read that file and replicate the algorithm; validate with a golden-trace test.

- **OPACK**: `pyatv/support/opack.py` (NOT `protocols/companion/opack.py`). Little-endian. Tag bytes: `null=0x04`, `true=0x01`, `false=0x02`, `uuid=0x05`+16B, small int 0–39 = `0x08..0x2F` (byte = value+8), int `0x30`(1B)/`0x31`(2B)/`0x32`(4B)/`0x33`(8B) LE, float64=`0x36`+8B LE, short string `0x40..0x60` (`0x40`+len, len≤32, utf8), string len-prefixed `0x61`(1B)/`0x62`(2B)/`0x63`(3B)/`0x64`(4B), short data `0x70..0x90` (`0x70`+len, len≤32), data len-prefixed `0x91`(1B)/`0x92`(2B)/`0x93`(4B)/`0x94`(8B), array `0xD0+min(len,0xF)` (terminator `0x03` if len≥0xF), dict `0xE0+min(len,0xF)` (terminator `0x03` if len≥0xF), back-ref `0xA0..0xC0` (index) / `0xC1..0xC4` (1/2/4/8B index). Object-list dedup: append packed byte-sequences with `len>1` (not bool/null/smallint/list/dict) in document order; reuse emits back-ref.
- **Companion frame** (`protocols/companion/connection.py`): `HEADER_LENGTH=4` = 1 byte FrameType + 3-byte **big-endian** payload length. `payload_length` includes `+16` auth tag when encrypted and payload>0. Encrypt/decrypt with `aad = 4-byte header`.
- **FrameType** (exact): `Unknown=0, NoOp=1, PS_Start=3, PS_Next=4, PV_Start=5, PV_Next=6, U_OPACK=7, E_OPACK=8, P_OPACK=9, PA_Req=10, PA_Rsp=11, SessionStartRequest=16, SessionStartResponse=17, SessionData=18, FamilyIdentityRequest=32, FamilyIdentityResponse=33, FamilyIdentityUpdate=34`. (No value 2.)
- **SRP** (`pyatv/auth/hap_srp.py`): username `"Pair-Setup"`, password = PIN string, RFC 5054 **3072-bit** group (g=5), hash **SHA-512**. Client `a` seeded from Ed25519 auth private key bytes.
- **HKDF**: HKDF-SHA512, 32-byte output, `salt`/`info` as UTF-8 bytes.
- **ChaCha20-Poly1305**: 16-byte tag appended. Pair-setup/verify uses 8-byte explicit nonces left-padded to 12 with zeros: `PS-Msg05`, `PS-Msg06`, `PV-Msg02`, `PV-Msg03`. Connection encryption uses 12-byte LE counter nonce starting at 0, separate counters per direction, AAD = frame header.
- **HKDF salt/info strings**: pair-setup sign `Pair-Setup-Controller-Sign-Salt`/`Pair-Setup-Controller-Sign-Info`; pair-setup encrypt `Pair-Setup-Encrypt-Salt`/`Pair-Setup-Encrypt-Info`; pair-verify encrypt `Pair-Verify-Encrypt-Salt`/`Pair-Verify-Encrypt-Info`; connection keys salt = `""`, output info `ClientEncrypt-main`, input info `ServerEncrypt-main`.
- **TLV8** (`pyatv/auth/hap_tlv8.py`): `Method=0x00, Identifier=0x01, Salt=0x02, PublicKey=0x03, Proof=0x04, EncryptedData=0x05, SeqNo=0x06, Error=0x07, Signature=0x0A, Permissions=0x0B, Name=0x11, Flags=0x13`. Values >255 bytes split into 255-byte chunks repeating the tag; reader concatenates repeated tags.
- **Pair-setup OPACK wrapper**: `{ "_pd": tlv_bytes, "_pwTy": 1 }`; pair-verify M1: `{ "_pd": tlv, "_auTy": 4 }`. `PAIRING_DATA_KEY = "_pd"`.
- **Protocol** (`protocols/companion/protocol.py`): each command = `exchange_opack(E_OPACK, {"_i": name, "_t": 2, "_c": content})`, response matched by `_x` XID; events `_t=1` carry `_i`/`_c`. `_setup_encryption` after pair-verify.
- **Handshake order** (`api.py`): `_systemInfo` → `_touchStart` → `_sessionStart` → `_tiStart` → subscribe `_iMC`.
- **HID button** (`HidCommand`): `Up=1,Down=2,Left=3,Right=4,Menu=5,Select=6,Home=7,VolumeUp=8,VolumeDown=9,Siri=10,Screensaver=11,Sleep=12,Wake=13,PlayPause=14,...`. Command `_hidC`, content `{"_hBtS": 1|2, "_hidC": value}` (1=down,2=up).
- **mDNS**: browse `_companion-link._tcp.local.`; TXT keys consumed by Companion: `rpmd` (model), `rpfl` (hex flags; `0x4000` bit = pairing-with-PIN supported, `0x04` = disabled). Port from SRV record.

---

## Public API Contract (LOCKED — Plans 2 & 3 depend on these exact types)

`module: protocol` · package root `dev.atvremote.protocol`

```kotlin
package dev.atvremote.protocol

/** A discovered Apple TV on the LAN. */
data class AppleTvDevice(
    val id: String,            // stable unique id (from mDNS name+host)
    val name: String,          // human name
    val host: String,          // resolved IPv4/IPv6
    val port: Int,             // Companion TCP port (SRV)
    val model: String?,        // from rpmd
    val pairable: Boolean,     // rpfl 0x4000 set and 0x04 clear
)

/** Long-term credentials persisted by the app (opaque blob + structured access). */
data class HapCredentials(
    val clientId: ByteArray,   // our pairing id (uuid string bytes)
    val clientLtsk: ByteArray, // our Ed25519 private (32B seed)
    val clientLtpk: ByteArray, // our Ed25519 public (32B)
    val atvId: ByteArray,      // ATV identifier
    val atvLtpk: ByteArray,    // ATV Ed25519 public (32B)
) {
    fun serialize(): String           // base64 of a fixed field layout
    companion object { fun parse(s: String): HapCredentials }
}

sealed interface PairingState {
    data object AwaitingPin : PairingState                 // ATV is showing a PIN
    data class Completed(val credentials: HapCredentials) : PairingState
    data class Failed(val reason: String) : PairingState
}

/** Buttons available over Companion HID (values are the wire values). */
enum class RemoteButton(val hid: Int) {
    Up(1), Down(2), Left(3), Right(4), Menu(5), Select(6), Home(7),
    VolumeUp(8), VolumeDown(9), PlayPause(14)
}

/** Discovery: cold Flow that emits the current device set as it changes. */
interface DeviceDiscovery {
    fun devices(): kotlinx.coroutines.flow.Flow<List<AppleTvDevice>>
}

/**
 * One connected, encrypted Companion session. Created via AppleTvRemote.connect().
 * Plan 1 implements: button(), close(), and the connection/handshake lifecycle.
 * Plan 2 adds: touch(), keyboard, apps, power, mediaControl, events.
 */
interface CompanionSession {
    suspend fun button(button: RemoteButton, down: Boolean)
    suspend fun close()
}

/** Top-level entry point. */
object AppleTvRemote {
    fun discovery(): DeviceDiscovery

    /** Pair with a device. Emits AwaitingPin, caller calls submitPin(), then Completed/Failed. */
    fun pair(device: AppleTvDevice): PairingHandle

    /** Connect using stored credentials; performs pair-verify + session handshake. */
    suspend fun connect(device: AppleTvDevice, credentials: HapCredentials): CompanionSession
}

interface PairingHandle {
    val state: kotlinx.coroutines.flow.StateFlow<PairingState>
    suspend fun submitPin(pin: String)
    fun cancel()
}
```

**Internal package layout (not part of the locked API):**
`dev.atvremote.protocol.opack`, `.tlv8`, `.crypto`, `.frame`, `.connection`, `.pairing`, `.session`, `.discovery`.

---

## File Structure

```
settings.gradle.kts                         multi-module include
gradle/libs.versions.toml                   version catalog
build.gradle.kts                            root
protocol/build.gradle.kts                   pure Kotlin/JVM module
protocol/src/main/kotlin/dev/atvremote/protocol/
  Api.kt                                    locked public types (above)
  opack/Opack.kt                            encoder + decoder
  opack/ObjectList.kt                       back-ref dedup list
  tlv8/Tlv8.kt                              HAP TLV8 encode/decode (+fragmentation)
  crypto/Srp.kt                             SRP-6a client (BouncyCastle)
  crypto/Curves.kt                          Ed25519 + X25519
  crypto/Hkdf.kt                            HKDF-SHA512
  crypto/ChaCha.kt                          ChaCha20-Poly1305 (8B + 12B nonce modes)
  frame/Frame.kt                            FrameType enum + header codec
  connection/CompanionConnection.kt         TCP + read loop
  connection/CompanionProtocol.kt           XID correlation, auth/opack dispatch
  pairing/PairSetup.kt                      M1–M6
  pairing/PairVerify.kt                     PV M1–M3 + connection keys
  session/SessionHandshake.kt               _systemInfo/_touchStart/_sessionStart/_tiStart
  session/CompanionSessionImpl.kt           implements CompanionSession.button/close
  discovery/JmdnsDiscovery.kt               jmDNS DeviceDiscovery
  RemoteImpl.kt                             AppleTvRemote object wiring
protocol/src/test/kotlin/dev/atvremote/protocol/
  opack/OpackTest.kt
  tlv8/Tlv8Test.kt
  crypto/*Test.kt
  frame/FrameTest.kt
  goldentrace/GoldenTraceTest.kt            replays captured pyatv fixtures
protocol/src/test/resources/goldentrace/    *.json fixtures (captured)
trace-tools/build.gradle.kts                JVM app module
trace-tools/src/main/kotlin/dev/atvremote/tracetools/
  CaptureGuide.md                           how to capture pyatv traces
  SmokeCli.kt                               discover→pair→verify→send Menu
  CredentialStore.kt                        file-based cred store for CLI
docs/PROTOCOL.md                            living notes on verified behavior
```

---

## Task 1: Multi-module Gradle scaffolding

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`
- Create: `protocol/build.gradle.kts`, `trace-tools/build.gradle.kts`
- Create: `protocol/src/main/kotlin/dev/atvremote/protocol/Api.kt`
- Test: `protocol/src/test/kotlin/dev/atvremote/protocol/ScaffoldTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// protocol/src/test/kotlin/dev/atvremote/protocol/ScaffoldTest.kt
package dev.atvremote.protocol

import kotlin.test.Test
import kotlin.test.assertTrue

class ScaffoldTest {
    @Test fun moduleCompilesAndApiTypesExist() {
        val d = AppleTvDevice("id", "Living Room", "10.0.0.5", 49152, "AppleTV14,1", true)
        assertTrue(d.pairable)
        assertTrue(RemoteButton.Menu.hid == 5)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :protocol:test --tests dev.atvremote.protocol.ScaffoldTest`
Expected: FAIL — Gradle/project not configured (or `AppleTvDevice` unresolved).

- [ ] **Step 3: Create build files**

`settings.gradle.kts`:
```kotlin
rootProject.name = "apple-tv-controller"
include(":protocol", ":trace-tools")
```

`gradle/libs.versions.toml`:
```toml
[versions]
kotlin = "2.0.21"
coroutines = "1.9.0"
bouncycastle = "1.79"
jmdns = "3.5.9"
[libraries]
coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
bouncycastle = { module = "org.bouncycastle:bcprov-jdk18on", version.ref = "bouncycastle" }
jmdns = { module = "org.jmdns:jmdns", version.ref = "jmdns" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
```

`build.gradle.kts`:
```kotlin
plugins { alias(libs.plugins.kotlin.jvm) apply false }
```

`protocol/build.gradle.kts`:
```kotlin
plugins { alias(libs.plugins.kotlin.jvm) }
dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.bouncycastle)
    implementation(libs.jmdns)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}
tasks.test { useJUnitPlatform() }
kotlin { jvmToolchain(17) }
```

`trace-tools/build.gradle.kts`:
```kotlin
plugins { alias(libs.plugins.kotlin.jvm); application }
dependencies { implementation(project(":protocol")); implementation(libs.coroutines.core) }
application { mainClass.set("dev.atvremote.tracetools.SmokeCliKt") }
kotlin { jvmToolchain(17) }
```

- [ ] **Step 4: Create the locked API file**

Create `protocol/src/main/kotlin/dev/atvremote/protocol/Api.kt` containing exactly the types from the "Public API Contract (LOCKED)" section above (data classes, enums, interfaces, `AppleTvRemote` object). For methods not yet implemented, declare them and throw `NotImplementedError()` in stub bodies (e.g. `AppleTvRemote.connect` stub) so the module compiles. `HapCredentials.serialize`/`parse`: implement as base64 of `clientId.size|clientId|...` length-prefixed concatenation now (needed by later tasks).

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :protocol:test --tests dev.atvremote.protocol.ScaffoldTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle/libs.versions.toml protocol trace-tools
git commit -m "build: scaffold :protocol and :trace-tools modules with locked API"
```

---

## Task 2: OPACK encoder

**Files:**
- Create: `protocol/src/main/kotlin/dev/atvremote/protocol/opack/ObjectList.kt`
- Create: `protocol/src/main/kotlin/dev/atvremote/protocol/opack/Opack.kt`
- Test: `protocol/src/test/kotlin/dev/atvremote/protocol/opack/OpackEncodeTest.kt`

OPACK value model: use `Any?` accepting `null, Boolean, Int, Long, Double, String, ByteArray, List<Any?>, Map<String, Any?>, java.util.UUID`.

- [ ] **Step 1: Write the failing test** (vectors derived from `opack.py` rules in the Reference section)

```kotlin
package dev.atvremote.protocol.opack
import kotlin.test.Test
import kotlin.test.assertEquals

private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

class OpackEncodeTest {
    @Test fun scalars() {
        assertEquals("04", hex(Opack.pack(null)))
        assertEquals("01", hex(Opack.pack(true)))
        assertEquals("02", hex(Opack.pack(false)))
        assertEquals("0a", hex(Opack.pack(2)))            // small int 2 -> 2+8
        assertEquals("2f", hex(Opack.pack(39)))           // small int max
        assertEquals("30ff", hex(Opack.pack(255)))        // 1-byte int
        assertEquals("3100010000".substring(0,6), hex(Opack.pack(256)).substring(0,6)) // 0x31 + 2B LE
        assertEquals("40", hex(Opack.pack("")))           // empty short string
        assertEquals("43616263", hex(Opack.pack("abc")))  // 0x43 + 'abc'
        assertEquals("70", hex(Opack.pack(ByteArray(0)))) // empty short data
    }
    @Test fun arrayAndDict() {
        assertEquals("d2010a", hex(Opack.pack(listOf(true, 2))))      // array len2, true, int2
        assertEquals("e14161010a".substring(0,2), hex(Opack.pack(mapOf("a" to listOf(true,2)))).substring(0,2))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :protocol:test --tests dev.atvremote.protocol.opack.OpackEncodeTest`
Expected: FAIL — `Opack` unresolved.

- [ ] **Step 3: Implement encoder**

```kotlin
// ObjectList.kt
package dev.atvremote.protocol.opack
internal class ObjectList {
    private val items = ArrayList<ByteArray>()
    fun indexOf(b: ByteArray): Int = items.indexOfFirst { it.contentEquals(b) }
    fun add(b: ByteArray) { items.add(b) }
}
```

```kotlin
// Opack.kt (encoder portion)
package dev.atvremote.protocol.opack
import java.io.ByteArrayOutputStream
import java.util.UUID

object Opack {
    fun pack(value: Any?): ByteArray {
        val out = ByteArrayOutputStream(); val ol = ObjectList(); packInto(value, out, ol); return out.toByteArray()
    }
    private fun le(v: Long, n: Int) = ByteArray(n) { ((v shr (8 * it)) and 0xFF).toByte() }

    private fun packInto(value: Any?, out: ByteArrayOutputStream, ol: ObjectList) {
        val packed = encode(value, ol)
        if (packed.size > 1) {
            val idx = ol.indexOf(packed)
            if (idx >= 0) { out.write(backref(idx)); return }
            ol.add(packed)
        }
        out.write(packed)
    }
    private fun backref(i: Int): ByteArray = when {
        i < 0x21 -> byteArrayOf((0xA0 + i).toByte())
        i <= 0xFF -> byteArrayOf(0xC1.toByte()) + le(i.toLong(),1)
        i <= 0xFFFF -> byteArrayOf(0xC2.toByte()) + le(i.toLong(),2)
        i <= 0xFFFFFFFFL.toInt() -> byteArrayOf(0xC3.toByte()) + le(i.toLong(),4)
        else -> byteArrayOf(0xC4.toByte()) + le(i.toLong(),8)
    }
    private fun encode(value: Any?, ol: ObjectList): ByteArray {
        val b = ByteArrayOutputStream()
        when (value) {
            null -> b.write(0x04)
            is Boolean -> b.write(if (value) 0x01 else 0x02)
            is Int -> b.write(encode((value.toLong()), ol))
            is Long -> {
                val v = value
                when {
                    v in 0..0x27 -> b.write((v + 8).toInt())
                    v <= 0xFF -> { b.write(0x30); b.write(le(v,1)) }
                    v <= 0xFFFF -> { b.write(0x31); b.write(le(v,2)) }
                    v <= 0xFFFFFFFFL -> { b.write(0x32); b.write(le(v,4)) }
                    else -> { b.write(0x33); b.write(le(v,8)) }
                }
            }
            is Double -> { b.write(0x36); b.write(java.lang.Double.doubleToLongBits(value).let { le(it,8) }) }
            is String -> {
                val s = value.toByteArray(Charsets.UTF_8)
                if (s.size <= 0x20) b.write(0x40 + s.size) else {
                    when { s.size <= 0xFF -> { b.write(0x61); b.write(le(s.size.toLong(),1)) }
                        s.size <= 0xFFFF -> { b.write(0x62); b.write(le(s.size.toLong(),2)) }
                        s.size <= 0xFFFFFF -> { b.write(0x63); b.write(le(s.size.toLong(),3)) }
                        else -> { b.write(0x64); b.write(le(s.size.toLong(),4)) } }
                }
                b.write(s)
            }
            is ByteArray -> {
                if (value.size <= 0x20) b.write(0x70 + value.size) else {
                    when { value.size <= 0xFF -> { b.write(0x91); b.write(le(value.size.toLong(),1)) }
                        value.size <= 0xFFFF -> { b.write(0x92); b.write(le(value.size.toLong(),2)) }
                        value.size <= 0xFFFFFFFFL.toInt() -> { b.write(0x93); b.write(le(value.size.toLong(),4)) }
                        else -> { b.write(0x94); b.write(le(value.size.toLong(),8)) } }
                }
                b.write(value)
            }
            is UUID -> { b.write(0x05); val bb=java.nio.ByteBuffer.allocate(16); bb.putLong(value.mostSignificantBits); bb.putLong(value.leastSignificantBits); b.write(bb.array()) }
            is List<*> -> {
                val n = value.size; b.write(0xD0 + minOf(n, 0xF))
                val sub = ByteArrayOutputStream(); value.forEach { packInto(it, sub, ol) }; b.write(sub.toByteArray())
                if (n >= 0xF) b.write(0x03)
            }
            is Map<*, *> -> {
                val n = value.size; b.write(0xE0 + minOf(n, 0xF))
                val sub = ByteArrayOutputStream()
                value.forEach { (k, v) -> packInto(k, sub, ol); packInto(v, sub, ol) }
                b.write(sub.toByteArray()); if (n >= 0xF) b.write(0x03)
            }
            else -> error("OPACK: unsupported type ${value::class}")
        }
        return b.toByteArray()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :protocol:test --tests dev.atvremote.protocol.opack.OpackEncodeTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add protocol/src/main/kotlin/dev/atvremote/protocol/opack protocol/src/test/kotlin/dev/atvremote/protocol/opack/OpackEncodeTest.kt
git commit -m "feat(opack): byte-exact OPACK encoder ported from pyatv opack.py"
```

---

## Task 3: OPACK decoder + round-trip

**Files:**
- Modify: `protocol/src/main/kotlin/dev/atvremote/protocol/opack/Opack.kt` (add `unpack`)
- Test: `protocol/src/test/kotlin/dev/atvremote/protocol/opack/OpackDecodeTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.atvremote.protocol.opack
import kotlin.test.Test
import kotlin.test.assertEquals
class OpackDecodeTest {
    private fun rt(v: Any?) = Opack.unpack(Opack.pack(v)).first
    @Test fun roundTrips() {
        assertEquals(null, rt(null)); assertEquals(true, rt(true)); assertEquals(2L, rt(2))
        assertEquals(255L, rt(255)); assertEquals(70000L, rt(70000)); assertEquals("hello", rt("hello"))
        assertEquals(listOf(1L, "x", true), rt(listOf(1, "x", true)))
        assertEquals(mapOf("_i" to "_systemInfo", "_t" to 2L), rt(mapOf("_i" to "_systemInfo", "_t" to 2)))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :protocol:test --tests dev.atvremote.protocol.opack.OpackDecodeTest`
Expected: FAIL — `Opack.unpack` unresolved.

- [ ] **Step 3: Implement decoder**

Add to `Opack.kt`: `fun unpack(data: ByteArray): Pair<Any?, ByteArray>` returning `(value, remaining)`. Dispatch on `data[0].toInt() and 0xFF` using the inverse of the encode tag table in the Reference section (small int `0x08..0x2F` → byte-8; int `0x30..0x33` → read `2^(tag&0xF)` bytes LE; string `0x40..0x60` inline / `0x61..0x64` len-prefixed; data `0x70..0x90` / `0x91..0x94`; array `0xD0` / dict `0xE0` with `0x03` terminator when low nibble == 0xF; back-ref `0xA0..0xC0` index → object_list, `0xC1..0xC4` → LE index). Maintain a parallel object_list appended for decoded scalars/strings/bytes/uuid with `len>1` in document order (NOT bool/null/small int/list/dict), mirroring the encoder so back-refs resolve.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :protocol:test --tests dev.atvremote.protocol.opack.OpackDecodeTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add protocol/src/main/kotlin/dev/atvremote/protocol/opack/Opack.kt protocol/src/test/kotlin/dev/atvremote/protocol/opack/OpackDecodeTest.kt
git commit -m "feat(opack): OPACK decoder + round-trip tests"
```

---

## Task 4: HAP TLV8 codec

**Files:**
- Create: `protocol/src/main/kotlin/dev/atvremote/protocol/tlv8/Tlv8.kt`
- Test: `protocol/src/test/kotlin/dev/atvremote/protocol/tlv8/Tlv8Test.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.atvremote.protocol.tlv8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
class Tlv8Test {
    @Test fun writeReadSingle() {
        val enc = Tlv8.write(mapOf(Tlv8.SeqNo to byteArrayOf(1), Tlv8.Method to byteArrayOf(0)))
        val dec = Tlv8.read(enc)
        assertTrue(dec[Tlv8.SeqNo]!!.contentEquals(byteArrayOf(1)))
        assertTrue(dec[Tlv8.Method]!!.contentEquals(byteArrayOf(0)))
    }
    @Test fun fragmentsOver255() {
        val big = ByteArray(600) { it.toByte() }
        val dec = Tlv8.read(Tlv8.write(mapOf(Tlv8.PublicKey to big)))
        assertEquals(600, dec[Tlv8.PublicKey]!!.size)
        assertTrue(dec[Tlv8.PublicKey]!!.contentEquals(big))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :protocol:test --tests dev.atvremote.protocol.tlv8.Tlv8Test`
Expected: FAIL — `Tlv8` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package dev.atvremote.protocol.tlv8
import java.io.ByteArrayOutputStream
object Tlv8 {
    const val Method=0x00; const val Identifier=0x01; const val Salt=0x02; const val PublicKey=0x03
    const val Proof=0x04; const val EncryptedData=0x05; const val SeqNo=0x06; const val Error=0x07
    const val Signature=0x0A; const val Permissions=0x0B; const val Name=0x11; const val Flags=0x13
    fun write(items: Map<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((tag, value) in items) {
            var off = 0
            if (value.isEmpty()) { out.write(tag); out.write(0) }
            while (off < value.size) {
                val len = minOf(255, value.size - off)
                out.write(tag); out.write(len); out.write(value, off, len); off += len
            }
        }
        return out.toByteArray()
    }
    fun read(data: ByteArray): Map<Int, ByteArray> {
        val map = LinkedHashMap<Int, ByteArrayOutputStream>(); var i = 0
        while (i < data.size) {
            val tag = data[i].toInt() and 0xFF; val len = data[i+1].toInt() and 0xFF
            map.getOrPut(tag) { ByteArrayOutputStream() }.write(data, i+2, len); i += 2 + len
        }
        return map.mapValues { it.value.toByteArray() }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :protocol:test --tests dev.atvremote.protocol.tlv8.Tlv8Test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add protocol/src/main/kotlin/dev/atvremote/protocol/tlv8 protocol/src/test/kotlin/dev/atvremote/protocol/tlv8/Tlv8Test.kt
git commit -m "feat(tlv8): HAP TLV8 codec with >255B fragmentation"
```

---

## Task 5: Crypto — Ed25519, X25519, HKDF, ChaCha20-Poly1305

**Files:**
- Create: `protocol/src/main/kotlin/dev/atvremote/protocol/crypto/Curves.kt`
- Create: `protocol/src/main/kotlin/dev/atvremote/protocol/crypto/Hkdf.kt`
- Create: `protocol/src/main/kotlin/dev/atvremote/protocol/crypto/ChaCha.kt`
- Test: `protocol/src/test/kotlin/dev/atvremote/protocol/crypto/CryptoTest.kt`

- [ ] **Step 1: Write the failing test** (RFC 7748/8439/5869 known vectors)

```kotlin
package dev.atvremote.protocol.crypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
private fun h(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
class CryptoTest {
    @Test fun ed25519SignVerifyRoundTrip() {
        val (sk, pk) = Curves.newEd25519()
        val sig = Curves.ed25519Sign(sk, "hello".toByteArray())
        assertTrue(Curves.ed25519Verify(pk, "hello".toByteArray(), sig))
    }
    @Test fun x25519RfcVector() {
        // RFC 7748 §6.1 Alice/Bob shared secret
        val aPriv = h("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bPub  = h("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        assertEquals("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742",
            hex(Curves.x25519(aPriv, bPub)))
    }
    @Test fun hkdfSha512Length32() {
        assertEquals(32, Hkdf.expand("salt", "info", ByteArray(32) { 0x0b }).size)
    }
    @Test fun chacha8ByteNonceRoundTrip() {
        val key = ByteArray(32) { it.toByte() }
        val c = ChaCha(key, key)
        val ct = c.encryptFixed("PS-Msg05".toByteArray(), "secret".toByteArray())
        assertTrue(c.decryptFixed("PS-Msg05".toByteArray(), ct).contentEquals("secret".toByteArray()))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :protocol:test --tests dev.atvremote.protocol.crypto.CryptoTest`
Expected: FAIL — `Curves`/`Hkdf`/`ChaCha` unresolved.

- [ ] **Step 3: Implement (BouncyCastle low-level API)**

```kotlin
// Curves.kt
package dev.atvremote.protocol.crypto
import org.bouncycastle.crypto.params.*
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.SecureRandom
object Curves {
    private val rng = SecureRandom()
    fun newEd25519(): Pair<ByteArray, ByteArray> {
        val seed = ByteArray(32).also { rng.nextBytes(it) }
        val pk = ByteArray(32); Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encode(pk, 0)
        return seed to pk
    }
    fun ed25519PublicFromSeed(seed: ByteArray): ByteArray =
        ByteArray(32).also { Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encode(it, 0) }
    fun ed25519Sign(seed: ByteArray, msg: ByteArray): ByteArray {
        val s = Ed25519Signer(); s.init(true, Ed25519PrivateKeyParameters(seed, 0)); s.update(msg, 0, msg.size); return s.generateSignature()
    }
    fun ed25519Verify(pub: ByteArray, msg: ByteArray, sig: ByteArray): Boolean {
        val v = Ed25519Signer(); v.init(false, Ed25519PublicKeyParameters(pub, 0)); v.update(msg, 0, msg.size); return v.verifySignature(sig)
    }
    fun newX25519(): Pair<ByteArray, ByteArray> {
        val priv = ByteArray(32).also { rng.nextBytes(it) }; X25519.clampPrivateKey(priv)
        val pub = ByteArray(32); X25519.scalarMultBase(priv, 0, pub, 0); return priv to pub
    }
    fun x25519(priv: ByteArray, peerPub: ByteArray): ByteArray =
        ByteArray(32).also { X25519.scalarMult(priv, 0, peerPub, 0, it, 0) }
}
```

```kotlin
// Hkdf.kt
package dev.atvremote.protocol.crypto
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
object Hkdf {
    fun expand(salt: String, info: String, ikm: ByteArray, len: Int = 32): ByteArray {
        val g = HKDFBytesGenerator(SHA512Digest())
        g.init(HKDFParameters(ikm, salt.toByteArray(), info.toByteArray()))
        return ByteArray(len).also { g.generateBytes(it, 0, len) }
    }
}
```

```kotlin
// ChaCha.kt
package dev.atvremote.protocol.crypto
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.*
class ChaCha(private val outKey: ByteArray, private val inKey: ByteArray) {
    private var outCtr = 0L; private var inCtr = 0L
    private fun pad12(n: ByteArray) = ByteArray(12 - n.size) + n
    private fun ctrNonce(c: Long): ByteArray { val b = ByteArray(12); for (i in 0..7) b[i] = ((c shr (8*i)) and 0xFF).toByte(); return b }
    private fun run(enc: Boolean, key: ByteArray, nonce: ByteArray, data: ByteArray, aad: ByteArray?): ByteArray {
        val e = ChaCha20Poly1305(); e.init(enc, ParametersWithIV(KeyParameter(key), nonce))
        if (aad != null) e.processAADBytes(aad, 0, aad.size)
        val out = ByteArray(e.getOutputSize(data.size)); var off = e.processBytes(data, 0, data.size, out, 0); e.doFinal(out, off); return out
    }
    /** Fixed explicit nonce (pair-setup/verify), left zero-padded to 12. */
    fun encryptFixed(nonce: ByteArray, pt: ByteArray) = run(true, outKey, pad12(nonce), pt, null)
    fun decryptFixed(nonce: ByteArray, ct: ByteArray) = run(false, inKey, pad12(nonce), ct, null)
    /** Connection mode: per-direction LE counter nonce, AAD = frame header. */
    fun encryptOut(pt: ByteArray, aad: ByteArray) = run(true, outKey, ctrNonce(outCtr++), pt, aad)
    fun decryptIn(ct: ByteArray, aad: ByteArray) = run(false, inKey, ctrNonce(inCtr++), ct, aad)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :protocol:test --tests dev.atvremote.protocol.crypto.CryptoTest`
Expected: PASS (all four tests).

- [ ] **Step 5: Commit**

```bash
git add protocol/src/main/kotlin/dev/atvremote/protocol/crypto protocol/src/test/kotlin/dev/atvremote/protocol/crypto/CryptoTest.kt
git commit -m "feat(crypto): Ed25519/X25519/HKDF-SHA512/ChaCha20-Poly1305 via BouncyCastle"
```

---

## Task 6: SRP-6a client

**Files:**
- Create: `protocol/src/main/kotlin/dev/atvremote/protocol/crypto/Srp.kt`
- Test: `protocol/src/test/kotlin/dev/atvremote/protocol/crypto/SrpTest.kt`

Implement SRP-6a per `hap_srp.py`: identity `"Pair-Setup"`, password = PIN, RFC 5054 3072-bit group `N`/`g=5`, hash SHA-512. Use BouncyCastle `org.bouncycastle.crypto.agreement.srp.SRP6Client` with `SRP6StandardGroups` (define the 3072-bit group constants from RFC 5054 inline — N hex + g=5).

- [ ] **Step 1: Write the failing test** (client/server self-consistency using BC `SRP6Server`)

```kotlin
package dev.atvremote.protocol.crypto
import kotlin.test.Test
import kotlin.test.assertTrue
class SrpTest {
    @Test fun clientServerAgreeOnSharedKey() {
        // Use BC SRP6Server with the same 3072-bit group/SHA-512 to validate our client.
        val pin = "1234"
        val (clientA, server, verifier) = SrpTestHarness.setup(pin)         // see harness in test
        val clientS = SrpTestHarness.runClient(clientA, server, verifier, pin)
        val serverS = SrpTestHarness.serverKey()
        assertTrue(clientS.contentEquals(serverS))
    }
}
```

(Provide `SrpTestHarness` in the same test file: builds a BC `SRP6Server`, generates a verifier from salt+identity+pin, drives `Srp.step1(pin)`/`Srp.step2(salt,B)` and asserts `Srp.sessionKey` equals server's premaster-derived key. This validates our client against a reference SRP implementation without a device.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :protocol:test --tests dev.atvremote.protocol.crypto.SrpTest`
Expected: FAIL — `Srp` unresolved.

- [ ] **Step 3: Implement**

Create `Srp.kt` with:
- `object Srp3072Group { val N: BigInteger; val g = BigInteger.valueOf(5) }` — `N` = RFC 5054 3072-bit prime (paste full hex constant).
- `class Srp(authPrivateSeed: ByteArray)` — `a` derived from `authPrivateSeed` (hexlify like pyatv). Methods: `fun step1(pin: String)`, `fun step2(salt: ByteArray, serverB: ByteArray): Pair<ByteArray /*A*/, ByteArray /*M1 proof*/>`, `val sessionKey: ByteArray` (SHA-512 of premaster, matching `srptools` `key`), `fun verifyServerProof(m2: ByteArray): Boolean`.
- Use `SRP6Client` (SHA-512 digest, group N/g). Set the client private from `authPrivateSeed` (override `selectPrivateValue` or use the low-level math) so the handshake is deterministic w.r.t. our Ed25519 key, exactly as `hap_srp.py` does.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :protocol:test --tests dev.atvremote.protocol.crypto.SrpTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add protocol/src/main/kotlin/dev/atvremote/protocol/crypto/Srp.kt protocol/src/test/kotlin/dev/atvremote/protocol/crypto/SrpTest.kt
git commit -m "feat(crypto): SRP-6a client (RFC5054 3072-bit, SHA-512) per hap_srp.py"
```

---

## Task 7: Companion frame codec

**Files:**
- Create: `protocol/src/main/kotlin/dev/atvremote/protocol/frame/Frame.kt`
- Test: `protocol/src/test/kotlin/dev/atvremote/protocol/frame/FrameTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :protocol:test --tests dev.atvremote.protocol.frame.FrameTest`
Expected: FAIL — `Frame`/`FrameType` unresolved.

- [ ] **Step 3: Implement**

```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :protocol:test --tests dev.atvremote.protocol.frame.FrameTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add protocol/src/main/kotlin/dev/atvremote/protocol/frame protocol/src/test/kotlin/dev/atvremote/protocol/frame/FrameTest.kt
git commit -m "feat(frame): Companion frame codec (exact FrameType values, BE length, AEAD)"
```

---

## Task 8: CompanionConnection (TCP + read loop)

**Files:**
- Create: `protocol/src/main/kotlin/dev/atvremote/protocol/connection/CompanionConnection.kt`
- Test: `protocol/src/test/kotlin/dev/atvremote/protocol/connection/CompanionConnectionTest.kt`

- [ ] **Step 1: Write the failing test** (loopback server feeding frames)

```kotlin
package dev.atvremote.protocol.connection
import dev.atvremote.protocol.frame.Frame
import dev.atvremote.protocol.frame.FrameType
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
class CompanionConnectionTest {
    @Test fun receivesFramedPayload() = runTest {
        val server = ServerSocket(0)
        val job = launch(Dispatchers.IO) {
            val s = server.accept(); s.getOutputStream().write(Frame.encode(FrameType.U_OPACK, byteArrayOf(9,9), null)); s.getOutputStream().flush()
        }
        val conn = CompanionConnection("127.0.0.1", server.localPort)
        conn.connect()
        val (ft, body) = conn.frames().first { true }   // first frame
        assertEquals(FrameType.U_OPACK, ft); assertEquals(2, body.size)
        conn.close(); job.cancel(); server.close()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :protocol:test --tests dev.atvremote.protocol.connection.CompanionConnectionTest`
Expected: FAIL — `CompanionConnection` unresolved.

- [ ] **Step 3: Implement**

Implement `class CompanionConnection(host: String, port: Int)`:
- `suspend fun connect()` opens a `Socket` on `Dispatchers.IO`.
- A read loop coroutine accumulates bytes into a growing buffer and repeatedly calls `Frame.decode(buffer, cipher)`; on a full frame, drop consumed bytes and emit `(FrameType, ByteArray)` into a `MutableSharedFlow` exposed via `fun frames(): SharedFlow<Pair<FrameType,ByteArray>>`.
- `suspend fun send(type: FrameType, payload: ByteArray)` writes `Frame.encode(...)`.
- `fun enableEncryption(outKey: ByteArray, inKey: ByteArray)` sets `cipher = ChaCha(outKey, inKey)` used by both encode (out) and decode (in) from that point.
- `suspend fun close()` cancels the loop and closes the socket.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :protocol:test --tests dev.atvremote.protocol.connection.CompanionConnectionTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add protocol/src/main/kotlin/dev/atvremote/protocol/connection/CompanionConnection.kt protocol/src/test/kotlin/dev/atvremote/protocol/connection/CompanionConnectionTest.kt
git commit -m "feat(connection): coroutine TCP CompanionConnection with frame read loop"
```

---

## Task 9: CompanionProtocol (XID correlation + dispatch)

**Files:**
- Create: `protocol/src/main/kotlin/dev/atvremote/protocol/connection/CompanionProtocol.kt`
- Test: `protocol/src/test/kotlin/dev/atvremote/protocol/connection/CompanionProtocolTest.kt`

- [ ] **Step 1: Write the failing test** (fake connection returning a crafted OPACK response)

```kotlin
package dev.atvremote.protocol.connection
import dev.atvremote.protocol.opack.Opack
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
class CompanionProtocolTest {
    @Test fun exchangeMatchesByXid() = runTest {
        val fake = FakeConnection { sentOpack ->
            val xid = (Opack.unpack(sentOpack).first as Map<*,*>)["_x"]
            Opack.pack(mapOf("_t" to 3, "_x" to xid, "_c" to mapOf("ok" to true)))
        }
        val proto = CompanionProtocol(fake)
        val resp = proto.exchange("_systemInfo", mapOf("name" to "Pixel"))
        assertEquals(true, (resp["_c"] as Map<*,*>)["ok"])
    }
}
```

(Provide `FakeConnection` in the test: implements the small interface `CompanionProtocol` depends on — a `send` that captures the E_OPACK payload and pushes back a response frame into the same `frames()` flow.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :protocol:test --tests dev.atvremote.protocol.connection.CompanionProtocolTest`
Expected: FAIL — `CompanionProtocol` unresolved.

- [ ] **Step 3: Implement**

`class CompanionProtocol(conn)`:
- `xid` starts at a random Int in `[0, 65535]`, increments per request.
- `suspend fun exchange(name: String, content: Map<String,Any?>): Map<String,Any?>`: builds `{"_i": name, "_t": 2, "_c": content, "_x": xid}`, packs, `conn.send(E_OPACK, ...)`, suspends on a `CompletableDeferred` keyed by that `_x`; the frame collector resolves it when an inbound dict has `_t == 3` and matching `_x`. If response contains `_em`, throw `ProtocolException("Command failed: ${_em}")`.
- `suspend fun sendEvent(name: String, content: Map<String,Any?>)`: `{"_i": name, "_t": 1, "_c": content}` via `conn.send(E_OPACK, ...)` (no wait).
- `val events: SharedFlow<Pair<String, Map<String,Any?>>>` — inbound `_t == 1` → emit `(_i, _c)`.
- `suspend fun sendAuth(type: FrameType, opack: Map<String,Any?>): Map<String,Any?>` for pairing: send raw frame, await next auth frame (PS_Start→PS_Next, PV_Start→PV_Next, else same), unpack and return.
- Default request timeout 5s (throw `TimeoutException`).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :protocol:test --tests dev.atvremote.protocol.connection.CompanionProtocolTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add protocol/src/main/kotlin/dev/atvremote/protocol/connection/CompanionProtocol.kt protocol/src/test/kotlin/dev/atvremote/protocol/connection/CompanionProtocolTest.kt
git commit -m "feat(connection): CompanionProtocol XID correlation, events, auth exchange"
```

---

## Task 10: Capture pyatv golden traces (real tvOS 18 device)

**Files:**
- Create: `trace-tools/src/main/kotlin/dev/atvremote/tracetools/CaptureGuide.md`
- Create: `protocol/src/test/resources/goldentrace/pair-setup.json`
- Create: `protocol/src/test/resources/goldentrace/pair-verify.json`
- Create: `protocol/src/test/resources/goldentrace/hid-menu.json`

This task produces fixtures, not code. Each fixture is JSON: `{ "steps": [ { "dir": "out|in", "frameType": int, "opackHexOrTlv": "...", "decoded": {...} } ] }`.

- [ ] **Step 1: Set up pyatv with frame logging**

```bash
python3 -m venv .venv && . .venv/bin/activate && pip install pyatv
# Enable protocol-level debug logging which prints raw Companion frames:
atvremote scan
```

- [ ] **Step 2: Capture pair-setup + pair-verify**

```bash
# Replace ID with your Apple TV's identifier from `atvremote scan`
atvremote --id <ATV_ID> --protocol companion --debug pair 2>pair.log
# enter the PIN shown on the Apple TV
```
From `pair.log`, extract every `Companion send/recv` line with frame type + hex payload (the `--debug` output includes them). Record the ordered out/in frames and, for OPACK frames, the decoded dict, into `pair-setup.json` and the verify portion into `pair-verify.json`.

- [ ] **Step 3: Capture a single HID Menu press**

```bash
atvremote --id <ATV_ID> --protocol companion --debug menu 2>menu.log
```
Record the post-handshake `_hidC` request frame (expect content `{"_hBtS":1,"_hidC":5}` then `{"_hBtS":2,"_hidC":5}`) into `hid-menu.json`.

- [ ] **Step 4: Sanity-check fixtures**

Open each JSON; confirm frame types match the FrameType enum (pair-setup uses 3/4, verify 5/6, commands 8). Confirm OPACK decoded dicts contain expected keys (`_pd`, `_pwTy`, `_i`, `_t`, `_c`, `_x`).

- [ ] **Step 5: Commit**

```bash
git add protocol/src/test/resources/goldentrace trace-tools/src/main/kotlin/dev/atvremote/tracetools/CaptureGuide.md
git commit -m "test(goldentrace): capture pyatv pair-setup/verify/HID fixtures from tvOS 18"
```

---

## Task 11: Pair-setup procedure (golden-trace validated)

**Files:**
- Create: `protocol/src/main/kotlin/dev/atvremote/protocol/pairing/PairSetup.kt`
- Test: `protocol/src/test/kotlin/dev/atvremote/protocol/goldentrace/PairSetupGoldenTest.kt`

- [ ] **Step 1: Write the failing test** (replay captured M2/M4/M6, assert our M1/M3/M5 bytes equal the fixture)

```kotlin
package dev.atvremote.protocol.goldentrace
import dev.atvremote.protocol.pairing.PairSetup
import kotlin.test.Test
import kotlin.test.assertTrue
class PairSetupGoldenTest {
    @Test fun m1m3m5MatchFixture() {
        val gt = GoldenTrace.load("pair-setup.json")
        // Seed PairSetup with the SAME Ed25519 seed/pairingId recorded in the fixture so output is deterministic.
        val ps = PairSetup(seed = gt.fixedSeed, pairingId = gt.fixedPairingId, pin = gt.pin)
        val m1 = ps.buildM1()
        assertTrue(m1.contentEquals(gt.out(0)))
        ps.consumeM2(gt.inFrame(1))
        val m3 = ps.buildM3()
        assertTrue(m3.contentEquals(gt.out(2)))
        ps.consumeM4(gt.inFrame(3))
        val m5 = ps.buildM5()
        assertTrue(m5.contentEquals(gt.out(4)))
        val creds = ps.consumeM6(gt.inFrame(5))
        assertTrue(creds.atvLtpk.size == 32)
    }
}
```

(Provide `GoldenTrace` test helper: loads JSON, exposes `out(i)`/`inFrame(i)` as raw OPACK payload bytes, plus `fixedSeed`/`fixedPairingId`/`pin` — for capture, run pyatv once with a fixed seed by setting `PYATV` cred or, simpler, record the seed pyatv used by adding it to the fixture during Task 10. If pyatv's seed is not extractable, assert structural equality of decoded TLV/OPACK instead of raw bytes — document which mode the fixture uses.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :protocol:test --tests dev.atvremote.protocol.goldentrace.PairSetupGoldenTest`
Expected: FAIL — `PairSetup` unresolved.

- [ ] **Step 3: Implement** (port `companion/auth.py` `CompanionPairSetupProcedure` + `hap_srp.py` step1–4)

`class PairSetup(seed, pairingId, pin)`:
- `buildM1()`: `Opack.pack(mapOf("_pd" to Tlv8.write(mapOf(Tlv8.Method to byteArrayOf(0), Tlv8.SeqNo to byteArrayOf(1))), "_pwTy" to 1))`.
- `consumeM2(payload)`: unpack OPACK, read `_pd` TLV → `Salt`, `PublicKey(B)`.
- `buildM3()`: `Srp(seed)`; `step1(pin)`; `step2(salt, B)` → A, M1proof; `Opack.pack({"_pd": Tlv8.write({SeqNo:3, PublicKey:A, Proof:M1proof}), "_pwTy":1})`.
- `consumeM4(payload)`: read server `Proof`; `srp.verifyServerProof(proof)` else fail.
- `buildM5()`: `iosDeviceX = Hkdf.expand("Pair-Setup-Controller-Sign-Salt","Pair-Setup-Controller-Sign-Info", srp.sessionKey)`; `sessionKey = Hkdf.expand("Pair-Setup-Encrypt-Salt","Pair-Setup-Encrypt-Info", srp.sessionKey)`; `authPub = Curves.ed25519PublicFromSeed(seed)`; `deviceInfo = iosDeviceX + pairingId + authPub`; `sig = Curves.ed25519Sign(seed, deviceInfo)`; inner TLV `{Identifier:pairingId, PublicKey:authPub, Signature:sig}`; `enc = ChaCha(sessionKey, sessionKey).encryptFixed("PS-Msg05".toByteArray(), innerTlv)`; `Opack.pack({"_pd": Tlv8.write({SeqNo:5, EncryptedData:enc}), "_pwTy":1})`.
- `consumeM6(payload)`: decrypt `EncryptedData` with `"PS-Msg06"`, read TLV `{Identifier(atvId), PublicKey(atvLtpk), Signature}`; return `HapCredentials(clientId=pairingId, clientLtsk=seed, clientLtpk=authPub, atvId=atvId, atvLtpk=atvLtpk)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :protocol:test --tests dev.atvremote.protocol.goldentrace.PairSetupGoldenTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add protocol/src/main/kotlin/dev/atvremote/protocol/pairing/PairSetup.kt protocol/src/test/kotlin/dev/atvremote/protocol/goldentrace/PairSetupGoldenTest.kt
git commit -m "feat(pairing): pair-setup M1–M6 validated against pyatv golden trace"
```

---

## Task 12: Pair-verify procedure + connection keys

**Files:**
- Create: `protocol/src/main/kotlin/dev/atvremote/protocol/pairing/PairVerify.kt`
- Test: `protocol/src/test/kotlin/dev/atvremote/protocol/goldentrace/PairVerifyGoldenTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.atvremote.protocol.goldentrace
import dev.atvremote.protocol.pairing.PairVerify
import kotlin.test.Test
import kotlin.test.assertTrue
class PairVerifyGoldenTest {
    @Test fun pvMatchesFixtureAndDerivesKeys() {
        val gt = GoldenTrace.load("pair-verify.json")
        val pv = PairVerify(credentials = gt.credentials, x25519Priv = gt.fixedX25519Priv, x25519Pub = gt.fixedX25519Pub)
        assertTrue(pv.buildM1().contentEquals(gt.out(0)))
        pv.consumeM2(gt.inFrame(1))
        assertTrue(pv.buildM3().contentEquals(gt.out(2)))
        val (outKey, inKey) = pv.connectionKeys()
        assertTrue(outKey.size == 32 && inKey.size == 32)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :protocol:test --tests dev.atvremote.protocol.goldentrace.PairVerifyGoldenTest`
Expected: FAIL — `PairVerify` unresolved.

- [ ] **Step 3: Implement** (port `CompanionPairVerifyProcedure` + `verify1`/`verify2`)

`class PairVerify(credentials, x25519Priv, x25519Pub)`:
- `buildM1()`: `Opack.pack({"_pd": Tlv8.write({SeqNo:1, PublicKey:x25519Pub}), "_auTy":4})`.
- `consumeM2(payload)`: read `PublicKey`(serverPub), `EncryptedData`; `shared = Curves.x25519(x25519Priv, serverPub)`; `sessKey = Hkdf.expand("Pair-Verify-Encrypt-Salt","Pair-Verify-Encrypt-Info", shared)`; `chacha = ChaCha(sessKey,sessKey)`; decrypt with `"PV-Msg02"` → TLV `{Identifier, Signature}`; assert `Identifier == credentials.atvId`; verify Ed25519 sig over `serverPub + identifier + x25519Pub` with `credentials.atvLtpk` else fail.
- `buildM3()`: `deviceInfo = x25519Pub + credentials.clientId + serverPub`; `sig = Curves.ed25519Sign(credentials.clientLtsk, deviceInfo)`; inner `Tlv8.write({Identifier:credentials.clientId, Signature:sig})`; `enc = chacha.encryptFixed("PV-Msg03".toByteArray(), inner)`; `Opack.pack({"_pd": Tlv8.write({SeqNo:3, EncryptedData:enc})})`.
- `connectionKeys()`: `out = Hkdf.expand("", "ClientEncrypt-main", shared)`; `in = Hkdf.expand("", "ServerEncrypt-main", shared)`; return `(out, in)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :protocol:test --tests dev.atvremote.protocol.goldentrace.PairVerifyGoldenTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add protocol/src/main/kotlin/dev/atvremote/protocol/pairing/PairVerify.kt protocol/src/test/kotlin/dev/atvremote/protocol/goldentrace/PairVerifyGoldenTest.kt
git commit -m "feat(pairing): pair-verify M1–M3 + ClientEncrypt/ServerEncrypt connection keys"
```

---

## Task 13: Session handshake

**Files:**
- Create: `protocol/src/main/kotlin/dev/atvremote/protocol/session/SessionHandshake.kt`
- Test: `protocol/src/test/kotlin/dev/atvremote/protocol/session/SessionHandshakeTest.kt`

- [ ] **Step 1: Write the failing test** (fake protocol asserts the exact command order/keys)

```kotlin
package dev.atvremote.protocol.session
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
class SessionHandshakeTest {
    @Test fun sendsCommandsInOrder() = runTest {
        val rec = mutableListOf<String>()
        val proto = RecordingProtocol(rec) { name -> mapOf("_c" to mapOf("_sid" to 42L)) }
        SessionHandshake(proto, deviceId = "dev", clientId = "cli", name = "Pixel", model = "Pixel 8").run()
        assertEquals(listOf("_systemInfo", "_touchStart", "_sessionStart", "_tiStart"), rec.take(4))
    }
}
```

(Provide `RecordingProtocol` test double implementing the subset of `CompanionProtocol` used here.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :protocol:test --tests dev.atvremote.protocol.session.SessionHandshakeTest`
Expected: FAIL — `SessionHandshake` unresolved.

- [ ] **Step 3: Implement** (port `api.py CompanionAPI.connect`)

`class SessionHandshake(proto, deviceId, clientId, name, model)` with `suspend fun run()`:
1. `proto.exchange("_systemInfo", mapOf("_bf" to 0,"_cf" to 512,"_clFl" to 128,"_i" to "<rpId>","_idsID" to clientId,"_pubID" to deviceId,"_sf" to 256,"_sv" to "170.18","model" to model,"name" to name))`
2. `proto.exchange("_touchStart", mapOf("_height" to 1000.0,"_tFl" to 0,"_width" to 1000.0))`
3. `proto.exchange("_sessionStart", mapOf("_srvT" to "com.apple.tvremoteservices","_sid" to localSid))` where `localSid = Random.nextLong(0, 1L shl 32)`; combine `sid = (remoteSid shl 32) or localSid`.
4. `proto.exchange("_tiStart", emptyMap())`
5. `proto.sendEvent("_interest", mapOf("_regEvents" to listOf("_iMC")))`
- Expose `val sid: Long`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :protocol:test --tests dev.atvremote.protocol.session.SessionHandshakeTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add protocol/src/main/kotlin/dev/atvremote/protocol/session/SessionHandshake.kt protocol/src/test/kotlin/dev/atvremote/protocol/session/SessionHandshakeTest.kt
git commit -m "feat(session): post-verify handshake (_systemInfo/_touchStart/_sessionStart/_tiStart)"
```

---

## Task 14: Wire AppleTvRemote.connect + CompanionSession.button

**Files:**
- Create: `protocol/src/main/kotlin/dev/atvremote/protocol/session/CompanionSessionImpl.kt`
- Modify: `protocol/src/main/kotlin/dev/atvremote/protocol/RemoteImpl.kt` (create) and `Api.kt` `AppleTvRemote.connect` stub → real
- Test: `protocol/src/test/kotlin/dev/atvremote/protocol/session/ButtonTest.kt`

- [ ] **Step 1: Write the failing test** (fake protocol asserts the `_hidC` content)

```kotlin
package dev.atvremote.protocol.session
import dev.atvremote.protocol.RemoteButton
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
class ButtonTest {
    @Test fun menuSendsHidC() = runTest {
        val sent = mutableListOf<Pair<String, Map<String,Any?>>>()
        val session = CompanionSessionImpl(RecordingProtocol2(sent))
        session.button(RemoteButton.Menu, down = true)
        assertEquals("_hidC", sent.last().first)
        assertEquals(1, sent.last().second["_hBtS"]); assertEquals(5, sent.last().second["_hidC"])
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :protocol:test --tests dev.atvremote.protocol.session.ButtonTest`
Expected: FAIL — `CompanionSessionImpl` unresolved.

- [ ] **Step 3: Implement**

- `CompanionSessionImpl(proto)`: `override suspend fun button(button, down) { proto.exchange("_hidC", mapOf("_hBtS" to (if (down) 1 else 2), "_hidC" to button.hid)) }`; `override suspend fun close()` → `_sessionStop` then `conn.close()`.
- `RemoteImpl.kt`: implement `AppleTvRemote.connect(device, credentials)`: create `CompanionConnection`, connect, run `PairVerify` over auth frames, `conn.enableEncryption(outKey, inKey)`, run `SessionHandshake`, return `CompanionSessionImpl`. Replace the `connect` stub in `Api.kt` to delegate here.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :protocol:test --tests dev.atvremote.protocol.session.ButtonTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add protocol/src/main/kotlin/dev/atvremote/protocol/session/CompanionSessionImpl.kt protocol/src/main/kotlin/dev/atvremote/protocol/RemoteImpl.kt protocol/src/main/kotlin/dev/atvremote/protocol/Api.kt protocol/src/test/kotlin/dev/atvremote/protocol/session/ButtonTest.kt
git commit -m "feat(session): AppleTvRemote.connect + CompanionSession.button"
```

---

## Task 15: mDNS discovery (jmDNS)

**Files:**
- Create: `protocol/src/main/kotlin/dev/atvremote/protocol/discovery/JmdnsDiscovery.kt`
- Modify: `RemoteImpl.kt` (`AppleTvRemote.discovery()` → real)
- Test: `protocol/src/test/kotlin/dev/atvremote/protocol/discovery/DiscoveryParseTest.kt`

- [ ] **Step 1: Write the failing test** (parse logic isolated from network)

```kotlin
package dev.atvremote.protocol.discovery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
class DiscoveryParseTest {
    @Test fun parsesRpflAndModel() {
        val d = JmdnsDiscovery.toDevice(
            name = "Living Room", host = "10.0.0.5", port = 49152,
            txt = mapOf("rpmd" to "AppleTV14,1", "rpfl" to "0x4000"))
        assertEquals("AppleTV14,1", d.model); assertTrue(d.pairable)
        val disabled = JmdnsDiscovery.toDevice("X","10.0.0.6",1, mapOf("rpfl" to "0x4"))
        assertTrue(!disabled.pairable)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :protocol:test --tests dev.atvremote.protocol.discovery.DiscoveryParseTest`
Expected: FAIL — `JmdnsDiscovery` unresolved.

- [ ] **Step 3: Implement**

- `JmdnsDiscovery.toDevice(name, host, port, txt)`: `rpfl = Integer.parseInt(txt["rpfl"]?.removePrefix("0x") ?: "0", 16)`; `pairable = (rpfl and 0x4000) != 0 && (rpfl and 0x04) == 0`; `model = txt["rpmd"]`; `id = "$name@$host:$port"`.
- `class JmdnsDiscovery : DeviceDiscovery` — `devices()` is a `callbackFlow` that creates `JmDNS.create(...)`, adds a `ServiceListener` for `_companion-link._tcp.local.`, resolves services, maps via `toDevice`, emits the current list; closes JmDNS on cancel.
- Wire `AppleTvRemote.discovery()` to return `JmdnsDiscovery()`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :protocol:test --tests dev.atvremote.protocol.discovery.DiscoveryParseTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add protocol/src/main/kotlin/dev/atvremote/protocol/discovery/JmdnsDiscovery.kt protocol/src/main/kotlin/dev/atvremote/protocol/RemoteImpl.kt protocol/src/test/kotlin/dev/atvremote/protocol/discovery/DiscoveryParseTest.kt
git commit -m "feat(discovery): jmDNS _companion-link discovery with rpfl/rpmd parsing"
```

---

## Task 16: Pairing handle wiring (PairSetup over the live connection)

**Files:**
- Modify: `protocol/src/main/kotlin/dev/atvremote/protocol/RemoteImpl.kt` (`AppleTvRemote.pair`)
- Create: `protocol/src/main/kotlin/dev/atvremote/protocol/pairing/PairingHandleImpl.kt`
- Test: `protocol/src/test/kotlin/dev/atvremote/protocol/pairing/PairingHandleTest.kt`

- [ ] **Step 1: Write the failing test** (state transitions with a fake connection scripted from the golden trace)

```kotlin
package dev.atvremote.protocol.pairing
import dev.atvremote.protocol.PairingState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
class PairingHandleTest {
    @Test fun awaitingPinThenCompleted() = runTest {
        val handle = PairingHandleImpl(ScriptedConnection.fromGoldenTrace("pair-setup.json"))
        assertTrue(handle.state.value is PairingState.AwaitingPin)
        handle.submitPin("1234")
        assertTrue(handle.state.value is PairingState.Completed)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :protocol:test --tests dev.atvremote.protocol.pairing.PairingHandleTest`
Expected: FAIL — `PairingHandleImpl` unresolved.

- [ ] **Step 3: Implement**

`PairingHandleImpl(conn)`: opens connection, sends M1 immediately, sets `state = AwaitingPin`. `submitPin(pin)` drives M3/M5 using `PairSetup`, consumes M2/M4/M6 from the auth flow, on success sets `Completed(credentials)`, on `verifyServerProof` failure or timeout sets `Failed(reason)` and does not persist partial creds. `cancel()` closes the connection. Wire `AppleTvRemote.pair(device)` to construct a real `CompanionConnection` and return `PairingHandleImpl`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :protocol:test --tests dev.atvremote.protocol.pairing.PairingHandleTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add protocol/src/main/kotlin/dev/atvremote/protocol/pairing/PairingHandleImpl.kt protocol/src/main/kotlin/dev/atvremote/protocol/RemoteImpl.kt protocol/src/test/kotlin/dev/atvremote/protocol/pairing/PairingHandleTest.kt
git commit -m "feat(pairing): PairingHandle state machine + AppleTvRemote.pair wiring"
```

---

## Task 17: CLI smoke test on the real device

**Files:**
- Create: `trace-tools/src/main/kotlin/dev/atvremote/tracetools/CredentialStore.kt`
- Create: `trace-tools/src/main/kotlin/dev/atvremote/tracetools/SmokeCli.kt`
- Test: manual (documented expected output)

- [ ] **Step 1: Implement CredentialStore**

`CredentialStore(file)`: `save(deviceId, HapCredentials)` writes `deviceId=base64` lines; `load(deviceId): HapCredentials?` parses. Plain file (CLI only; Android Keystore is Plan 3).

- [ ] **Step 2: Implement SmokeCli**

`main()`: `discovery().devices().first()` → print devices; arg `pair <id>` → run `pair()`, print "Enter PIN shown on TV:", read stdin, `submitPin`, save creds; arg `menu <id>` → `connect()` with stored creds, `button(Menu, true)`, `button(Menu, false)`, print "OK", `close()`.

- [ ] **Step 3: Pair against the real Apple TV**

Run: `./gradlew :trace-tools:run --args "pair <ATV_ID>"`
Expected: prints discovered device, prompts for PIN, after entering the PIN shown on the Apple TV prints `Paired: <id>` and writes credentials file. (This validates Tasks 8–16 end-to-end against tvOS 18.)

- [ ] **Step 4: Send Menu against the real Apple TV**

Run: `./gradlew :trace-tools:run --args "menu <ATV_ID>"`
Expected: prints `OK`; the Apple TV UI visibly responds to a Menu press. (Validates pair-verify + encrypted session + HID end-to-end.)

- [ ] **Step 5: Commit**

```bash
git add trace-tools/src/main/kotlin/dev/atvremote/tracetools/CredentialStore.kt trace-tools/src/main/kotlin/dev/atvremote/tracetools/SmokeCli.kt
git commit -m "feat(trace-tools): CLI smoke test (discover/pair/connect/menu) on real device"
```

---

## Self-Review

**1. Spec coverage** (spec §2 v1 scope vs this plan): discovery ✅ T15; HAP/SRP pairing + persist + verify ✅ T6,T11,T12,T16,T17; encrypted session ✅ T7,T8,T12,T14; one HID command (foundation for full set) ✅ T14. Now Playing / Siri correctly **excluded** (spec §2 YAGNI). Full command set (touch/keyboard/apps/power/volume), reconnection/Doze, Keystore storage, UI, golden-trace harness generalization → deferred to **Plan 2 / Plan 3** by design (this plan is the foundation vertical slice per spec §3 risk-first sequencing).

**2. Placeholder scan:** No "TBD/TODO/implement later". Device-dependent wire bytes are handled by explicit capture (T10) + golden-trace assertions (T11,T12) rather than fabricated — the only honest approach for an undocumented protocol; the porting source (pyatv file + exact constants) is named in every such task.

**3. Type consistency:** `AppleTvDevice`, `HapCredentials`, `RemoteButton`, `CompanionSession`, `PairingHandle`, `AppleTvRemote`, `DeviceDiscovery` used identically across T1/T14/T15/T16/T17 and match the LOCKED contract. Internal types `Opack`, `Tlv8`, `Curves`, `Hkdf`, `ChaCha`, `Srp`, `Frame`/`FrameType`, `CompanionConnection`, `CompanionProtocol`, `PairSetup`, `PairVerify`, `SessionHandshake` consistent across tasks. `ChaCha` method names (`encryptFixed`/`decryptFixed`/`encryptOut`/`decryptIn`) consistent T5↔T7↔T11↔T12.

**Open risk flagged for execution:** golden-trace raw-byte equality requires pyatv to use a fixed Ed25519/X25519 seed; if pyatv's ephemeral keys aren't extractable from `--debug`, T11/T12 fall back to structural (decoded TLV/OPACK) equality + the live-device CLI (T17) as the authoritative end-to-end check. The plan documents both modes.
