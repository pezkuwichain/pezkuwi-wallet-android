package io.novafoundation.nova.runtime.multiNetwork.connection.light

import android.content.Context
import android.util.Log
import io.novafoundation.nova.lightclient.HostResolver
import io.novafoundation.nova.lightclient.PezkuwiLight
import io.novafoundation.nova.runtime.multiNetwork.chain.model.ChainId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Dns
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The embedded light client (`bindings/pezkuwi-light`) for the chains it can verify.
 *
 * Started on first use, so a wallet whose RPC nodes answer never runs it, and a chain stops
 * syncing once [release]d. Chain specifications come from the APK (`light-client/` assets, built
 * from pezkuwi-wallet-utils): the genesis state root, boot nodes and, for a relay chain, a
 * checkpoint. Peer host names resolve through [dns], the app's DNS-over-HTTPS-first resolver.
 */
class LightClients(
    private val context: Context,
    private val dns: Dns,
    private val specApi: LightClientSpecApi,
) {

    private class LightChain(val file: String, val relay: ChainId?)

    private val chains = mapOf(
        ZAGROS to LightChain("zagros.json", relay = null),
        ZAGROS_PEOPLE to LightChain("people-zagros.json", relay = ZAGROS),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val specRefreshStarted = AtomicBoolean(false)

    private val mutex = Mutex()
    private var baseUrl: String? = null
    private val registered = mutableSetOf<ChainId>()
    private val inUse = mutableSetOf<ChainId>()

    fun supports(chainId: ChainId): Boolean = chainId in chains

    /** Loopback JSON-RPC URL of [chainId], or null when the light client cannot serve it. */
    suspend fun url(chainId: ChainId): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                if (!supports(chainId)) return@runCatching null
                val base = baseUrl ?: PezkuwiLight.start(File(context.filesDir, DATABASE_DIR), resolver()).also { baseUrl = it }
                register(chainId)
                inUse += chainId
                PezkuwiLight.chainUrl(base, chainId)
            }.onFailure {
                Log.w(TAG, "Light client unavailable for $chainId", it)
            }.getOrNull()
        }
    }

    /**
     * [chainId] no longer needs the light client, so it stops syncing: a synced chain costs data
     * and CPU while nothing reads it. A relay chain goes once none of its teyrchains is in use.
     */
    suspend fun release(chainId: ChainId) = mutex.withLock {
        withContext(Dispatchers.IO) {
            inUse -= chainId
            while (true) {
                val unused = registered.firstOrNull { key -> key !in inUse && registered.none { chains.getValue(it).relay == key } } ?: break
                // Still registered on failure, so a later url() does not register it twice
                runCatching { PezkuwiLight.removeChain(unused) }
                    .onSuccess { registered -= unused }
                    .onFailure { Log.w(TAG, "Cannot stop the light client for $unused", it) }
                    .getOrNull() ?: break
            }
        }
    }

    /**
     * Fetches each chain's specification from the config repository, once per process, and keeps it
     * when it is the same chain with a later checkpoint ([LightSpecs.isUpdate]). Called while RPC
     * works: the light client is wanted when it does not, and then the repository may not answer
     * either. Without it a first fallback starts from the checkpoint the APK was built with.
     */
    fun refreshSpecsInBackground() {
        if (!specRefreshStarted.compareAndSet(false, true)) return

        scope.launch {
            chains.values.forEach { chain ->
                runCatching {
                    val candidate = specApi.spec(SPEC_SOURCE + chain.file)
                    if (LightSpecs.isUpdate(current = spec(chain), candidate = candidate)) {
                        writeAtomically(storedSpec(chain), candidate)
                        Log.d(TAG, "Light client specification ${chain.file} updated")
                    }
                }.onFailure { Log.w(TAG, "Cannot refresh light client specification ${chain.file}", it) }
            }
        }
    }

    private fun register(chainId: ChainId) {
        if (chainId in registered) return
        val chain = chains.getValue(chainId)
        chain.relay?.let(::register)

        PezkuwiLight.addChain(chainId, spec(chain), chain.relay)
        registered += chainId
    }

    /** The stored specification when it is later than the APK's; an app update can bring a later one. */
    private fun spec(chain: LightChain): String {
        val bundled = context.assets.open(BUNDLED_DIR + chain.file).bufferedReader().use { it.readText() }
        val stored = storedSpec(chain).takeIf { it.exists() }?.readText() ?: return bundled

        return if (LightSpecs.isUpdate(current = bundled, candidate = stored)) stored else bundled
    }

    private fun storedSpec(chain: LightChain) = File(context.filesDir, "$SPECS_DIR/${chain.file}")

    private fun writeAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(text)
        if (!temporary.renameTo(file)) error("Cannot replace $file")
    }

    private fun resolver() = HostResolver { host ->
        dns.lookup(host).mapNotNull { it.hostAddress }.toTypedArray()
    }

    private companion object {
        const val ZAGROS: ChainId = "1b0b4727bbb5e44ed587d1056b4ad537ffb330034509a58866a088d77e792cd5"
        const val ZAGROS_PEOPLE: ChainId = "c72b38f36affaeb2661018b201c2be02e8c09fc699640525d39200a10fd77878"

        // Not LOG_TAG: called inside a coroutine, it gives the coroutine class name
        const val TAG = "LightClients"
        const val DATABASE_DIR = "light-client"
        const val SPECS_DIR = "light-client-specs"
        const val BUNDLED_DIR = "light-client/"
        const val SPEC_SOURCE = "https://raw.githubusercontent.com/pezkuwichain/pezkuwi-wallet-utils/master/light-client/"
    }
}
