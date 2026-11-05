package io.novafoundation.nova.runtime.names

import io.novasama.substrate_sdk_android.hash.Hasher.blake2b256

/**
 * A `.kurd` name in the canonical form the Navnisan pallet keys records by.
 *
 * The chain does not normalise: it accepts canonical bytes or refuses them. So the wallet lower-cases
 * ASCII first and then applies the pallet's label rules (`label::validate`, `Use::Sub`); input that
 * fails them is never looked up anywhere, and in particular never handed to a search engine.
 */
class KurdName private constructor(
    /** Labels from the leftmost down to the one directly under `.kurd`: `dks.gov.kurd` is `[dks, gov]`. */
    val labels: List<String>,
) {

    val name: String = labels.joinToString(".") + SUFFIX

    /** The top-level label: `gov` for `dks.gov.kurd`. */
    val top: String get() = labels.last()

    /** `node(name)`, as `pezpallet_navnisan::Pallet::node_of` computes it. */
    val node: ByteArray by lazy { labels.foldRight(KURD_NODE) { label, parent -> childNode(parent, label) } }

    /** Nodes of this name and each ancestor, this name first, the top-level name last. */
    fun nodesUpward(): List<ByteArray> = labels.indices.map { from -> KurdName(labels.drop(from)).node }

    override fun equals(other: Any?) = other is KurdName && other.name == name

    override fun hashCode() = name.hashCode()

    override fun toString() = name

    companion object {

        const val SUFFIX = ".kurd"

        const val MAX_LABEL = 63
        const val MAX_DEPTH = 4
        const val MAX_NAME = 253

        /** `node("kurd")`: never stored, every top-level name's parent. */
        val KURD_NODE: ByteArray = (ByteArray(32) + "kurd".toByteArray().blake2b256()).blake2b256()

        /** True for `kurd` and any host under it, valid name or not: none of them may reach system DNS. */
        fun isKurdHost(host: String?): Boolean {
            val normalized = host?.lowercase(java.util.Locale.ROOT)?.trimEnd('.') ?: return false
            return normalized == "kurd" || normalized.endsWith(SUFFIX)
        }

        /**
         * True when [input] names a `.kurd` host, valid name or not, with or without a scheme or path:
         * such input is never treated as a web address or a search query.
         */
        fun mentionsKurd(input: String): Boolean {
            val afterScheme = input.trim().substringAfter("://")
            val authority = afterScheme.takeWhile { it !in "/?#" }
            val host = authority.substringAfterLast('@').substringBefore(':')
            return isKurdHost(host)
        }

        /**
         * The name in [input], or null when it is not a valid `.kurd` name. Accepts surrounding
         * whitespace, a trailing dot or slash and an `https://` prefix; anything else around the
         * name (a path, a port, user info) makes it invalid rather than being ignored.
         */
        fun parse(input: String): KurdName? {
            var text = input.trim()
            if (text.startsWith("https://", ignoreCase = true)) text = text.substring("https://".length)
            text = text.trimEnd('/').trimEnd('.')
            if (text.any { it.code > 0x7f }) return null
            text = text.lowercase(java.util.Locale.ROOT)

            if (text.length > MAX_NAME || !text.endsWith(SUFFIX)) return null
            val labels = text.removeSuffix(SUFFIX).split('.')
            if (labels.size > MAX_DEPTH || !labels.all(::isValidLabel)) return null

            return KurdName(labels)
        }

        /** `a-z`, `0-9` and `-`, 1 to 63 bytes, no hyphen at either end and no `--` anywhere. */
        fun isValidLabel(label: String): Boolean {
            if (label.isEmpty() || label.length > MAX_LABEL) return false
            if (!label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) return false
            if (label.first() == '-' || label.last() == '-') return false
            return "--" !in label
        }

        fun childNode(parent: ByteArray, label: String): ByteArray = (parent + label.toByteArray().blake2b256()).blake2b256()
    }
}
