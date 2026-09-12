package com.neurone.myblocker.tls

import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Per-device certificate authority used to terminate HTTPS for browsers in deep-clean
 * mode, so EasyList hiding rules can be injected into pages. The CA private key never
 * leaves the app's private storage; the user installs only the public certificate.
 * Leaf certificates are minted per host name on demand and cached in memory.
 *
 * Certificates are DER-encoded by [Der] and signed with SHA256withRSA; the JVM's own
 * X.509 parser validates the output (see CertAuthorityTest).
 */
class CertAuthority private constructor(
    private val caKey: PrivateKey,
    val caCert: X509Certificate,
    private val leafKeys: KeyPair,
) {
    class Minted(val cert: X509Certificate, val chain: Array<X509Certificate>, val sslContext: SSLContext)

    private val cache = object : LinkedHashMap<String, Minted>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Minted>?): Boolean = size > 256
    }

    /** Leaf certificate plus a ready TLS server context for [host]. */
    @Synchronized
    fun forHost(host: String): Minted {
        cache[host]?.let { return it }
        val cert = mintLeaf(host)
        val chain = arrayOf(cert, caCert)
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("leaf", leafKeys.private, CharArray(0), chain)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, CharArray(0))
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, null)
        val m = Minted(cert, chain, ctx)
        cache[host] = m
        return m
    }

    private fun mintLeaf(host: String): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 3600 * 1000)
        val notAfter = Date(now + 365L * 24 * 3600 * 1000)
        val isIp = host.matches(Regex("^[0-9.]+$")) || host.contains(':')
        val san = if (isIp) Der.implicit(7, java.net.InetAddress.getByName(host).address) else Der.implicit(2, host.toByteArray(Charsets.US_ASCII))
        val extensions = Der.concat(
            extension(OID_BASIC_CONSTRAINTS, true, Der.sequence()),
            extension(OID_KEY_USAGE, true, keyUsage(0xA0)), // digitalSignature | keyEncipherment
            extension(OID_EXT_KEY_USAGE, false, Der.sequence(Der.oid(OID_SERVER_AUTH))),
            extension(OID_SUBJECT_ALT_NAME, false, Der.sequence(san)),
            extension(OID_AUTHORITY_KEY_ID, false, Der.sequence(Der.implicit(0, keyId(caCert.publicKey.encoded)))),
            extension(OID_SUBJECT_KEY_ID, false, Der.octetString(keyId(leafKeys.public.encoded))),
        )
        val serial = BigInteger(1, sha256(host.toByteArray()).copyOf(16)).or(BigInteger.ONE)
        val tbs = tbsCertificate(
            serial = serial,
            issuer = caCert.subjectX500Principal.encoded,
            subject = Der.name(OID_CN to host.take(60)),
            notBefore = notBefore,
            notAfter = notAfter,
            spki = leafKeys.public.encoded,
            extensions = extensions,
        )
        return parse(sign(tbs, caKey))
    }

    companion object {
        private const val OID_CN = "2.5.4.3"
        private const val OID_O = "2.5.4.10"
        private const val OID_SHA256_RSA = "1.2.840.113549.1.1.11"
        private const val OID_BASIC_CONSTRAINTS = "2.5.29.19"
        private const val OID_KEY_USAGE = "2.5.29.15"
        private const val OID_EXT_KEY_USAGE = "2.5.29.37"
        private const val OID_SUBJECT_ALT_NAME = "2.5.29.17"
        private const val OID_SUBJECT_KEY_ID = "2.5.29.14"
        private const val OID_AUTHORITY_KEY_ID = "2.5.29.35"
        private const val OID_SERVER_AUTH = "1.3.6.1.5.5.7.3.1"

        /** Loads the CA from [dir] or creates a new one (RSA 2048, valid 10 years). */
        fun load(dir: File): CertAuthority {
            dir.mkdirs()
            val keyFile = File(dir, "ca.key")
            val certFile = File(dir, "ca.crt")
            val leafKeyFile = File(dir, "leaf.key")
            val kf = KeyFactory.getInstance("RSA")
            val caKey: PrivateKey
            val caCert: X509Certificate
            if (keyFile.exists() && certFile.exists()) {
                caKey = kf.generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                caCert = parse(certFile.readBytes())
            } else {
                val pair = generateKeyPair()
                val suffix = BigInteger(1, SecureRandom().generateSeed(4)).toString(36).uppercase()
                val now = System.currentTimeMillis()
                val extensions = Der.concat(
                    extension(OID_BASIC_CONSTRAINTS, true, Der.sequence(Der.boolean(true))),
                    extension(OID_KEY_USAGE, true, keyUsage(0x06)), // keyCertSign | cRLSign
                    extension(OID_SUBJECT_KEY_ID, false, Der.octetString(keyId(pair.public.encoded))),
                )
                val name = Der.name(OID_CN to "Adbrella Local CA $suffix", OID_O to "Adbrella")
                val tbs = tbsCertificate(
                    serial = BigInteger(63, SecureRandom()).or(BigInteger.ONE),
                    issuer = name,
                    subject = name,
                    notBefore = Date(now - 24L * 3600 * 1000),
                    notAfter = Date(now + 3650L * 24 * 3600 * 1000),
                    spki = pair.public.encoded,
                    extensions = extensions,
                )
                val der = sign(tbs, pair.private)
                caCert = parse(der)
                caKey = pair.private
                keyFile.writeBytes(pair.private.encoded)
                certFile.writeBytes(der)
            }
            val leafKeys: KeyPair = if (leafKeyFile.exists()) {
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(leafKeyFile.readBytes())) as RSAPrivateCrtKey
                KeyPair(kf.generatePublic(RSAPublicKeySpec(priv.modulus, priv.publicExponent)), priv)
            } else {
                generateKeyPair().also { leafKeyFile.writeBytes(it.private.encoded) }
            }
            return CertAuthority(caKey, caCert, leafKeys)
        }

        fun exists(dir: File): Boolean = File(dir, "ca.crt").exists()

        /** PEM form of a certificate, the format Android's certificate installer accepts. */
        fun pem(cert: X509Certificate): String {
            val b64 = java.util.Base64.getEncoder().encodeToString(cert.encoded)
            val sb = StringBuilder("-----BEGIN CERTIFICATE-----\n")
            var i = 0
            while (i < b64.length) {
                sb.append(b64, i, minOf(i + 64, b64.length)).append('\n')
                i += 64
            }
            sb.append("-----END CERTIFICATE-----\n")
            return sb.toString()
        }

        fun fingerprint(cert: X509Certificate): String = sha256(cert.encoded).joinToString(":") { "%02X".format(it) }

        private fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        private fun tbsCertificate(
            serial: BigInteger, issuer: ByteArray, subject: ByteArray, notBefore: Date, notAfter: Date, spki: ByteArray, extensions: ByteArray,
        ): ByteArray = Der.sequence(
            Der.explicit(0, Der.integer(2)), // version 3
            Der.integer(serial),
            algorithmId(),
            issuer,
            Der.sequence(Der.time(notBefore), Der.time(notAfter)),
            subject,
            spki,
            Der.explicit(3, Der.sequence(extensions)),
        )

        private fun sign(tbs: ByteArray, key: PrivateKey): ByteArray {
            val sig = Signature.getInstance("SHA256withRSA")
            sig.initSign(key)
            sig.update(tbs)
            return Der.sequence(tbs, algorithmId(), Der.bitString(sig.sign()))
        }

        private fun algorithmId(): ByteArray = Der.sequence(Der.oid(OID_SHA256_RSA), Der.nullValue())

        private fun extension(oid: String, critical: Boolean, value: ByteArray): ByteArray =
            if (critical) Der.sequence(Der.oid(oid), Der.boolean(true), Der.octetString(value))
            else Der.sequence(Der.oid(oid), Der.octetString(value))

        private fun keyUsage(bits: Int): ByteArray = byteArrayOf(Der.TAG_BIT_STRING.toByte(), 2, 1, bits.toByte())

        private fun keyId(spki: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(spki)

        private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

        fun parse(der: ByteArray): X509Certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }
}
