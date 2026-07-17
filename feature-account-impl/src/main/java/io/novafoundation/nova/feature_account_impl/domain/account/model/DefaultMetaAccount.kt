package io.novafoundation.nova.feature_account_impl.domain.account.model

import io.novafoundation.nova.core.model.CryptoType
import io.novafoundation.nova.feature_account_api.domain.model.LightMetaAccount
import io.novafoundation.nova.feature_account_api.domain.model.MetaAccount
import io.novafoundation.nova.feature_account_api.domain.model.hasChainAccountIn
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novafoundation.nova.runtime.multiNetwork.chain.model.ChainId
import io.novasama.substrate_sdk_android.runtime.AccountId

open class DefaultMetaAccount(
    override val id: Long,
    override val globallyUniqueId: String,
    override val substratePublicKey: ByteArray?,
    override val substrateCryptoType: CryptoType?,
    override val substrateAccountId: ByteArray?,
    override val ethereumAddress: ByteArray?,
    override val ethereumPublicKey: ByteArray?,
    override val isSelected: Boolean,
    override val name: String,
    override val type: LightMetaAccount.Type,
    override val status: LightMetaAccount.Status,
    override val chainAccounts: Map<ChainId, MetaAccount.ChainAccount>,
    override val parentMetaId: Long?,
    override val tronAddress: ByteArray? = null,
    override val tronPublicKey: ByteArray? = null,
    override val bitcoinAddress: ByteArray? = null,
    override val bitcoinPublicKey: ByteArray? = null,
    override val solanaAddress: ByteArray? = null,
    override val solanaPublicKey: ByteArray? = null,
) : MetaAccount {

    override suspend fun supportsAddingChainAccount(chain: Chain): Boolean {
        return true
    }

    override fun hasAccountIn(chain: Chain): Boolean {
        return when {
            hasChainAccountIn(chain.id) -> true
            chain.isTronBased -> tronAddress != null
            chain.isBitcoinBased -> bitcoinAddress != null
            chain.isSolanaBased -> solanaAddress != null
            chain.isEthereumBased -> ethereumAddress != null
            else -> substrateAccountId != null
        }
    }

    override fun accountIdIn(chain: Chain): AccountId? {
        return when {
            hasChainAccountIn(chain.id) -> chainAccounts.getValue(chain.id).accountId
            chain.isTronBased -> tronAddress
            chain.isBitcoinBased -> bitcoinAddress
            chain.isSolanaBased -> solanaAddress
            chain.isEthereumBased -> ethereumAddress
            else -> substrateAccountId
        }
    }

    override fun publicKeyIn(chain: Chain): ByteArray? {
        return when {
            hasChainAccountIn(chain.id) -> chainAccounts.getValue(chain.id).publicKey
            chain.isTronBased -> tronPublicKey
            chain.isBitcoinBased -> bitcoinPublicKey
            chain.isSolanaBased -> solanaPublicKey
            chain.isEthereumBased -> ethereumPublicKey
            else -> substratePublicKey
        }
    }
}
