package io.novafoundation.nova.runtime.extrinsic.extensions

import io.novasama.substrate_sdk_android.runtime.extrinsic.v5.transactionExtension.extensions.FixedValueTransactionExtension

/**
 * Signed extension for PezkuwiChain that authorizes calls.
 * This extension uses PhantomData internally, so it has no payload (empty encoding).
 *
 * In the runtime, AuthorizeCall is defined as:
 * pub struct AuthorizeCall<T>(core::marker::PhantomData<T>);
 *
 * It's placed first in the TxExtension tuple for PezkuwiChain.
 */
class AuthorizeCall : FixedValueTransactionExtension(
    name = "AuthorizeCall",
    implicit = null,
    explicit = null // PhantomData encodes to nothing
)
