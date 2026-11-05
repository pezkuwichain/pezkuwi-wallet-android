package io.novafoundation.nova.runtime.names.content

import io.novafoundation.nova.runtime.names.NameRecord
import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom
import java.time.Instant

class KurdFeedResolverTest {

    private val keys = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }.generateKeyPair()
    private val publisher = (keys.public as Ed25519PublicKeyParameters).encoded
    private val anchor = cid("bafkreifjjcie6lypi6ny7amxnfftagclbuxndqonfipmb64f2km2devei4")
    private val newer = cid("bafkreifkam6ns4aoolg3wedr4uzrs3kvq5m6wzyijeswb3htqhmc6itmi4")
    private val now = Instant.parse("2026-11-05T00:00:00Z")

    @Test
    fun `a newer release a gateway has is served`() = runBlocking {
        val release = resolve(floor = 5, records = mapOf("https://a" to record(seq = 7, newer)))!!

        assertEquals(newer, release.root)
        assertFalse(release.onChainVersion)
    }

    @Test
    fun `the newest of several gateways wins`() = runBlocking {
        val records = mapOf("https://a" to record(seq = 6, anchor), "https://b" to record(seq = 9, newer))

        assertEquals(newer, resolve(floor = 5, records = records)!!.root)
    }

    @Test
    fun `a release older than the chain's anchor is never served`() = runBlocking {
        val release = resolve(floor = 5, records = mapOf("https://a" to record(seq = 4, newer)))!!

        assertEquals(anchor, release.root)
        assertTrue(release.onChainVersion)
    }

    @Test
    fun `the anchored release itself is the chain's word`() = runBlocking {
        val release = resolve(floor = 5, records = mapOf("https://a" to record(seq = 5, newer)))!!

        assertEquals(anchor, release.root)
        assertFalse(release.onChainVersion)
    }

    @Test
    fun `a record signed by another key is ignored`() = runBlocking {
        val stranger = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }.generateKeyPair()
        val forged = record(seq = 99, newer, signer = stranger.private as Ed25519PrivateKeyParameters)

        assertTrue(resolve(floor = 5, records = mapOf("https://a" to forged))!!.onChainVersion)
    }

    @Test
    fun `no gateway answering leaves the chain's version`() = runBlocking {
        val release = resolve(floor = 5, records = emptyMap())!!

        assertEquals(anchor, release.root)
        assertTrue(release.onChainVersion)
    }

    @Test
    fun `the feed is asked for by its publisher's IPNS name`() = runBlocking {
        val asked = mutableListOf<String>()
        KurdFeedResolver(gateways("https://a"), { _, name -> asked += name; null }, clock = { now }).resolve(feed(floor = 1))

        assertEquals(listOf(IpnsRecords.nameOf(publisher)), asked)
    }

    private suspend fun resolve(floor: Long, records: Map<String, ByteArray>): KurdFeedResolver.Release? {
        val source = IpnsRecordSource { gateway, _ -> records[gateway] }
        return KurdFeedResolver(gateways(*records.keys.ifEmpty { setOf("https://a") }.toTypedArray()), source, clock = { now }).resolve(feed(floor))
    }

    private fun feed(floor: Long) = NameRecord.Content.Feed(
        publisher = publisher,
        anchorer = ByteArray(32),
        anchor = byteArrayOf(0xe3.toByte(), 0x01) + anchor.bytes,
        seq = BigInteger.valueOf(floor),
    )

    /** A V2 IPNS record, built here only to exercise release selection; signing is tested against Kubo's records. */
    private fun record(seq: Long, root: Cid, signer: Ed25519PrivateKeyParameters = keys.private as Ed25519PrivateKeyParameters): ByteArray {
        val value = "/ipfs/$root".toByteArray()
        val validity = "2030-01-01T00:00:00Z".toByteArray()
        val data = cborMap(
            "Sequence" to cborUint(seq),
            "TTL" to cborUint(0),
            "Validity" to cborBytes(validity),
            "ValidityType" to cborUint(0),
            "Value" to cborBytes(value),
        )
        val signature = Ed25519Signer().run {
            init(true, signer)
            val message = "ipns-signature:".toByteArray() + data
            update(message, 0, message.size)
            generateSignature()
        }
        return protobufBytes(8, signature) + protobufBytes(9, data)
    }

    private fun protobufBytes(field: Int, bytes: ByteArray) = byteArrayOf(((field shl 3) or 2).toByte()) + varint(bytes.size.toLong()) + bytes

    private fun varint(value: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var rest = value
        while (rest >= 0x80) {
            out.write(((rest and 0x7f) or 0x80).toInt())
            rest = rest ushr 7
        }
        out.write(rest.toInt())
        return out.toByteArray()
    }

    private fun cborHead(major: Int, value: Long): ByteArray = when {
        value < 24 -> byteArrayOf(((major shl 5) or value.toInt()).toByte())
        value < 256 -> byteArrayOf(((major shl 5) or 24).toByte(), value.toByte())
        else -> byteArrayOf(((major shl 5) or 27).toByte()) + ByteArray(8) { (value ushr (56 - 8 * it)).toByte() }
    }

    private fun cborUint(value: Long) = cborHead(0, value)

    private fun cborBytes(bytes: ByteArray) = cborHead(2, bytes.size.toLong()) + bytes

    private fun cborMap(vararg entries: Pair<String, ByteArray>): ByteArray {
        return entries.fold(cborHead(5, entries.size.toLong())) { acc, (key, value) -> acc + cborHead(3, key.length.toLong()) + key.toByteArray() + value }
    }
}
