package io.novafoundation.nova.runtime.extrinsic.extensions

import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.extensions.FixedValueTransactionExtension

/**
 * Signed extension that checks weight limits.
 * This extension uses PhantomData internally, so it has no payload (empty encoding).
 *
 * In the runtime, CheckWeight is defined as:
 * pub struct CheckWeight<T>(core::marker::PhantomData<T>);
 */
class CheckWeight : FixedValueTransactionExtension(
    name = "CheckWeight",
    implicit = null,
    explicit = null  // PhantomData encodes to nothing
)
