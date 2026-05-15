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
        // generatePrivateKey both fills and clamps the key per RFC 7748
        val priv = ByteArray(32).also { X25519.generatePrivateKey(rng, it) }
        val pub = ByteArray(32); X25519.scalarMultBase(priv, 0, pub, 0); return priv to pub
    }
    fun x25519(priv: ByteArray, peerPub: ByteArray): ByteArray =
        ByteArray(32).also { X25519.scalarMult(priv, 0, peerPub, 0, it, 0) }
    /** X25519 public key for a (already clamped) private scalar: priv·basepoint. */
    fun x25519Base(priv: ByteArray): ByteArray =
        ByteArray(32).also { X25519.scalarMultBase(priv, 0, it, 0) }
}
