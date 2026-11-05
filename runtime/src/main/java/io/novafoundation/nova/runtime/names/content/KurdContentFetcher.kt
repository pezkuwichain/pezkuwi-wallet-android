package io.novafoundation.nova.runtime.names.content

import android.util.Log
import retrofit2.http.GET
import retrofit2.http.Url

/** Where `.kurd` content is fetched from: IPFS trustless gateways that answer `?format=car`. */
interface KurdGateways {

    /** Base URLs, `https://host`, in the order they are tried. */
    suspend fun list(): List<String>
}

interface KurdGatewayListApi {

    /** A file of the config repository, checked against its signed targets by the app's HTTP client. */
    @GET
    suspend fun gateways(@Url url: String): GatewayListRemote
}

class GatewayListRemote(val gateways: List<GatewayRemote>?) {

    class GatewayRemote(val url: String?)
}

/**
 * The signed list from the config repository when it can be read, the built-in one otherwise. The
 * list can only add or reorder gateways: whatever a gateway sends is verified block by block, so a
 * bad entry costs time, never content.
 */
class RealKurdGateways(
    private val api: KurdGatewayListApi,
    private val refreshMillis: Long = 30 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) : KurdGateways {

    @Volatile
    private var remote: Pair<Long, List<String>>? = null

    override suspend fun list(): List<String> {
        val cached = remote
        if (cached != null && clock() - cached.first < refreshMillis) return cached.second

        val fetched = runCatching { api.gateways(LIST_URL).gateways.orEmpty().mapNotNull { it.url?.let(::normalized) } }
            .onFailure { Log.w(TAG, "Signed gateway list unavailable, using the built-in one", it) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }

        if (fetched != null) remote = clock() to fetched
        return fetched ?: cached?.second ?: BUILT_IN
    }

    companion object {

        const val LIST_URL = "https://raw.githubusercontent.com/pezkuwichain/pezkuwi-wallet-utils/master/kurd/gateways.json"

        /** Measured reachable from Türkiye (architecture, M0); our own mirrors join through the signed list. */
        val BUILT_IN = listOf("https://ipfs.io", "https://dweb.link")

        private const val TAG = "KurdGateways"

        /** `https://host[:port]` only: a path or query in an entry would change what is asked for. */
        internal fun normalized(url: String): String? {
            val match = Regex("^https://([a-z0-9.-]+)(:[0-9]{1,5})?/?$").matchEntire(url.trim().lowercase()) ?: return null
            return "https://" + match.groupValues[1] + match.groupValues[2]
        }
    }
}

/** One gateway's answer for a CAR request: the bytes, or null when it has none (404 and the like). */
fun interface CarSource {

    suspend fun car(gateway: String, root: Cid, path: List<String>): ByteArray?
}

/**
 * Fetches a path under a content root from the gateways, in order, and keeps the first answer that
 * verifies. A gateway that fails, times out, withholds a block or sends a changed one is skipped.
 */
class KurdContentFetcher(
    private val gateways: KurdGateways,
    private val source: CarSource,
    private val maxFileSize: Int = UnixFsReader.DEFAULT_MAX_FILE,
) {

    sealed interface Result {

        class File(val bytes: ByteArray, val path: List<String>) : Result

        /** Proven absent: the parent directory was verified and does not have the name. */
        object NotFound : Result

        /** No gateway gave a verifiable answer. */
        object Unavailable : Result
    }

    /** The file at [path] under [root]; a directory gives its `index.html`. */
    suspend fun fetch(root: Cid, path: List<String>): Result {
        return when (val entry = entry(root, path)) {
            is Lookup.Found -> when (val found = entry.entry) {
                is UnixFsReader.Entry.File -> Result.File(found.bytes, path)
                is UnixFsReader.Entry.Directory -> when (val index = entry(root, path + INDEX)) {
                    is Lookup.Found -> (index.entry as? UnixFsReader.Entry.File)?.let { Result.File(it.bytes, path + INDEX) } ?: Result.NotFound
                    Lookup.Absent -> Result.NotFound
                    Lookup.Unavailable -> Result.Unavailable
                }
            }
            Lookup.Absent -> Result.NotFound
            Lookup.Unavailable -> Result.Unavailable
        }
    }

    private sealed interface Lookup {
        class Found(val entry: UnixFsReader.Entry) : Lookup
        object Absent : Lookup
        object Unavailable : Lookup
    }

    private suspend fun entry(root: Cid, path: List<String>): Lookup {
        for (gateway in gateways.list()) {
            val entry = runCatching { read(gateway, root, path) }
                .onFailure { Log.w(TAG, "Gateway $gateway gave no verifiable answer for $root", it) }
                .getOrNull()
            if (entry != null) return entry
        }
        return Lookup.Unavailable
    }

    /** Null when this gateway could not answer; [Lookup.Absent] only once absence is verified. */
    private suspend fun read(gateway: String, root: Cid, path: List<String>): Lookup? {
        val car = source.car(gateway, root, path)
        if (car != null) {
            val entry = UnixFsReader(VerifiedBlocks.fromCar(car), maxFileSize).read(root, path) ?: return null
            return Lookup.Found(entry)
        }

        // A gateway that says "not found" may be withholding: check the parent directory says so
        if (path.isEmpty()) return null
        val parentCar = source.car(gateway, root, path.dropLast(1)) ?: return null
        val reader = UnixFsReader(VerifiedBlocks.fromCar(parentCar), maxFileSize)
        if (reader.read(root, path.dropLast(1)) !is UnixFsReader.Entry.Directory) return null
        return if (reader.read(root, path) == null) Lookup.Absent else null
    }

    companion object {

        const val INDEX = "index.html"

        private const val TAG = "KurdContentFetcher"
    }
}
