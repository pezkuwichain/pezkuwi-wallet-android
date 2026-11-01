package io.novafoundation.nova.feature_dapp_impl.data.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import io.novafoundation.nova.common.utils.LOG_TAG
import io.novafoundation.nova.feature_dapp_impl.data.network.metadata.DappMetadataResponse

/** The DApps catalog the APK was built with: what is shown until a catalog can be fetched. */
fun interface BundledDappCatalog {

    fun load(): DappMetadataResponse?

    companion object {
        val None = BundledDappCatalog { null }
    }
}

class AssetBundledDappCatalog(
    private val context: Context,
    private val gson: Gson,
) : BundledDappCatalog {

    companion object {
        // Written at build time by the `bundleDappsConfig` task in feature-dapp-impl/build.gradle.
        const val ASSET_PATH = "bundled-config/dapps.json"
    }

    override fun load(): DappMetadataResponse? = runCatching {
        context.assets.open(ASSET_PATH).bufferedReader().use { gson.fromJson(it, DappMetadataResponse::class.java) }
    }.onFailure {
        Log.e(LOG_TAG, "No usable bundled DApps catalog", it)
    }.getOrNull()
}
