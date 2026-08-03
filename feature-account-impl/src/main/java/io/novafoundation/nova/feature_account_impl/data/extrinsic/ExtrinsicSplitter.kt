package io.novafoundation.nova.feature_account_impl.data.extrinsic

import io.novafoundation.nova.common.data.network.runtime.binding.WeightV2
import io.novafoundation.nova.common.data.network.runtime.binding.fitsIn
import io.novafoundation.nova.common.di.scope.FeatureScope
import io.novafoundation.nova.feature_account_api.data.signer.NovaSigner
import io.novafoundation.nova.feature_account_api.data.signer.SigningContext
import io.novafoundation.nova.common.utils.min
import io.novafoundation.nova.feature_account_api.data.extrinsic.ExtrinsicSplitter
import io.novafoundation.nova.feature_account_api.data.extrinsic.SplitCalls
import io.novafoundation.nova.runtime.ext.isPezkuwiChain
import io.novafoundation.nova.runtime.ext.requireGenesisHash
import io.novafoundation.nova.runtime.extrinsic.CustomTransactionExtensions
import io.novafoundation.nova.runtime.extrinsic.extensions.PezkuwiCheckImmortal
import io.novafoundation.nova.runtime.extrinsic.multi.CallBuilder
import io.novafoundation.nova.runtime.multiNetwork.ChainRegistry
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novafoundation.nova.runtime.multiNetwork.getRuntime
import io.novafoundation.nova.runtime.network.binding.BlockWeightLimits
import io.novafoundation.nova.runtime.network.binding.PerDispatchClassWeight
import io.novafoundation.nova.runtime.network.binding.total
import io.novafoundation.nova.runtime.network.rpc.RpcCalls
import io.novafoundation.nova.runtime.repository.BlockLimitsRepository
import io.novasama.substrate_sdk_android.extensions.fromHex
import io.novasama.substrate_sdk_android.runtime.RuntimeSnapshot
import io.novasama.substrate_sdk_android.runtime.definitions.types.generics.Era
import io.novasama.substrate_sdk_android.runtime.definitions.types.generics.GenericCall
import io.novasama.substrate_sdk_android.runtime.extrinsic.BatchMode
import io.novasama.substrate_sdk_android.runtime.extrinsic.ExtrinsicVersion
import io.novasama.substrate_sdk_android.runtime.extrinsic.builder.ExtrinsicBuilder
import io.novasama.substrate_sdk_android.runtime.extrinsic.signer.SendableExtrinsic
import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.extensions.ChargeTransactionPayment
import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.extensions.CheckGenesis
import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.extensions.CheckMortality
import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.extensions.CheckSpecVersion
import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.extensions.CheckTxVersion
import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.extensions.checkMetadataHash.CheckMetadataHash
import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.extensions.checkMetadataHash.CheckMetadataHashMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.math.BigInteger
import javax.inject.Inject

private typealias CallWeightsByType = Map<String, Deferred<WeightV2>>

private const val LEAVE_SOME_SPACE_MULTIPLIER = 0.8

// DIAGNOSTIC BUILD — remove with the probe in wrapInFakeExtrinsic.
private const val DIAG = "PezMsigDiag"

@FeatureScope
internal class RealExtrinsicSplitter @Inject constructor(
    private val rpcCalls: RpcCalls,
    private val blockLimitsRepository: BlockLimitsRepository,
    private val signingContextFactory: SigningContext.Factory,
    private val chainRegistry: ChainRegistry,
) : ExtrinsicSplitter {

    override suspend fun split(signer: NovaSigner, callBuilder: CallBuilder, chain: Chain): SplitCalls = coroutineScope {
        val weightByCallId = estimateWeightByCallType(signer, callBuilder, chain)

        val blockLimit = blockLimitsRepository.blockLimits(chain.id)
        val lastBlockWeight = blockLimitsRepository.lastBlockWeight(chain.id)
        val extrinsicLimit = determineExtrinsicLimit(blockLimit, lastBlockWeight)

        val signerLimit = signer.maxCallsPerTransaction()

        callBuilder.splitCallsWith(weightByCallId, extrinsicLimit, signerLimit)
    }

    override suspend fun estimateCallWeight(signer: NovaSigner, call: GenericCall.Instance, chain: Chain): WeightV2 {
        val runtime = chainRegistry.getRuntime(chain.id)
        val fakeExtrinsic = wrapInFakeExtrinsic(signer, call, runtime, chain)
        return rpcCalls.getExtrinsicFee(chain, fakeExtrinsic).weight
    }

    private fun determineExtrinsicLimit(blockLimits: BlockWeightLimits, lastBlockWeight: PerDispatchClassWeight): WeightV2 {
        val extrinsicLimit = blockLimits.perClass.normal.maxExtrinsic
        val normalClassLimit = blockLimits.perClass.normal.maxTotal - lastBlockWeight.normal
        val blockLimit = blockLimits.maxBlock - lastBlockWeight.total()

        val unionLimit = min(extrinsicLimit, normalClassLimit, blockLimit)
        return unionLimit * LEAVE_SOME_SPACE_MULTIPLIER
    }

    private val GenericCall.Instance.uniqueId: String
        get() {
            val (moduleIdx, functionIdx) = function.index
            return "$moduleIdx:$functionIdx"
        }

    @Suppress("SuspendFunctionOnCoroutineScope")
    private suspend fun CoroutineScope.estimateWeightByCallType(signer: NovaSigner, callBuilder: CallBuilder, chain: Chain): CallWeightsByType {
        return callBuilder.calls.groupBy { it.uniqueId }
            .mapValues { (_, calls) ->
                val sample = calls.first()
                val sampleExtrinsic = wrapInFakeExtrinsic(signer, sample, callBuilder.runtime, chain)

                async { rpcCalls.getExtrinsicFee(chain, sampleExtrinsic).weight }
            }
    }

    private suspend fun CallBuilder.splitCallsWith(
        weights: CallWeightsByType,
        blockWeightLimit: WeightV2,
        signerNumberOfCallsLimit: Int?,
    ): SplitCalls {
        val split = mutableListOf<List<GenericCall.Instance>>()

        var currentBatch = mutableListOf<GenericCall.Instance>()
        var currentBatchWeight: WeightV2 = WeightV2.zero()

        calls.forEach { call ->
            val estimatedCallWeight = weights.getValue(call.uniqueId).await()
            val newWeight = currentBatchWeight + estimatedCallWeight
            val exceedsByWeight = !newWeight.fitsIn(blockWeightLimit)
            val exceedsByNumberOfCalls = signerNumberOfCallsLimit != null && currentBatch.size >= signerNumberOfCallsLimit

            if (exceedsByWeight || exceedsByNumberOfCalls) {
                if (!estimatedCallWeight.fitsIn(blockWeightLimit)) throw IllegalArgumentException("Impossible to fit call $call into a block")

                split += currentBatch

                currentBatchWeight = estimatedCallWeight
                currentBatch = mutableListOf(call)
            } else {
                currentBatchWeight += estimatedCallWeight
                currentBatch += call
            }
        }

        if (currentBatch.isNotEmpty()) {
            split.add(currentBatch)
        }

        return split
    }

    /**
     * DIAGNOSTIC BUILD — not for release.
     *
     * "Failed to encode extension CheckMortality" reproduces here, and only here, when
     * approving a multisig operation on Pezkuwi Asset Hub. This path is multisig-only:
     * asMulti needs a max_weight, so a throwaway signed extrinsic is built to measure
     * the inner call. Plain transfers never reach it, which is why they succeed.
     *
     * Static reading could not tell which side of the isPezkuwiChain gate fails, so this
     * build tries BOTH era encodings and reports the outcome of each. Read the PezMsigDiag
     * lines to see which one the chain accepts, then wire that choice in permanently and
     * delete this.
     */
    private suspend fun wrapInFakeExtrinsic(
        signer: NovaSigner,
        call: GenericCall.Instance,
        runtime: RuntimeSnapshot,
        chain: Chain
    ): SendableExtrinsic {
        val genesisHash = chain.requireGenesisHash().fromHex()
        val isPezkuwi = chain.isPezkuwiChain

        android.util.Log.e(
            DIAG,
            "chain='${chain.name}' id=${chain.id} isPezkuwiChain=$isPezkuwi " +
                "genesis=${chain.requireGenesisHash()}"
        )
        android.util.Log.e(
            DIAG,
            "signedExtensions=${runtime.metadata.extrinsic.signedExtensions.map { it.id }}"
        )

        // Builds the fake extrinsic with one specific era encoding. Kept as a local so the
        // two attempts differ in exactly one thing and nothing else.
        suspend fun attempt(usePezkuwiEra: Boolean): Result<SendableExtrinsic> = runCatching {
            ExtrinsicBuilder(
                runtime = runtime,
                extrinsicVersion = ExtrinsicVersion.V4,
                batchMode = BatchMode.BATCH,
            ).apply {
                if (usePezkuwiEra) {
                    setTransactionExtension(PezkuwiCheckImmortal(genesisHash))
                } else {
                    setTransactionExtension(CheckMortality(Era.Immortal, genesisHash))
                }
                setTransactionExtension(CheckGenesis(chain.requireGenesisHash().fromHex()))
                setTransactionExtension(ChargeTransactionPayment(BigInteger.ZERO))
                setTransactionExtension(CheckMetadataHash(CheckMetadataHashMode.Disabled))
                setTransactionExtension(CheckSpecVersion(0))
                setTransactionExtension(CheckTxVersion(0))

                CustomTransactionExtensions.defaultValues(runtime).forEach(::setTransactionExtension)

                call(call)

                val signingContext = signingContextFactory.default(chain)
                signer.setSignerDataForFee(signingContext)
            }.buildExtrinsic()
        }

        fun report(label: String, result: Result<SendableExtrinsic>) {
            result.fold(
                onSuccess = { android.util.Log.e(DIAG, "$label -> OK") },
                onFailure = { e ->
                    // The message alone has been the whole diagnosis so far; the cause chain
                    // is what actually names the failing type.
                    val causes = generateSequence(e) { it.cause }.joinToString(" <- ") {
                        "${it::class.java.simpleName}: ${it.message}"
                    }
                    android.util.Log.e(DIAG, "$label -> FAIL  $causes", e)
                }
            )
        }

        // Preferred first: whatever the current gate would have chosen on its own.
        val preferred = attempt(usePezkuwiEra = isPezkuwi)
        report(if (isPezkuwi) "PezkuwiCheckImmortal(gate choice)" else "CheckMortality(gate choice)", preferred)
        preferred.getOrNull()?.let { return it }

        val alternative = attempt(usePezkuwiEra = !isPezkuwi)
        report(if (isPezkuwi) "CheckMortality(alternative)" else "PezkuwiCheckImmortal(alternative)", alternative)

        return alternative.getOrElse { throw preferred.exceptionOrNull()!! }
    }
}
