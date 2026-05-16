package dev.atvremote.app.data

import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * TEST-ONLY reconciliation, NOT a production change.
 *
 * Robolectric 4.13's shadows-framework ships no `AndroidKeyStore` JCA provider,
 * so the verbatim production `KeyStore.getInstance("AndroidKeyStore")` /
 * `KeyGenerator.getInstance(AES, "AndroidKeyStore")` in [KeystoreCipher] throw
 * `KeyStoreException: AndroidKeyStore not found` / `NoSuchAlgorithmException`
 * under the Robolectric sandbox (this exact friction is called out in the
 * Task-4 brief). Real devices/emulators DO provide it.
 *
 * This installs a software JCA provider literally named "AndroidKeyStore" that
 * backs the same two SPIs with standard JDK AES, so the UNCHANGED production
 * crypto path (non-exportable-key intent, AES/GCM/NoPadding seal/unseal,
 * iv‖ct framing, base64) is exercised end-to-end. Keys live in a process-wide
 * map so a key generated via `KeyGenerator` is later visible via
 * `ks.getEntry(alias)` exactly as the real AndroidKeyStore behaves.
 *
 * Production [KeystoreCipher]/[CredentialStore] are byte-for-byte the plan's
 * verbatim source — only the test environment is reconciled.
 */
internal object AndroidKeyStoreTestProvider {
    private const val NAME = "AndroidKeyStore"

    fun install() {
        if (Security.getProvider(NAME) != null) return
        Security.addProvider(SoftAndroidKeyStoreProvider())
    }

    /** Clears generated keys so each test starts from a pristine keystore. */
    fun reset() = AndroidKeyStoreTestKeys.keys.clear()

    /**
     * Removes the provider from the JVM's global [Security] registry AND clears
     * the key map.  Call from `@AfterTest` so the provider is not resident
     * between test methods / test classes — a later keystore-touching test
     * would otherwise silently get the software stand-in instead of failing
     * fast if its environment is misconfigured.
     */
    fun uninstall() {
        AndroidKeyStoreTestKeys.keys.clear()
        Security.removeProvider(NAME)
    }
}

/** Process-wide key registry shared by the SPI classes (mirrors a keystore). */
internal object AndroidKeyStoreTestKeys {
    val keys = ConcurrentHashMap<String, SecretKey>()
}

@Suppress("DEPRECATION")
private class SoftAndroidKeyStoreProvider :
    Provider("AndroidKeyStore", 1.0, "Test-only software AndroidKeyStore") {
    init {
        // Production calls KeyStore.getInstance("AndroidKeyStore") and
        // KeyGenerator.getInstance("AES", "AndroidKeyStore") -> the JCA
        // resolves "KeyStore.AndroidKeyStore" and "KeyGenerator.AES" here and
        // reflectively instantiates these public no-arg SPI classes.
        put("KeyStore.AndroidKeyStore", SoftKeyStoreSpi::class.java.name)
        put("KeyGenerator.AES", SoftAesKeyGeneratorSpi::class.java.name)
    }
}

/** Public no-arg so the JCA framework can reflectively instantiate it. */
class SoftAesKeyGeneratorSpi : KeyGeneratorSpi() {
    private var keySize = 256
    private var alias: String? = null

    override fun engineInit(random: SecureRandom?) {}

    override fun engineInit(
        params: java.security.spec.AlgorithmParameterSpec?,
        random: SecureRandom?,
    ) {
        // android.security.keystore.KeyGenParameterSpec is a real framework
        // class under Robolectric; read its alias + key size via the public
        // getters so the generated key auto-registers under its alias exactly
        // like the real AndroidKeyStore.
        params?.let { p ->
            runCatching {
                alias = p.javaClass.getMethod("getKeystoreAlias").invoke(p) as? String
            }
            runCatching {
                val s = p.javaClass.getMethod("getKeySize").invoke(p) as? Int
                if (s != null && s > 0) keySize = s
            }
        }
    }

    override fun engineInit(keySize: Int, random: SecureRandom?) {
        this.keySize = keySize
    }

    override fun engineGenerateKey(): SecretKey {
        val raw = ByteArray(keySize / 8)
        SecureRandom().nextBytes(raw)
        val key = SecretKeySpec(raw, "AES")
        alias?.let { AndroidKeyStoreTestKeys.keys[it] = key }
        return key
    }
}

/** Public no-arg so the JCA framework can reflectively instantiate it. */
class SoftKeyStoreSpi : KeyStoreSpi() {
    private val keys get() = AndroidKeyStoreTestKeys.keys

    override fun engineLoad(stream: java.io.InputStream?, password: CharArray?) {}
    override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]
    override fun engineContainsAlias(alias: String): Boolean = keys.containsKey(alias)
    override fun engineGetCertificate(alias: String): Certificate? = null
    override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
    override fun engineGetCreationDate(alias: String): Date = Date()
    override fun engineIsKeyEntry(alias: String): Boolean = keys.containsKey(alias)
    override fun engineIsCertificateEntry(alias: String): Boolean = false
    override fun engineGetCertificateAlias(cert: Certificate?): String? = null
    override fun engineSize(): Int = keys.size
    override fun engineAliases(): Enumeration<String> =
        Collections.enumeration(keys.keys.toList())

    override fun engineSetKeyEntry(
        alias: String,
        key: Key?,
        password: CharArray?,
        chain: Array<out Certificate>?,
    ) {
        if (key is SecretKey) keys[alias] = key
    }

    override fun engineSetKeyEntry(
        alias: String,
        key: ByteArray?,
        chain: Array<out Certificate>?,
    ): Unit = throw KeyStoreException("unsupported in test provider")

    override fun engineSetCertificateEntry(alias: String?, cert: Certificate?): Unit =
        throw KeyStoreException("unsupported in test provider")

    override fun engineDeleteEntry(alias: String) {
        keys.remove(alias)
    }

    override fun engineStore(stream: java.io.OutputStream?, password: CharArray?) {}

    override fun engineGetEntry(
        alias: String,
        protParam: KeyStore.ProtectionParameter?,
    ): KeyStore.Entry? =
        keys[alias]?.let { KeyStore.SecretKeyEntry(it) }

    override fun engineEntryInstanceOf(
        alias: String,
        entryClass: Class<out KeyStore.Entry>,
    ): Boolean =
        keys.containsKey(alias) &&
            entryClass.isAssignableFrom(KeyStore.SecretKeyEntry::class.java)
}
