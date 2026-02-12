package io.novafoundation.nova.runtime.extrinsic.extensions

import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.extensions.FixedValueTransactionExtension

/**
 * Signed extension for PezkuwiChain that handles weight reclamation.
 * This extension reclaims unused weight after transaction execution.
 *
 * In the runtime, WeightReclaim is defined as:
 * pub struct WeightReclaim<T>(core::marker::PhantomData<T>);
 *
 * It uses PhantomData internally, so it has no payload (empty encoding).
 */
class WeightReclaim : FixedValueTransactionExtension(
    name = "WeightReclaim",
    implicit = null,
    explicit = null // PhantomData encodes to nothing
)
