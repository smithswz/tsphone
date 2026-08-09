package com.smithswz.tsphone

import com.github.manevolent.ts3j.identity.LocalIdentity
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.Security

/**
 * Verifies that a generated identity survives the DataStore round trip:
 * export() → string → read() reproduces the same UID and public key.
 */
class IdentityRoundtripTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun registerProvider() {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }

    @Test
    fun exportImportRoundtrip() {
        val identity = LocalIdentity.generateNew(8)
        val exported = identity.export()

        val restored = LocalIdentity.read(exported.byteInputStream())

        assertEquals(identity.uid.toBase64(), restored.uid.toBase64())
        assertEquals(identity.getPublicKeyString(), restored.getPublicKeyString())
        assertTrue(restored.getSecurityLevel() >= 8)
    }

    @Test
    fun differentIdentitiesDiffer() {
        val a = LocalIdentity.generateNew(8)
        val b = LocalIdentity.generateNew(8)
        assertTrue(a.uid.toBase64() != b.uid.toBase64())
    }
}
