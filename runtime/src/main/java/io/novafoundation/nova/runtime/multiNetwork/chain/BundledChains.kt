package io.novafoundation.nova.runtime.multiNetwork.chain

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.novafoundation.nova.common.utils.LOG_TAG
import io.novafoundation.nova.runtime.multiNetwork.chain.remote.model.ChainRemote

/** The chain list the APK was built with: what a first install starts from when no chain list can be fetched. */
fun interface BundledChains {

    fun load(): List<ChainRemote>?

    companion object {
        val None = BundledChains { null }
    }
}

class AssetBundledChains(
    private val context: Context,
    private val gson: Gson,
) : BundledChains {

    companion object {
        // Written at build time by the `bundleChainsConfig` task in runtime/build.gradle.
        const val ASSET_PATH = "bundled-config/chains.json"
    }

    override fun load(): List<ChainRemote>? = runCatching {
        context.assets.open(ASSET_PATH).bufferedReader().use { reader ->
            gson.fromJson<List<ChainRemote>>(reader, object : TypeToken<List<ChainRemote>>() {}.type)
        }
    }.onFailure {
        Log.e(LOG_TAG, "No usable bundled chain list", it)
    }.getOrNull()
}
