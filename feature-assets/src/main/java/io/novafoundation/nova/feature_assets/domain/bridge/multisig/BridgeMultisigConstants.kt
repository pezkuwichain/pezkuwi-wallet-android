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
    const val POLKADOT_USDT_ASSET_ID = 1

    const val MULTISIG_ADDRESS = "5GvwxmCDp3PC33KHoeWSgj3S7ocE7nzk1jiCCZMPSDBFeNcj"
    /** Same multisig account, Polkadot Asset Hub SS58 encoding - where the real USDT backing
     *  wUSDT withdrawals actually sits. Used to check a specific withdrawal amount against the
     *  real reserve (see BridgeMultisigInteractor.getPolkadotUsdtReserve) instead of the old
     *  dead-external-service boolean check this replaced (217.77.6.126:3030/status belonged to
     *  the legacy bridge bot, stopped this same session - see res/validators-tiki.md). */
    const val MULTISIG_ADDRESS_POLKADOT = "15sF76THfpefUaKomHZSpssayRbsp6Yt6ESgMrLjzJCmpe66"
    const val AUTOMATION_KEY_ADDRESS = "5GQu4PFUb1f3MTJ7i7c1CtLgDk3TVvpSW1VbQCRmfkMoC8cM"

    const val THRESHOLD = 3

    /** Renewal is offered once the remaining allowance drops below this (6 decimals). Must match
     *  pezbridge_bot_config.json's renewal_threshold and usdt-bridge's auto_pay_daily_cap sizing
     *  server-side - all three signing channels (this app, pwap-web, PezbridgeBot's Telegram
     *  alert) construct/check the SAME on-chain call, so a mismatch here doesn't just look
     *  inconsistent, it computes a different call hash and can start a second, unrelated pending
     *  multisig entry instead of contributing to the real one. */
    const val RENEWAL_THRESHOLD = 40_000_000_000L // 40,000 wUSDT

    /** The standard amount a renewal tops the allowance back up to (6 decimals). */
    const val TOPUP_AMOUNT = 200_000_000_000L // 200,000 wUSDT

    data class Signatory(val role: String, val address: String)

    val SIGNATORIES = listOf(
        Signatory("Serok", "5EfUVZ4HXG65WuqeG24z4Pmt11cNQTUacR3CKymKcsv1UTFU"),
        Signatory("SerokiMeclise", "5CrB5BWJfLNWEZAsAXDKXdJUGzFMXKvYnwRX4DVMcgBwxSdx"),
        Signatory("Xezinedar", "5GipBJs2uNWTCazyZQ2vG3DEqLz4tXNmNZtBAT1Mtm1orZ5i"),
        Signatory("Berdevk", "5HWFZbhkZuTUySXu6ZXYKrTHBnWXHvWRKLozE22zhnwXGGxk"),
        Signatory("Noter", "5ELgySrX5ZyK7EWXjj6bAedyTCcTNWDANbiiipsT5gnpoCEp"),
    )
}
