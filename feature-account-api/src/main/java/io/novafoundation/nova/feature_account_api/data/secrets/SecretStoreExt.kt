package io.novafoundation.nova.feature_account_api.data.secrets

import io.novafoundation.nova.common.data.secrets.v2.AccountSecrets
import io.novafoundation.nova.common.data.secrets.v2.ChainAccountSecrets
import io.novafoundation.nova.common.data.secrets.v2.MetaAccountSecrets
import io.novafoundation.nova.common.data.secrets.v2.SecretStoreV2
import io.novafoundation.nova.common.data.secrets.v2.getAccountSecrets
import io.novafoundation.nova.common.data.secrets.v2.mapChainAccountSecretsToKeypair
import io.novafoundation.nova.common.data.secrets.v2.mapMetaAccountSecretsToDerivationPath
import io.novafoundation.nova.common.data.secrets.v2.mapMetaAccountSecretsToKeypair
import io.novafoundation.nova.common.utils.fold
import io.novasama.substrate_sdk_android.encrypt.keypair.Keypair
import io.novafoundation.nova.feature_account_api.domain.model.MetaAccount
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novasama.substrate_sdk_android.scale.EncodableStruct

suspend fun SecretStoreV2.getAccountSecrets(
    metaAccount: MetaAccount,
    chain: Chain
): AccountSecrets {
    val accountId = metaAccount.accountIdIn(chain) ?: error("No account for chain $chain in meta account ${metaAccount.name}")

    return getAccountSecrets(metaAccount.id, accountId)
}

fun AccountSecrets.keypair(chain: Chain): Keypair {
    return fold(
        // `tron` was missing here already (found while adding `solana`) - without it, exporting a
        // Tron account's private key from this call site would have silently returned the wrong
        // (Ethereum) keypair instead of the Tron-specific one. Fixed alongside, not a separate change.
        left = {
            mapMetaAccountSecretsToKeypair(
                it,
                ethereum = chain.isEthereumBased,
                tron = chain.isTronBased,
                bitcoin = chain.isBitcoinBased,
                solana = chain.isSolanaBased,
            )
        },
        right = { mapChainAccountSecretsToKeypair(it) }
    )
}

fun AccountSecrets.derivationPath(chain: Chain): String? {
    return fold(
        left = { mapMetaAccountSecretsToDerivationPath(it, ethereum = chain.isEthereumBased, bitcoin = chain.isBitcoinBased) },
        right = { it[ChainAccountSecrets.DerivationPath] }
    )
}

fun EncodableStruct<MetaAccountSecrets>.keypair(ethereum: Boolean): Keypair {
    return mapMetaAccountSecretsToKeypair(this, ethereum)
}
