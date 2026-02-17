package io.novafoundation.nova.runtime.extrinsic

import io.novafoundation.nova.runtime.extrinsic.extensions.AuthorizeCall
import io.novafoundation.nova.runtime.extrinsic.extensions.ChargeAssetTxPayment
import io.novafoundation.nova.runtime.extrinsic.extensions.CheckAppId
import io.novafoundation.nova.runtime.extrinsic.extensions.CheckNonZeroSender
import io.novafoundation.nova.runtime.extrinsic.extensions.CheckWeight
import io.novafoundation.nova.runtime.extrinsic.extensions.WeightReclaim
import io.novasama.substrate_sdk_android.runtime.RuntimeSnapshot
import io.novasama.substrate_sdk_android.runtime.extrinsic.builder.ExtrinsicBuilder
import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.TransactionExtension

object CustomTransactionExtensions {

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
        val signedExtIds = runtime.metadata.extrinsic.signedExtensions.map { it.id }

        // Add extensions based on what the metadata requires
        if ("AuthorizeCall" in signedExtIds) {
            extensions.add(AuthorizeCall())
        }
        if ("CheckNonZeroSender" in signedExtIds) {
            extensions.add(CheckNonZeroSender())
        }
        if ("CheckWeight" in signedExtIds) {
            extensions.add(CheckWeight())
        }
        if ("WeightReclaim" in signedExtIds || "StorageWeightReclaim" in signedExtIds) {
            extensions.add(WeightReclaim())
        }
        if ("ChargeAssetTxPayment" in signedExtIds) {
            // Default to native fee payment (null assetId)
            extensions.add(ChargeAssetTxPayment())
        }
        if ("CheckAppId" in signedExtIds) {
            extensions.add(CheckAppId())
        }

        return extensions
    }
}
