package io.novafoundation.nova.runtime.multiNetwork.connection.light

import android.content.Context
import android.util.Log
import io.novafoundation.nova.common.utils.LOG_TAG
import io.novafoundation.nova.lightclient.HostResolver
import io.novafoundation.nova.lightclient.PezkuwiLight
import io.novafoundation.nova.runtime.multiNetwork.chain.model.ChainId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Dns
import java.io.File

/**
 * The embedded light client (`bindings/pezkuwi-light`) for the chains it can verify.
 *
 * Started on first use, so a wallet whose RPC nodes answer never runs it. Chain specifications
 * come from the APK (`light-client/` assets, built from pezkuwi-wallet-utils): the genesis state
 * root, boot nodes and, for a relay chain, a checkpoint. Peer host names resolve through [dns],
 * the app's DNS-over-HTTPS-first resolver.
 */
class LightClients(
    private val context: Context,
    private val dns: Dns,
) {

    private class LightChain(val asset: String, val relay: ChainId?)

    private val chains = mapOf(
        ZAGROS to LightChain("light-client/zagros.json", relay = null),
        ZAGROS_PEOPLE to LightChain("light-client/people-zagros.json", relay = ZAGROS),
    )

    private val mutex = Mutex()
    private var baseUrl: String? = null
    private val registered = mutableSetOf<ChainId>()

    fun supports(chainId: ChainId): Boolean = chainId in chains

    /** Loopback JSON-RPC URL of [chainId], or null when the light client cannot serve it. */
    suspend fun url(chainId: ChainId): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                if (!supports(chainId)) return@runCatching null
                val base = baseUrl ?: PezkuwiLight.start(File(context.filesDir, DATABASE_DIR), resolver()).also { baseUrl = it }
                register(chainId)
                PezkuwiLight.chainUrl(base, chainId)
            }.onFailure {
                Log.w(LOG_TAG, "Light client unavailable for $chainId", it)
            }.getOrNull()
        }
    }

    private fun register(chainId: ChainId) {
        if (chainId in registered) return
        val chain = chains.getValue(chainId)
        chain.relay?.let(::register)

        val spec = context.assets.open(chain.asset).bufferedReader().use { it.readText() }
        PezkuwiLight.addChain(chainId, spec, chain.relay)
        registered += chainId
    }

    private fun resolver() = HostResolver { host ->
        dns.lookup(host).mapNotNull { it.hostAddress }.toTypedArray()
    }

    private companion object {
        const val ZAGROS: ChainId = "1b0b4727bbb5e44ed587d1056b4ad537ffb330034509a58866a088d77e792cd5"
        const val ZAGROS_PEOPLE: ChainId = "c72b38f36affaeb2661018b201c2be02e8c09fc699640525d39200a10fd77878"

        const val DATABASE_DIR = "light-client"
    }
}
