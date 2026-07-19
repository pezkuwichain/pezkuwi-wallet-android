package io.novafoundation.nova.core_db.model.chain.account

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.novafoundation.nova.core.model.CryptoType
import io.novafoundation.nova.core_db.model.common.SerializedJson
import java.util.UUID

/*
    TODO: on next migration please add following changes:
     - Foreign key for parentMetaId to remove proxy meta account automatically when proxied is deleted
     - Foreign key to ProxyAccountLocal to remove proxies meta accounts automatically when chain is deleted
 */
// NB!: We intentionally do not make MetaAccountLocal a data-class since it is easy to misuse copy due to value of `id` is not being copied
// All copy-like methods should be implemented explicitly, like `addEvmAccount`
@Entity(
    tableName = MetaAccountLocal.TABLE_NAME,
    indices = [
        Index(value = ["substrateAccountId"]),
        Index(value = ["ethereumAddress"])
    ]
)
class MetaAccountLocal(
    val substratePublicKey: ByteArray?,
    val substrateCryptoType: CryptoType?,
    val substrateAccountId: ByteArray?,
    val ethereumPublicKey: ByteArray?,
    val ethereumAddress: ByteArray?,
    val name: String,
    val parentMetaId: Long?,
    val isSelected: Boolean,
    val position: Int,
    val type: Type,
    @ColumnInfo(defaultValue = "ACTIVE")
    val status: Status,
    val globallyUniqueId: String,
    val typeExtras: SerializedJson?,
    val tronPublicKey: ByteArray? = null,
    val tronAddress: ByteArray? = null,
    val bitcoinPublicKey: ByteArray? = null,
    val bitcoinAddress: ByteArray? = null,
    val solanaPublicKey: ByteArray? = null,
    val solanaAddress: ByteArray? = null,
) {

    enum class Status {
        ACTIVE, DEACTIVATED
    }

    companion object Table {
        const val TABLE_NAME = "meta_accounts"

        object Column {
            const val SUBSTRATE_PUBKEY = "substratePublicKey"
            const val SUBSTRATE_CRYPTO_TYPE = "substrateCryptoType"
            const val SUBSTRATE_ACCOUNT_ID = "substrateAccountId"

            const val ETHEREUM_PUBKEY = "ethereumPublicKey"
            const val ETHEREUM_ADDRESS = "ethereumAddress"

            const val TRON_PUBKEY = "tronPublicKey"
            const val TRON_ADDRESS = "tronAddress"

            const val BITCOIN_PUBKEY = "bitcoinPublicKey"
            const val BITCOIN_ADDRESS = "bitcoinAddress"

            const val SOLANA_PUBKEY = "solanaPublicKey"
            const val SOLANA_ADDRESS = "solanaAddress"

            const val NAME = "name"
            const val IS_SELECTED = "isSelected"
            const val POSITION = "position"
            const val ID = "id"
        }

        fun generateGloballyUniqueId(): String {
            return UUID.randomUUID().toString()
        }
    }

    // We do not use copy as we need explicitly set id
    fun addEvmAccount(
        ethereumPublicKey: ByteArray,
        ethereumAddress: ByteArray,
    ): MetaAccountLocal {
        return MetaAccountLocal(
            substratePublicKey = substratePublicKey,
            substrateCryptoType = substrateCryptoType,
            substrateAccountId = substrateAccountId,
            ethereumPublicKey = ethereumPublicKey,
            ethereumAddress = ethereumAddress,
            name = name,
            parentMetaId = parentMetaId,
            isSelected = isSelected,
            position = position,
            type = type,
            status = status,
            globallyUniqueId = globallyUniqueId,
            typeExtras = typeExtras,
            tronPublicKey = tronPublicKey,
            tronAddress = tronAddress,
            bitcoinPublicKey = bitcoinPublicKey,
            bitcoinAddress = bitcoinAddress,
            solanaPublicKey = solanaPublicKey,
            solanaAddress = solanaAddress,
        ).also {
            it.id = id
        }
    }

    // We do not use copy as we need explicitly set id
    fun addBitcoinAccount(
        bitcoinPublicKey: ByteArray,
        bitcoinAddress: ByteArray,
    ): MetaAccountLocal {
        return MetaAccountLocal(
            substratePublicKey = substratePublicKey,
            substrateCryptoType = substrateCryptoType,
            substrateAccountId = substrateAccountId,
            ethereumPublicKey = ethereumPublicKey,
            ethereumAddress = ethereumAddress,
            name = name,
            parentMetaId = parentMetaId,
            isSelected = isSelected,
            position = position,
            type = type,
            status = status,
            globallyUniqueId = globallyUniqueId,
            typeExtras = typeExtras,
            tronPublicKey = tronPublicKey,
            tronAddress = tronAddress,
            bitcoinPublicKey = bitcoinPublicKey,
            bitcoinAddress = bitcoinAddress,
            solanaPublicKey = solanaPublicKey,
            solanaAddress = solanaAddress,
        ).also {
            it.id = id
        }
    }

    // We do not use copy as we need explicitly set id
    fun addTronAccount(
        tronPublicKey: ByteArray,
        tronAddress: ByteArray,
    ): MetaAccountLocal {
        return MetaAccountLocal(
            substratePublicKey = substratePublicKey,
            substrateCryptoType = substrateCryptoType,
            substrateAccountId = substrateAccountId,
            ethereumPublicKey = ethereumPublicKey,
            ethereumAddress = ethereumAddress,
            name = name,
            parentMetaId = parentMetaId,
            isSelected = isSelected,
            position = position,
            type = type,
            status = status,
            globallyUniqueId = globallyUniqueId,
            typeExtras = typeExtras,
            tronPublicKey = tronPublicKey,
            tronAddress = tronAddress,
            // Previously missing - dropped bitcoin/solana on any account already carrying them. Since
            // this migration is deliberately unconditional/self-healing (re-runs every app start to
            // recover from a since-fixed bug re-losing a keypair), a re-run after Bitcoin/Solana were
            // already backfilled would silently wipe them back to null. See addBitcoinAccount/
            // addSolanaAccount - every addXAccount must carry every sibling chain-family field forward.
            bitcoinPublicKey = bitcoinPublicKey,
            bitcoinAddress = bitcoinAddress,
            solanaPublicKey = solanaPublicKey,
            solanaAddress = solanaAddress,
        ).also {
            it.id = id
        }
    }

    // We do not use copy as we need explicitly set id
    fun addSolanaAccount(
        solanaPublicKey: ByteArray,
        solanaAddress: ByteArray,
    ): MetaAccountLocal {
        return MetaAccountLocal(
            substratePublicKey = substratePublicKey,
            substrateCryptoType = substrateCryptoType,
            substrateAccountId = substrateAccountId,
            ethereumPublicKey = ethereumPublicKey,
            ethereumAddress = ethereumAddress,
            name = name,
            parentMetaId = parentMetaId,
            isSelected = isSelected,
            position = position,
            type = type,
            status = status,
            globallyUniqueId = globallyUniqueId,
            typeExtras = typeExtras,
            tronPublicKey = tronPublicKey,
            tronAddress = tronAddress,
            bitcoinPublicKey = bitcoinPublicKey,
            bitcoinAddress = bitcoinAddress,
            solanaPublicKey = solanaPublicKey,
            solanaAddress = solanaAddress,
        ).also {
            it.id = id
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MetaAccountLocal) return false

        if (id != other.id) return false

        if (!substratePublicKey.contentEquals(other.substratePublicKey)) return false
        if (substrateCryptoType != other.substrateCryptoType) return false
        if (!substrateAccountId.contentEquals(other.substrateAccountId)) return false
        if (!ethereumPublicKey.contentEquals(other.ethereumPublicKey)) return false
        if (!ethereumAddress.contentEquals(other.ethereumAddress)) return false
        if (!tronPublicKey.contentEquals(other.tronPublicKey)) return false
        if (!tronAddress.contentEquals(other.tronAddress)) return false
        if (!bitcoinPublicKey.contentEquals(other.bitcoinPublicKey)) return false
        if (!bitcoinAddress.contentEquals(other.bitcoinAddress)) return false
        if (!solanaPublicKey.contentEquals(other.solanaPublicKey)) return false
        if (!solanaAddress.contentEquals(other.solanaAddress)) return false
        if (name != other.name) return false
        if (parentMetaId != other.parentMetaId) return false
        if (isSelected != other.isSelected) return false
        if (position != other.position) return false
        if (type != other.type) return false
        if (status != other.status) return false
        if (globallyUniqueId != other.globallyUniqueId) return false
        if (typeExtras != other.typeExtras) return false

        return true
    }

    override fun hashCode(): Int {
        var result = substratePublicKey?.contentHashCode() ?: 0
        result = 31 * result + id.hashCode()
        result = 31 * result + (substrateCryptoType?.hashCode() ?: 0)
        result = 31 * result + (substrateAccountId?.contentHashCode() ?: 0)
        result = 31 * result + (ethereumPublicKey?.contentHashCode() ?: 0)
        result = 31 * result + (ethereumAddress?.contentHashCode() ?: 0)
        result = 31 * result + (tronPublicKey?.contentHashCode() ?: 0)
        result = 31 * result + (tronAddress?.contentHashCode() ?: 0)
        result = 31 * result + (bitcoinPublicKey?.contentHashCode() ?: 0)
        result = 31 * result + (bitcoinAddress?.contentHashCode() ?: 0)
        result = 31 * result + (solanaPublicKey?.contentHashCode() ?: 0)
        result = 31 * result + (solanaAddress?.contentHashCode() ?: 0)
        result = 31 * result + name.hashCode()
        result = 31 * result + (parentMetaId?.hashCode() ?: 0)
        result = 31 * result + isSelected.hashCode()
        result = 31 * result + position
        result = 31 * result + type.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + globallyUniqueId.hashCode()
        result = 31 * result + (typeExtras?.hashCode() ?: 0)
        result = 31 * result + id.hashCode()
        return result
    }

    @PrimaryKey(autoGenerate = true)
    var id: Long = 0

    enum class Type {
        SECRETS,
        WATCH_ONLY,
        PARITY_SIGNER,

        // We did not rename LEDGER -> LEDGER_LEGACY as in domain to avoid writing a migration
        LEDGER,
        LEDGER_GENERIC,
        POLKADOT_VAULT,
        PROXIED,
        MULTISIG
    }
}

class MetaAccountPositionUpdate(
    val id: Long,
    val position: Int
)

data class MetaAccountIdWithType(
    val id: Long,
    val type: MetaAccountLocal.Type
)
