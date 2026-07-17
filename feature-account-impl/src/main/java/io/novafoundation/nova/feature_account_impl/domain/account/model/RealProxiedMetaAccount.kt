package io.novafoundation.nova.feature_account_impl.domain.account.model

import io.novafoundation.nova.core.model.CryptoType
import io.novafoundation.nova.feature_account_api.domain.model.LightMetaAccount
import io.novafoundation.nova.feature_account_api.domain.model.MetaAccount
import io.novafoundation.nova.feature_account_api.domain.model.ProxiedMetaAccount
import io.novafoundation.nova.feature_account_api.domain.model.ProxyAccount
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novafoundation.nova.runtime.multiNetwork.chain.model.ChainId

internal class RealProxiedMetaAccount(
    id: Long,
    globallyUniqueId: String,
    substratePublicKey: ByteArray?,
    substrateCryptoType: CryptoType?,
    substrateAccountId: ByteArray?,
    ethereumAddress: ByteArray?,
    ethereumPublicKey: ByteArray?,
    isSelected: Boolean,
    name: String,
    status: LightMetaAccount.Status,
    override val proxy: ProxyAccount,
    chainAccounts: Map<ChainId, MetaAccount.ChainAccount>,
    parentMetaId: Long?,
    tronAddress: ByteArray? = null,
    tronPublicKey: ByteArray? = null,
    bitcoinAddress: ByteArray? = null,
    bitcoinPublicKey: ByteArray? = null,
    solanaAddress: ByteArray? = null,
    solanaPublicKey: ByteArray? = null,
) : DefaultMetaAccount(
    id = id,
    globallyUniqueId = globallyUniqueId,
    substratePublicKey = substratePublicKey,
    substrateCryptoType = substrateCryptoType,
    substrateAccountId = substrateAccountId,
    ethereumAddress = ethereumAddress,
    ethereumPublicKey = ethereumPublicKey,
    isSelected = isSelected,
    name = name,
    type = LightMetaAccount.Type.PROXIED,
    status = status,
    chainAccounts = chainAccounts,
    parentMetaId = parentMetaId,
    tronAddress = tronAddress,
    tronPublicKey = tronPublicKey,
    bitcoinAddress = bitcoinAddress,
    bitcoinPublicKey = bitcoinPublicKey,
    solanaAddress = solanaAddress,
    solanaPublicKey = solanaPublicKey
),
    ProxiedMetaAccount {

    override suspend fun supportsAddingChainAccount(chain: Chain): Boolean {
        // User cannot manually add accounts to proxy meta account
        return false
    }
}
