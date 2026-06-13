package io.novafoundation.nova.feature_assets.presentation.balance.common.holders

import androidx.core.view.isVisible
import coil.ImageLoader
import io.novafoundation.nova.common.list.GroupedListHolder
import io.novafoundation.nova.common.presentation.masking.setMaskableText
import io.novafoundation.nova.common.utils.recyclerView.expandable.ExpandableChildViewHolder
import io.novafoundation.nova.common.utils.recyclerView.expandable.items.ExpandableChildItem
import io.novafoundation.nova.feature_account_api.presenatation.chain.loadChainIcon
import io.novafoundation.nova.feature_account_api.presenatation.chain.setTokenIcon
import io.novafoundation.nova.feature_assets.databinding.ItemTokenAssetBinding
import io.novafoundation.nova.feature_assets.presentation.balance.common.BalanceListAdapter
import io.novafoundation.nova.feature_assets.presentation.balance.list.model.items.TokenAssetUi
import io.novafoundation.nova.feature_assets.presentation.model.AssetModel
import io.novafoundation.nova.feature_wallet_api.presentation.model.maskableFiat
import io.novafoundation.nova.feature_wallet_api.presentation.model.maskableToken
import io.novafoundation.nova.runtime.ext.Geneses
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain

// Fungible assets on Pezkuwi Asset Hub follow the PEZ-20 token standard.
private val PEZ20_SYMBOLS = setOf("PEZ", "USDT", "wUSDT")

class TokenAssetViewHolder(
    private val binder: ItemTokenAssetBinding,
    private val imageLoader: ImageLoader,
) : GroupedListHolder(binder.root), ExpandableChildViewHolder {

    override var expandableItem: ExpandableChildItem? = null

    fun bind(tokenAsset: TokenAssetUi, itemHandler: BalanceListAdapter.ItemAssetHandler) = with(containerView) {
        updateExpandableItem(tokenAsset)

        val asset = tokenAsset.asset
        binder.itemTokenAssetImage.setTokenIcon(tokenAsset.assetIcon, imageLoader)
        binder.itemTokenAssetChainIcon.loadChainIcon(tokenAsset.chain.icon, imageLoader)
        binder.itemTokenAssetChainName.text = tokenAsset.chain.name

        bindTotal(asset)

        val config = asset.token.configuration
        binder.itemTokenAssetToken.text = config.symbol.value

        val isPez20 = config.chainId == Chain.Geneses.PEZKUWI_ASSET_HUB && config.symbol.value in PEZ20_SYMBOLS
        binder.itemTokenAssetPez20.isVisible = isPez20
        if (isPez20) binder.itemTokenAssetPez20.text = "PEZ-20"

        setOnClickListener { itemHandler.assetClicked(asset.token.configuration) }
    }

    fun bindTotal(asset: AssetModel) {
        binder.itemTokenAssetBalance.setMaskableText(asset.amount.maskableToken())
        binder.itemTokenAssetPriceAmount.setMaskableText(asset.amount.maskableFiat())
    }
}
