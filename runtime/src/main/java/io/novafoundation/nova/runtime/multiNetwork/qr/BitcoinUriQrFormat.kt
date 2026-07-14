package io.novafoundation.nova.runtime.multiNetwork.qr

import io.novasama.substrate_sdk_android.encrypt.qr.PublicQrFormat
import io.novasama.substrate_sdk_android.encrypt.qr.QrFormat

private const val BITCOIN_URI_SCHEME = "bitcoin:"

/**
 * BIP21 URI (`bitcoin:<address>?amount=...&label=...`) - the standard QR payload most wallets/exchanges generate
 * for a Bitcoin receive address. Neither `SubstrateQrFormat` (expects a `substrate:<address>:<pubkey>` triple)
 * nor `AddressQrFormat` (treats the whole QR content as a bare address) recognize this, so scanning an external
 * wallet's or exchange's BTC address QR failed outright with the generic "QR can't be decoded" error - confirmed
 * live against a real BTC withdrawal QR.
 */
class BitcoinUriQrFormat(
    private val addressValidator: (String) -> Boolean
) : PublicQrFormat {

    override fun encode(payload: PublicQrFormat.Payload): String {
        return "$BITCOIN_URI_SCHEME${payload.address}"
    }

    override fun decode(qrContent: String): PublicQrFormat.Payload {
        if (!qrContent.startsWith(BITCOIN_URI_SCHEME, ignoreCase = true)) {
            throw QrFormat.InvalidFormatException("Not a bitcoin: URI")
        }

        val address = qrContent.substring(BITCOIN_URI_SCHEME.length).substringBefore('?')

        return if (addressValidator(address)) {
            PublicQrFormat.Payload(address = address)
        } else {
            throw QrFormat.InvalidFormatException("Supplied bitcoin: URI has an invalid address")
        }
    }
}
