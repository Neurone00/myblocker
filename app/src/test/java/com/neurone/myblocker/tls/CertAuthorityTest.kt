package com.neurone.myblocker.tls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CertAuthorityTest {
    @Test fun caAndLeafVerifyWithJavaParser() {
        val dir = Files.createTempDirectory("adbrella-ca").toFile()
        val ca = CertAuthority.load(dir)
        val root = ca.caCert
        root.verify(root.publicKey) // self-signed signature checks out
        root.checkValidity()
        assertTrue("CA basic constraints", root.basicConstraints >= 0)
        assertTrue(root.subjectX500Principal.name.contains("Adbrella Local CA"))
        assertTrue(root.keyUsage[5]) // keyCertSign

        val leaf = ca.forHost("www.example.com")
        leaf.cert.verify(root.publicKey)
        leaf.cert.checkValidity()
        assertEquals(root.subjectX500Principal, leaf.cert.issuerX500Principal)
        assertEquals(-1, leaf.cert.basicConstraints) // not a CA
        val sans = leaf.cert.subjectAlternativeNames
        assertNotNull(sans)
        assertTrue(sans!!.any { it[0] == 2 && it[1] == "www.example.com" })
        assertTrue(leaf.cert.extendedKeyUsage.contains("1.3.6.1.5.5.7.3.1"))
        assertNotNull(leaf.sslContext)
        assertEquals(2, leaf.chain.size)

        // Cached, and reloaded from disk consistently
        assertTrue(leaf === ca.forHost("www.example.com"))
        val again = CertAuthority.load(dir)
        assertEquals(root, again.caCert)
        val ip = again.forHost("93.184.216.34")
        assertTrue(ip.cert.subjectAlternativeNames!!.any { it[0] == 7 })

        // Serials differ per host and stay positive
        assertTrue(leaf.cert.serialNumber.signum() > 0)
        assertTrue(leaf.cert.serialNumber != ca.forHost("other.example").cert.serialNumber)

        val pem = CertAuthority.pem(root)
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----\n"))
        assertTrue(pem.endsWith("-----END CERTIFICATE-----\n"))
        assertEquals(95, CertAuthority.fingerprint(root).length)
        dir.deleteRecursively()
    }

    @Test fun derPrimitives() {
        assertEquals(listOf(0x06, 0x03, 0x55, 0x04, 0x03), Der.oid("2.5.4.3").map { it.toInt() and 0xff })
        assertEquals(
            listOf(0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x0b),
            Der.oid("1.2.840.113549.1.1.11").map { it.toInt() and 0xff },
        )
        val long = Der.tlv(0x04, ByteArray(300))
        assertEquals(0x82, long[1].toInt() and 0xff)
        assertEquals(0x01, long[2].toInt())
        assertEquals(0x2c, long[3].toInt())
        assertEquals(listOf(0x02, 0x01, 0x00), Der.integer(0).map { it.toInt() })
        assertEquals(listOf(0x02, 0x02, 0x00, 0x80), Der.integer(128).map { it.toInt() and 0xff })
    }
}
