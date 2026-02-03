package io.novafoundation.nova.runtime.extrinsic.extensions

import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.extensions.FixedValueTransactionExtension

/**
 * Signed extension for PezkuwiChain that checks for non-zero sender.
 * This extension ensures the sender is not the zero address.
 *
 * In the runtime, CheckNonZeroSender is defined as:
 * pub struct CheckNonZeroSender<T>(core::marker::PhantomData<T>);
 *
 * It uses PhantomData internally, so it has no payload (empty encoding).
 */
class CheckNonZeroSender : FixedValueTransactionExtension(
    name = "CheckNonZeroSender",
    implicit = null,
    explicit = null  // PhantomData encodes to nothing
)
