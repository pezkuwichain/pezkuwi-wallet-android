package io.novafoundation.nova.common.utils

import io.novasama.substrate_sdk_android.extensions.asEthereumPublicKey
import io.novasama.substrate_sdk_android.extensions.toAccountId
import io.novasama.substrate_sdk_android.extensions.toHexString
import io.novasama.substrate_sdk_android.runtime.AccountId
import java.math.BigInteger

/**
 * Tron address format:
 * Base58Check(0x41 ++ accountId), where `accountId` is the 20-byte keccak256-derived id
 * (the exact same derivation Ethereum uses: keccak256(uncompressed pubkey without the 0x04 prefix)[-20:]).
 *
 * We deliberately reuse the existing Ethereum-style pubkey -> accountId derivation from `substrate_sdk_android`
 * (`asEthereumPublicKey().toAccountId()`) instead of re-implementing keccak/secp256k1 ourselves, since that math
 * is identical for Tron - only the final string encoding differs (Base58Check with a 0x41 prefix, instead of
 * checksummed hex with a 0x prefix).
 *
 * Base58Check itself (this file's [Base58]/[Base58Check] objects) is hand-implemented since no Base58 library is
 * currently on this project's classpath. It is a deterministic text encoding (not a secret-dependent cryptographic
 * primitive) and has been cross-checked against the well-known USDT-TRC20 contract address
 * (TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t <-> hex 41a614f803b6fd780986a42c78ec9c7f77e6ded13c) - see [TronAddressTest].
 */
private const val TRON_ADDRESS_PREFIX_BYTE: Byte = 0x41

object Base58 {

    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val BASE = BigInteger.valueOf(58)

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""

        var value = BigInteger(1, input)

        val sb = StringBuilder()
        while (value > BigInteger.ZERO) {
            val (div, rem) = value.divideAndRemainder(BASE)
            sb.append(ALPHABET[rem.toInt()])
            value = div
        }

        // Every leading zero byte of the input must be represented as a leading '1' in the output,
        // since a plain big-integer encoding would otherwise drop them.
        val leadingZeroBytes = input.takeWhile { it == 0.toByte() }.size
        repeat(leadingZeroBytes) { sb.append(ALPHABET[0]) }

        return sb.reverse().toString()
    }

    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)

        var value = BigInteger.ZERO
        for (char in input) {
            val digit = ALPHABET.indexOf(char)
            require(digit >= 0) { "Invalid Base58 character: '$char'" }

            value = value.multiply(BASE).add(BigInteger.valueOf(digit.toLong()))
        }

        var bytes = if (value == BigInteger.ZERO) ByteArray(0) else value.toByteArray()
        // BigInteger#toByteArray may prepend a single zero sign byte for an otherwise-positive value; drop it.
        if (bytes.size > 1 && bytes[0] == 0.toByte()) {
            bytes = bytes.copyOfRange(1, bytes.size)
        }

        val leadingOnes = input.takeWhile { it == ALPHABET[0] }.length

        return ByteArray(leadingOnes) + bytes
    }
}

object Base58Check {

    private const val CHECKSUM_SIZE = 4

    fun encode(payload: ByteArray): String {
        val checksum = payload.sha256().sha256().copyOfRange(0, CHECKSUM_SIZE)

        return Base58.encode(payload + checksum)
    }

    fun decode(input: String): ByteArray {
        val full = Base58.decode(input)
        require(full.size > CHECKSUM_SIZE) { "Base58Check payload too short: $input" }

        val payload = full.copyOfRange(0, full.size - CHECKSUM_SIZE)
        val checksum = full.copyOfRange(full.size - CHECKSUM_SIZE, full.size)

        val expectedChecksum = payload.sha256().sha256().copyOfRange(0, CHECKSUM_SIZE)
        require(checksum.contentEquals(expectedChecksum)) { "Invalid Base58Check checksum: $input" }

        return payload
    }
}

// See the file-level doc above for why it's correct to reuse the Ethereum pubkey->accountId derivation here.
fun ByteArray.tronPublicKeyToAccountId(): AccountId = asEthereumPublicKey().toAccountId().value

fun AccountId.toTronAddress(): String {
    require(size == 20) { "Tron account id must be 20 bytes, got $size" }

    return Base58Check.encode(byteArrayOf(TRON_ADDRESS_PREFIX_BYTE) + this)
}

fun String.tronAddressToAccountId(): AccountId {
    val decoded = Base58Check.decode(this)
    require(decoded.size == 21 && decoded[0] == TRON_ADDRESS_PREFIX_BYTE) { "Not a valid Tron address: $this" }

    return decoded.copyOfRange(1, decoded.size)
}

fun String.isValidTronAddress(): Boolean = runCatching { tronAddressToAccountId() }.isSuccess

fun emptyTronAccountId() = ByteArray(20) { 1 }

/**
 * Hex form of a Tron address (`0x41` prefix byte ++ accountId, hex-encoded, no `0x` prefix), e.g.
 * `41a614f803b6fd780986a42c78ec9c7f77e6ded13c`. This is the format TronGrid's `/wallet/*` transaction
 * construction/broadcast endpoints expect when called with `"visible": false` (as opposed to the human-facing
 * Base58Check form used by the `/v1/accounts/{address}` balance endpoint and by [toTronAddress]).
 */
fun AccountId.toTronHexAddress(): String {
    require(size == 20) { "Tron account id must be 20 bytes, got $size" }

    return byteArrayOf(TRON_ADDRESS_PREFIX_BYTE).toHexString(withPrefix = false) + toHexString(withPrefix = false)
}

/**
 * Converts a human-facing Base58Check Tron address (e.g. a TRC20 `contractAddress` from chain config) directly
 * into the hex form described in [toTronHexAddress].
 */
fun String.tronAddressToHexAddress(): String = tronAddressToAccountId().toTronHexAddress()
