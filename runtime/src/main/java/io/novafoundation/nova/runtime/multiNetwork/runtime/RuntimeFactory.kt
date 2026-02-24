package io.novafoundation.nova.runtime.multiNetwork.runtime

import com.google.gson.Gson
import io.novafoundation.nova.common.utils.md5
import io.novafoundation.nova.common.utils.newLimitedThreadPoolExecutor
import io.novafoundation.nova.core_db.dao.ChainDao
import io.novafoundation.nova.runtime.multiNetwork.chain.model.TypesUsage
import io.novafoundation.nova.runtime.multiNetwork.runtime.types.custom.pezkuwi.PezkuwiPathTypeMapping
import io.novafoundation.nova.runtime.multiNetwork.runtime.types.custom.vote.SiVoteTypeMapping
import io.novasama.substrate_sdk_android.runtime.RuntimeSnapshot
import io.novasama.substrate_sdk_android.runtime.definitions.TypeDefinitionParser.parseBaseDefinitions
import io.novasama.substrate_sdk_android.runtime.definitions.TypeDefinitionParser.parseNetworkVersioning
import io.novasama.substrate_sdk_android.runtime.definitions.TypeDefinitionsTree
import io.novasama.substrate_sdk_android.runtime.definitions.dynamic.DynamicTypeResolver
import io.novasama.substrate_sdk_android.runtime.definitions.dynamic.extentsions.GenericsExtension
import io.novasama.substrate_sdk_android.runtime.definitions.registry.TypePreset
import io.novasama.substrate_sdk_android.runtime.definitions.registry.TypeRegistry
import io.novasama.substrate_sdk_android.runtime.definitions.registry.v13Preset
import io.novasama.substrate_sdk_android.runtime.definitions.registry.v14Preset
import io.novasama.substrate_sdk_android.runtime.definitions.v14.TypesParserV14
import io.novasama.substrate_sdk_android.runtime.definitions.v14.typeMapping.SiTypeMapping
import io.novasama.substrate_sdk_android.runtime.definitions.v14.typeMapping.default
import io.novasama.substrate_sdk_android.runtime.definitions.v14.typeMapping.plus
import io.novasama.substrate_sdk_android.runtime.metadata.RuntimeMetadataReader
import io.novasama.substrate_sdk_android.runtime.metadata.builder.VersionedRuntimeBuilder
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class ConstructedRuntime(
    val runtime: RuntimeSnapshot,
    val metadataHash: String,
    val baseTypesHash: String?,
    val ownTypesHash: String?,
    val runtimeVersion: Int,
    val typesUsage: TypesUsage,
)

object BaseTypesNotInCacheException : Exception()
object ChainInfoNotInCacheException : Exception()
object NoRuntimeVersionException : Exception()

class RuntimeFactory(
    private val runtimeFilesCache: RuntimeFilesCache,
    private val chainDao: ChainDao,
    private val gson: Gson,
    private val concurrencyLimit: Int = 1
) {
    companion object {
        @Volatile
        var lastDiagnostics: String = "not yet initialized"
    }

    private val dispatcher = newLimitedThreadPoolExecutor(concurrencyLimit).asCoroutineDispatcher()
    private val semaphore = Semaphore(concurrencyLimit)

    suspend fun constructRuntime(
        chainId: String,
        typesUsage: TypesUsage,
    ): ConstructedRuntime = semaphore.withPermit {
        constructRuntimeInternal(chainId, typesUsage)
    }

    /**
     * @throws BaseTypesNotInCacheException
     * @throws ChainInfoNotInCacheException
     * @throws NoRuntimeVersionException
     */
    private suspend fun constructRuntimeInternal(
        chainId: String,
        typesUsage: TypesUsage,
    ): ConstructedRuntime = withContext(dispatcher) {
        val runtimeVersion = chainDao.runtimeInfo(chainId)?.syncedVersion ?: throw NoRuntimeVersionException

        val runtimeMetadataRaw = runCatching { runtimeFilesCache.getChainMetadata(chainId) }
            .getOrElse { throw ChainInfoNotInCacheException }

        val metadataReader = RuntimeMetadataReader.read(runtimeMetadataRaw)

        val schema = metadataReader.metadataPostV14.schema

        val typePreset = if (metadataReader.metadataVersion < 14) {
            v13Preset()
        } else {
            TypesParserV14.parse(
                lookup = metadataReader.metadata[schema.lookup],
                typePreset = v14Preset(),
                typeMapping = allSiTypeMappings()
            )
        }

        val (types, baseHash, ownHash) = when (typesUsage) {
            TypesUsage.BASE -> {
                val (types, baseHash) = constructBaseTypes(typePreset)

                Triple(types, baseHash, null)
            }
            TypesUsage.BOTH -> constructBaseAndChainTypes(chainId, runtimeVersion, typePreset)
            TypesUsage.OWN -> {
                val (types, ownHash) = constructOwnTypes(chainId, runtimeVersion, typePreset)

                Triple(types, null, ownHash)
            }
            TypesUsage.NONE -> Triple(typePreset, null, null)
        }

        // Add Pezkuwi type aliases for chains that use pezsp_* types
        val finalTypes = addPezkuwiTypeAliases(types)

        val typeRegistry = TypeRegistry(finalTypes, DynamicTypeResolver(DynamicTypeResolver.DEFAULT_COMPOUND_EXTENSIONS + GenericsExtension))

        // Store diagnostic info for error messages
        val hasExtrinsicSignature = typeRegistry["ExtrinsicSignature"] != null
        val hasAddress = typeRegistry["Address"] != null
        lastDiagnostics = "typesUsage=$typesUsage, ExtrinsicSig=$hasExtrinsicSignature, Address=$hasAddress, typeCount=${finalTypes.size}"

        val runtimeMetadata = VersionedRuntimeBuilder.buildMetadata(metadataReader, typeRegistry)

        ConstructedRuntime(
            runtime = RuntimeSnapshot(typeRegistry, runtimeMetadata),
            metadataHash = runtimeMetadataRaw.metadataContent.md5(),
            baseTypesHash = baseHash,
            ownTypesHash = ownHash,
            runtimeVersion = runtimeVersion,
            typesUsage = typesUsage
        )
    }

    private fun RuntimeMetadataReader.Companion.read(rawRuntimeMetadata: RawRuntimeMetadata): RuntimeMetadataReader {
        return if (rawRuntimeMetadata.isOpaque) {
            readOpaque(rawRuntimeMetadata.metadataContent)
        } else {
            read(rawRuntimeMetadata.metadataContent)
        }
    }

    private suspend fun constructBaseAndChainTypes(
        chainId: String,
        runtimeVersion: Int,
        initialPreset: TypePreset,
    ): Triple<TypePreset, String, String> {
        val (basePreset, baseHash) = constructBaseTypes(initialPreset)
        val (chainPreset, ownHash) = constructOwnTypes(chainId, runtimeVersion, basePreset)

        return Triple(chainPreset, baseHash, ownHash)
    }

    private suspend fun constructOwnTypes(
        chainId: String,
        runtimeVersion: Int,
        baseTypes: TypePreset,
    ): Pair<TypePreset, String> {
        val ownTypesRaw = runCatching { runtimeFilesCache.getChainTypes(chainId) }
            .getOrElse { throw ChainInfoNotInCacheException }

        val ownTypesTree = fromJson(ownTypesRaw)

        val withoutVersioning = parseBaseDefinitions(ownTypesTree, baseTypes)

        // Try to parse versioning, but if it fails (e.g., no versioning field), use base definitions
        val typePreset = try {
            parseNetworkVersioning(ownTypesTree, withoutVersioning, runtimeVersion)
        } catch (e: IllegalArgumentException) {
            withoutVersioning
        }

        return typePreset to ownTypesRaw.md5()
    }

    private suspend fun constructBaseTypes(initialPreset: TypePreset): Pair<TypePreset, String> {
        val baseTypesRaw = runCatching { runtimeFilesCache.getBaseTypes() }
            .getOrElse { throw BaseTypesNotInCacheException }

        val typePreset = parseBaseDefinitions(fromJson(baseTypesRaw), initialPreset)

        return typePreset to baseTypesRaw.md5()
    }

    private fun fromJson(types: String): TypeDefinitionsTree = gson.fromJson(types, TypeDefinitionsTree::class.java)

    // NOTE: Don't use PezkuwiExtrinsicTypeMapping here - its aliases create broken TypeReferences.
    // Instead, addPezkuwiTypeAliases() handles copying actual type instances in the RuntimeFactory.
    private fun allSiTypeMappings() = SiTypeMapping.default() + PezkuwiPathTypeMapping() + SiVoteTypeMapping()

    /**
     * For Pezkuwi chains that use pezsp_* type paths, copy the actual type instances
     * to the standard type names (Address, ExtrinsicSignature, etc.).
     *
     * Pezkuwi chains use pezsp_* prefixes instead of sp_* prefixes for their type paths.
     * This function ensures that code looking for standard type names will find the
     * correct Pezkuwi types.
     */
    private fun addPezkuwiTypeAliases(types: TypePreset): TypePreset {
        val hasPezspTypes = types.keys.any { it.startsWith("pezsp_") || it.startsWith("pezframe_") || it.startsWith("pezpallet_") }
        if (!hasPezspTypes) {
            return types
        }

        val mutableTypes = types.toMutableMap()

        // Map Pezkuwi type paths to standard type names.
        // These types are parsed as actual types from metadata (not aliased to built-ins),
        // and we copy them to standard names so existing code can find them.
        val pezkuwiTypeAliases = listOf(
            "pezsp_runtime.multiaddress.MultiAddress" to "Address",
            "pezsp_runtime.multiaddress.MultiAddress" to "MultiAddress",
            "pezsp_runtime.MultiSignature" to "ExtrinsicSignature",
            "pezsp_runtime.MultiSignature" to "MultiSignature",
            // Fee-related types
            "pezframe_support.dispatch.DispatchInfo" to "DispatchInfo",
            "pezpallet_transaction_payment.types.RuntimeDispatchInfo" to "RuntimeDispatchInfo",
            "pezframe_support.dispatch.DispatchClass" to "DispatchClass"
        )

        for ((pezspPath, standardName) in pezkuwiTypeAliases) {
            types[pezspPath]?.let { pezspType ->
                mutableTypes[standardName] = pezspType
            }
        }

        return mutableTypes
    }
}
