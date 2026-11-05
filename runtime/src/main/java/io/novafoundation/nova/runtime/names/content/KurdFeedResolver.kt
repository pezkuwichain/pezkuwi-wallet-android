package io.novafoundation.nova.runtime.names.content

import io.novafoundation.nova.runtime.names.NameRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant

/** One gateway's IPNS record for a name, or null when it has none. */
fun interface IpnsRecordSource {

    suspend fun record(gateway: String, name: String): ByteArray?
}

class OkHttpIpnsRecordSource(private val client: OkHttpClient) : IpnsRecordSource {

    override suspend fun record(gateway: String, name: String): ByteArray? = withContext(Dispatchers.IO) {
        val url = gateway.toHttpUrlOrNull()?.newBuilder()
            ?.addPathSegment("ipns")
            ?.addPathSegment(name)
            ?.addQueryParameter("format", "ipns-record")
            ?.build()
            ?: return@withContext null
        val request = Request.Builder().url(url).header("Accept", "application/vnd.ipfs.ipns-record").build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val source = response.body?.source() ?: return@withContext null
            if (source.request(IpnsRecords.MAX_RECORD + 1L)) return@withContext null
            source.buffer.readByteArray()
        }
    }
}

/**
 * The release a feed name serves: the newest one its publisher signed that any gateway has, never
 * older than the release the chain was last told of (its anchor), and the anchor itself when no
 * gateway has a record to show.
 */
class KurdFeedResolver(
    private val gateways: KurdGateways,
    private val source: IpnsRecordSource,
    private val clock: () -> Instant = Instant::now,
) {

    /** [onChainVersion]: no gateway confirmed a release at or past the anchor, so newer ones may exist. */
    class Release(val root: Cid, val onChainVersion: Boolean)

    suspend fun resolve(feed: NameRecord.Content.Feed): Release? {
        val anchor = Cid.fromContenthash(feed.anchor) ?: return null
        val floor = feed.seq
        val name = IpnsRecords.nameOf(feed.publisher)
        val now = clock()

        val records = coroutineScope {
            gateways.list().map { gateway ->
                async { runCatching { source.record(gateway, name) }.getOrNull()?.let { IpnsRecords.verify(it, feed.publisher, now) } }
            }.awaitAll()
        }
        val newest = records.filterNotNull().filter { it.sequence.toBigInteger() >= floor }.maxByOrNull { it.sequence }

        return when {
            newest == null -> Release(anchor, onChainVersion = true)
            // The release the chain already names: its root is the chain's word, not a gateway's
            newest.sequence.toBigInteger() == floor -> Release(anchor, onChainVersion = false)
            else -> Release(newest.root, onChainVersion = false)
        }
    }
}
