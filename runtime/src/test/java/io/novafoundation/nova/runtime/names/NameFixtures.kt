package io.novafoundation.nova.runtime.names

import io.novasama.substrate_sdk_android.extensions.fromHex

/** Raw `Navnisan::Names` values read from Zagros People (spec 1020024) on 2026-11-05. */
internal object NameFixtures {

    val GOV = "0x0c676f76011fc4e97a5ef275bd399ccbe8fca90e5f7a907c010663259364a9fb4b1d64d90000000000000000010000000000000002010100000000".fromHex()

    val DKS = "0x0c646b73011fc4e97a5ef275bd399ccbe8fca90e5f7a907c010663259364a9fb4b1d64d90000000000000000190000000000000002010100000000".fromHex()

    val SEROK_GOV = hex(
        "0x147365726f6bc5c21a8e620900b2c976bc0919a91167b427de09f025bb24bf46f91a39a629070100000000000000070000000000000001030201000192b5",
        "e877ed42d604c921154f456dd2effe85991d07c1577b9709beb7dee411190000",
    )

    val DARAYI_GOV = hex(
        "0x18646172617969c5c21a8e620900b2c976bc0919a91167b427de09f025bb24bf46f91a39a6290701000000000000000b00000000000000012502010001",
        "eaa3a59fbd6a4795ac240a00d00b4f6c1cc288934679cb9a1e6557be4ae972480000",
    )

    /** `Timestamp::Now` at the block the records were read from. */
    const val NOW = 0x01_a1_ac_c3_49_d0L

    private fun hex(vararg parts: String) = parts.joinToString("").fromHex()
}
