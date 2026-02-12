package io.novafoundation.nova.runtime.extrinsic

import android.util.Log
import io.novafoundation.nova.common.utils.orZero
import io.novafoundation.nova.runtime.ext.requireGenesisHash
import io.novafoundation.nova.runtime.extrinsic.extensions.PezkuwiCheckMortality
import io.novafoundation.nova.runtime.extrinsic.metadata.MetadataShortenerService
import io.novafoundation.nova.runtime.multiNetwork.ChainRegistry
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novafoundation.nova.runtime.multiNetwork.getRuntime
import io.novasama.substrate_sdk_android.extensions.fromHex
import io.novasama.substrate_sdk_android.runtime.RuntimeSnapshot
import io.novasama.substrate_sdk_android.runtime.extrinsic.BatchMode
import io.novasama.substrate_sdk_android.runtime.extrinsic.ExtrinsicVersion
import io.novasama.substrate_sdk_android.runtime.extrinsic.builder.ExtrinsicBuilder
import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.extensions.ChargeTransactionPayment
import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.extensions.CheckGenesis
import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.extensions.CheckMortality
import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.extensions.CheckSpecVersion
import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.extensions.CheckTxVersion
import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.extensions.checkMetadataHash.CheckMetadataHash

private const val TAG = "ExtrinsicBuilderFactory"

class ExtrinsicBuilderFactory(
    private val chainRegistry: ChainRegistry,
    private val mortalityConstructor: MortalityConstructor,
    private val metadataShortenerService: MetadataShortenerService,
) {

    class Options(
        val batchMode: BatchMode,
    )

    suspend fun create(
        chain: Chain,
        options: Options,
    ): ExtrinsicBuilder {
        return createMulti(chain, options).first()
    }

    suspend fun createMulti(
        chain: Chain,
        options: Options,
    ): Sequence<ExtrinsicBuilder> {
        val runtime = chainRegistry.getRuntime(chain.id)

        // Log metadata extensions
        val metadataExtensions = runtime.metadata.extrinsic.signedExtensions.map { it.id }
        Log.d(TAG, "Chain: ${chain.name}, Metadata extensions: $metadataExtensions")

        val mortality = mortalityConstructor.constructMortality(chain.id)
        val metadataProof = metadataShortenerService.generateMetadataProof(chain.id)

        // Log custom extensions
        val customExtensions = CustomTransactionExtensions.defaultValues(runtime).map { it.name }
        Log.d(TAG, "Custom extensions to add: $customExtensions")

        val isPezkuwi = isPezkuwiChain(runtime)
        Log.d(TAG, "isPezkuwiChain: $isPezkuwi")

        return generateSequence {
            ExtrinsicBuilder(
                runtime = runtime,
                extrinsicVersion = ExtrinsicVersion.V4,
                batchMode = options.batchMode,
            ).apply {
                // Use custom CheckMortality for Pezkuwi chains to avoid type lookup issues
                if (isPezkuwi) {
                    Log.d(TAG, "Using PezkuwiCheckMortality for ${chain.name}")
                    setTransactionExtension(PezkuwiCheckMortality(mortality.era, mortality.blockHash.fromHex()))
                } else {
                    setTransactionExtension(CheckMortality(mortality.era, mortality.blockHash.fromHex()))
                }
                setTransactionExtension(CheckGenesis(chain.requireGenesisHash().fromHex()))
                setTransactionExtension(ChargeTransactionPayment(chain.additional?.defaultTip.orZero()))
                setTransactionExtension(CheckMetadataHash(metadataProof.checkMetadataHash))
                setTransactionExtension(CheckSpecVersion(metadataProof.usedVersion.specVersion))
                setTransactionExtension(CheckTxVersion(metadataProof.usedVersion.transactionVersion))

                CustomTransactionExtensions.defaultValues(runtime).forEach(::setTransactionExtension)

                Log.d(TAG, "All extensions set for ${chain.name}")
            }
        }
    }

    private fun isPezkuwiChain(runtime: RuntimeSnapshot): Boolean {
        val signedExtIds = runtime.metadata.extrinsic.signedExtensions.map { it.id }
        return signedExtIds.any { it == "AuthorizeCall" }
    }
}
