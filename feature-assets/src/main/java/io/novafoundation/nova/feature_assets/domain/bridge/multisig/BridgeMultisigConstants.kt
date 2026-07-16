package io.novafoundation.nova.feature_assets.domain.bridge.multisig

/**
 * Everything needed to identify the USDT bridge's 3-of-5 multisig and let any of its 5 real
 * signatories renew the automation key's spending allowance directly from this app's Bridge
 * screen - a third independent channel alongside pwap-web's `/multisig/pending` and
 * pezbridge-sign.pex.mom, all three ultimately doing the same on-chain thing (a signer's own
 * wallet contributing one `Multisig.as_multi` approval), so none of them is a single point of
 * failure for "how do signers actually sign."
 *
 * See res/validators-tiki.md "USDT Bridge Custody Multisig" (not in this repo) for how these
 * were derived/verified on-chain.
 */
object BridgeMultisigConstants {

    const val WUSDT_ASSET_ID = 1000

    /** The real on-chain Assets pallet id for USDT on Polkadot Asset Hub - NOT the wallet's own
     *  internal chains.json ordinal (also confusingly "1" there; the real id lives under that
     *  entry's `typeExtras.assetId`). This constant is used for a raw runtime storage query
     *  (getPolkadotUsdtReserve), which bypasses the wallet's Chain.Asset abstraction entirely, so
     *  it needs the real protocol id. Querying "1" always returned an empty/zero balance - the
     *  multisig has never held anything at that id - which made the wUSDT->USDT reserve check
     *  report 0 permanently regardless of the multisig's real (and growing) USDT holdings. */
    const val POLKADOT_USDT_ASSET_ID = 1984

    const val MULTISIG_ADDRESS = "5GvwxmCDp3PC33KHoeWSgj3S7ocE7nzk1jiCCZMPSDBFeNcj"

    /** Same multisig account, Polkadot Asset Hub SS58 encoding - where the real USDT backing
     *  wUSDT withdrawals actually sits. Used to check a specific withdrawal amount against the
     *  real reserve (see BridgeMultisigInteractor.getPolkadotUsdtReserve) instead of the old
     *  dead-external-service boolean check this replaced (217.77.6.126:3030/status belonged to
     *  the legacy bridge bot, stopped this same session - see res/validators-tiki.md). */
    const val MULTISIG_ADDRESS_POLKADOT = "15sF76THfpefUaKomHZSpssayRbsp6Yt6ESgMrLjzJCmpe66"
    const val AUTOMATION_KEY_ADDRESS = "5GQu4PFUb1f3MTJ7i7c1CtLgDk3TVvpSW1VbQCRmfkMoC8cM"

    /** Same automation key, Polkadot Asset Hub SS58 encoding - needed to query/renew its real
     *  spending approval on that chain (BridgeMultisigInteractor.getPolkadotUsdtRemainingAllowance
     *  / submitPolkadotRenewalSignature). Confirmed on-chain (2026-07-16): unlike the Pezkuwi/
     *  wUSDT side, this approval has never been granted at all - every wUSDT->USDT withdrawal
     *  falls back to manual 3-of-5 review until the signatories grant one via the renewal flow
     *  below, same as they did for the wUSDT side. */
    const val AUTOMATION_KEY_ADDRESS_POLKADOT = "15MCCiWYSnvWnzJdfkf1M3Aq5N37CENaaWE5ZVR8DqPKNcVj"

    const val THRESHOLD = 3

    /** Single source of truth for the bridge fee - was previously duplicated as separate literals
     *  in BridgeViewModel and BridgeExecutionViewModel, both computing the same 1:1-minus-fee
     *  output. Must match usdt-bridge's own `fee_basis_points` (10 = 0.1%) server-side. */
    const val FEE_PERCENT = 0.001

    /** Hard per-transaction ceiling the automation key may ever execute on its own, regardless of
     *  how much on-chain approval remains - must match usdt-bridge's own `max_single_tx`
     *  (default_max_single_tx() in bridge_config.json). A single withdrawal/deposit above this is
     *  ALWAYS queued for manual 3-of-5 review even right after a fresh renewal (200,000 wUSDT/USDT
     *  topup), so checking the remaining allowance alone is not sufficient to predict "will this
     *  auto-pay" - both checks are needed (see BridgeViewModel.updateWarningState). */
    const val MAX_SINGLE_TX = 50_000_000_000L // 50,000 USDT (6 decimals)

    /** Renewal is offered once the remaining allowance drops below this (6 decimals). Must match
     *  pezbridge_bot_config.json's renewal_threshold and usdt-bridge's auto_pay_daily_cap sizing
     *  server-side - all three signing channels (this app, pwap-web, PezbridgeBot's Telegram
     *  alert) construct/check the SAME on-chain call, so a mismatch here doesn't just look
     *  inconsistent, it computes a different call hash and can start a second, unrelated pending
     *  multisig entry instead of contributing to the real one. */
    const val RENEWAL_THRESHOLD = 40_000_000_000L // 40,000 wUSDT

    /** The standard amount a renewal tops the allowance back up to (6 decimals). */
    const val TOPUP_AMOUNT = 200_000_000_000L // 200,000 wUSDT

    /** Same 40,000/200,000 (20%) ratio as the wUSDT side, mirrored for the Polkadot/real-USDT
     *  leg for the exact same reason: catch a draining allowance and renew it before users ever
     *  hit the "needs manual review" consent gate, rather than only reacting after the fact. The
     *  ceiling being far above the multisig's current real Polkadot reserve is intentional and
     *  harmless - transfer_approved is still bounded by the real on-chain balance underneath, an
     *  approval is unused headroom, not a promise of funds (see getPolkadotUsdtReserve). */
    const val POLKADOT_RENEWAL_THRESHOLD = 40_000_000_000L // 40,000 USDT
    const val POLKADOT_TOPUP_AMOUNT = 200_000_000_000L // 200,000 USDT

    data class Signatory(val role: String, val address: String)

    val SIGNATORIES = listOf(
        Signatory("Serok", "5EfUVZ4HXG65WuqeG24z4Pmt11cNQTUacR3CKymKcsv1UTFU"),
        Signatory("SerokiMeclise", "5CrB5BWJfLNWEZAsAXDKXdJUGzFMXKvYnwRX4DVMcgBwxSdx"),
        Signatory("Xezinedar", "5GipBJs2uNWTCazyZQ2vG3DEqLz4tXNmNZtBAT1Mtm1orZ5i"),
        Signatory("Berdevk", "5HWFZbhkZuTUySXu6ZXYKrTHBnWXHvWRKLozE22zhnwXGGxk"),
        Signatory("Noter", "5ELgySrX5ZyK7EWXjj6bAedyTCcTNWDANbiiipsT5gnpoCEp"),
    )
}
