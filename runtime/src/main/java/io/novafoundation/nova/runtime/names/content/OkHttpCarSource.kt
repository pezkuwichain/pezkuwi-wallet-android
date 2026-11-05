package io.novafoundation.nova.runtime.names.content

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Asks a trustless gateway for the blocks of one path as a CAR (`dag-scope=entity`: the path's
 * directories and the whole file or directory at its end), never more than [maxCarBytes].
 */
class OkHttpCarSource(
    private val client: OkHttpClient,
    private val maxCarBytes: Long = UnixFsReader.DEFAULT_MAX_FILE + 8L * 1024 * 1024,
) : CarSource {

    override suspend fun car(gateway: String, root: Cid, path: List<String>): ByteArray? = withContext(Dispatchers.IO) {
        val url = gateway.toHttpUrlOrNull()?.newBuilder()
            ?.addPathSegment("ipfs")
            ?.addPathSegment(root.toString())
            ?.apply { path.forEach(::addPathSegment) }
            ?.addQueryParameter("format", "car")
            ?.addQueryParameter("dag-scope", "entity")
            ?.build()
            ?: return@withContext null

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.ipld.car;version=1;order=dfs;dups=y")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body ?: return@withContext null
            if (body.contentLength() > maxCarBytes) throw IOException("CAR larger than $maxCarBytes bytes")

            val source = body.source()
            if (source.request(maxCarBytes + 1)) throw IOException("CAR larger than $maxCarBytes bytes")
            source.buffer.readByteArray()
        }
    }
}
