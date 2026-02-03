package io.novafoundation.nova.runtime.extrinsic

import android.util.Log
import io.novafoundation.nova.runtime.extrinsic.extensions.AuthorizeCall
import io.novafoundation.nova.runtime.extrinsic.extensions.ChargeAssetTxPayment
import io.novafoundation.nova.runtime.extrinsic.extensions.CheckAppId
import io.novafoundation.nova.runtime.extrinsic.extensions.CheckNonZeroSender
import io.novafoundation.nova.runtime.extrinsic.extensions.CheckWeight
import io.novafoundation.nova.runtime.extrinsic.extensions.WeightReclaim
import io.novasama.substrate_sdk_android.runtime.RuntimeSnapshot
import io.novasama.substrate_sdk_android.runtime.extrinsic.builder.ExtrinsicBuilder
import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.TransactionExtension

private const val TAG = "CustomTxExtensions"

object CustomTransactionExtensions {

    // PezkuwiChain genesis hashes (mainnet and teyrchains)
    private val PEZKUWI_GENESIS_HASHES = setOf(
        "bb4a61ab0c4b8c12f5eab71d0c86c482e03a275ecdafee678dea712474d33d75", // Pezkuwi Mainnet
        "00d0e1d0581c3cd5c5768652d52f4520184018b44f56a2ae1e0dc9d65c00c948", // Asset Hub
        "58269e9c184f721e0309332d90cafc410df1519a5dc27a5fd9b3bf5fd2d129f8", // People Chain
        "96eb58af1bb7288115b5e4ff1590422533e749293f231974536dc6672417d06f"  // Zagros Testnet
    )

    fun applyDefaultValues(builder: ExtrinsicBuilder) {
        defaultValues().forEach(builder::setTransactionExtension)
    }

    fun applyDefaultValues(builder: ExtrinsicBuilder, runtime: RuntimeSnapshot) {
        defaultValues(runtime).forEach(builder::setTransactionExtension)
    }

    fun defaultValues(): List<TransactionExtension> {
        return listOf(
            ChargeAssetTxPayment(),
            CheckAppId()
        )
    }

    fun defaultValues(runtime: RuntimeSnapshot): List<TransactionExtension> {
        val extensions = mutableListOf<TransactionExtension>()
        val isPezkuwi = isPezkuwiChain(runtime)

        Log.d(TAG, "isPezkuwiChain: $isPezkuwi")

        if (isPezkuwi) {
            // Pezkuwi needs: AuthorizeCall, CheckNonZeroSender, CheckWeight, WeightReclaim
            // Other extensions (CheckMortality, CheckGenesis, etc.) are set in ExtrinsicBuilderFactory
            Log.d(TAG, "Adding Pezkuwi extensions: AuthorizeCall, CheckNonZeroSender, CheckWeight, WeightReclaim")
            extensions.add(AuthorizeCall())
            extensions.add(CheckNonZeroSender())
            extensions.add(CheckWeight())
            extensions.add(WeightReclaim())
        } else {
            // Other chains (Asset Hub, etc.) use ChargeAssetTxPayment and CheckAppId
            Log.d(TAG, "Adding default extensions: ChargeAssetTxPayment, CheckAppId")
            extensions.add(ChargeAssetTxPayment())
            extensions.add(CheckAppId())
        }

        return extensions
    }

    private fun isPezkuwiChain(runtime: RuntimeSnapshot): Boolean {
        val signedExtIds = runtime.metadata.extrinsic.signedExtensions.map { it.id }
        Log.d(TAG, "Metadata signed extensions: $signedExtIds")
        val hasAuthorizeCall = signedExtIds.any { it == "AuthorizeCall" }
        Log.d(TAG, "Has AuthorizeCall: $hasAuthorizeCall")
        return hasAuthorizeCall
    }
}
