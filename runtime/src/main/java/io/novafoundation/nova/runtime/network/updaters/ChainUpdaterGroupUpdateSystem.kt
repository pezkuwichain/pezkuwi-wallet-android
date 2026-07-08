package io.novafoundation.nova.runtime.network.updaters

import android.util.Log
import io.novafoundation.nova.common.utils.LOG_TAG
import io.novafoundation.nova.common.utils.hasModule
import io.novafoundation.nova.core.updater.UpdateSystem
import io.novafoundation.nova.core.updater.Updater
import io.novafoundation.nova.runtime.ethereum.StorageSharedRequestsBuilderFactory
import io.novafoundation.nova.runtime.ethereum.subscribe
import io.novafoundation.nova.runtime.multiNetwork.ChainRegistry
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novafoundation.nova.runtime.multiNetwork.getRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.merge
import kotlin.coroutines.coroutineContext

abstract class ChainUpdaterGroupUpdateSystem(
    private val chainRegistry: ChainRegistry,
    private val storageSharedRequestsBuilderFactory: StorageSharedRequestsBuilderFactory,
) : UpdateSystem {

    // Callers (MultiChainUpdateSystem, SingleChainUpdateSystem) merge several chains' runUpdaters() results
    // into one flow. chainRegistry.getRuntime(chain.id) throws for a chain whose runtime isn't ready yet
    // (including a disabled chain, via DisabledChainException) - if that throw escapes this function
    // uncaught, it propagates through the merge and kills governance/staking/crowdloan sync for every OTHER
    // chain in the group too, not just the failing one. Wrapping the whole body in flow{} + catch isolates
    // that failure to this chain alone.
    protected suspend fun runUpdaters(chain: Chain, updaters: Collection<Updater<*>>): Flow<Updater.SideEffect> {
        return flow {
            val runtimeMetadata = chainRegistry.getRuntime(chain.id).metadata

            val logTag = this@ChainUpdaterGroupUpdateSystem.LOG_TAG
            val selfName = this@ChainUpdaterGroupUpdateSystem::class.java.simpleName

            val scopeFlows = updaters.groupBy(Updater<*>::scope).map { (scope, scopeUpdaters) ->
                scope.invalidationFlow().flatMapLatest { scopeValue ->
                    val subscriptionBuilder = storageSharedRequestsBuilderFactory.create(chain.id)

                    val updatersFlow = scopeUpdaters
                        .filter { it.requiredModules.all(runtimeMetadata::hasModule) }
                        .map { updater ->
                            @Suppress("UNCHECKED_CAST")
                            (updater as Updater<Any?>).listenForUpdates(subscriptionBuilder, scopeValue)
                                .catch { Log.e(logTag, "Failed to start ${updater.javaClass.simpleName} in $selfName for ${chain.name}", it) }
                                .flowOn(Dispatchers.Default)
                        }

                    if (updatersFlow.isNotEmpty()) {
                        subscriptionBuilder.subscribe(coroutineContext)

                        updatersFlow.merge()
                    } else {
                        emptyFlow()
                    }
                }
            }

            emitAll(scopeFlows.merge())
        }.catch { error ->
            // Explicitly qualified: unqualified LOG_TAG here would resolve against the nearest implicit
            // receiver, which is this catch lambda's FlowCollector, not this class - Any.LOG_TAG applies to
            // any receiver, so it would silently compile but log the wrong (unhelpful) tag.
            Log.e(this@ChainUpdaterGroupUpdateSystem.LOG_TAG, "Failed to start updaters in ${this@ChainUpdaterGroupUpdateSystem::class.java.simpleName} for ${chain.name}", error)
        }
    }
}
