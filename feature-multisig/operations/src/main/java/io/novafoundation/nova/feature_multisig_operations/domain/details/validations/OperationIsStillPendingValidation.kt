package io.novafoundation.nova.feature_multisig_operations.domain.details.validations

import io.novafoundation.nova.common.di.scope.FeatureScope
import io.novafoundation.nova.common.validation.ValidationStatus
import io.novafoundation.nova.common.validation.isTrueOrError
import io.novafoundation.nova.feature_account_api.data.multisig.repository.MultisigValidationsRepository
import javax.inject.Inject

@FeatureScope
class OperationIsStillPendingValidation @Inject constructor(
    private val multisigValidationsRepository: MultisigValidationsRepository
) : ApproveMultisigOperationValidation {

    override suspend fun validate(value: ApproveMultisigOperationValidationPayload): ValidationStatus<ApproveMultisigOperationValidationFailure> {
        // A not-yet-submitted operation (first signer, reached via deep link before anyone has
        // signed - see PendingMultisigOperation.notYetSubmitted) has, by definition, no
        // Multisig.Multisigs entry yet - that's exactly what this submission is about to
        // create. Checking "is it still pending" only makes sense for an operation that was
        // already pending; skip it here rather than failing a legitimate first submission.
        if (!value.operation.isSubmittedOnChain) {
            return ValidationStatus.Valid()
        }

        val hasPendingCallHash = multisigValidationsRepository.hasPendingCallHash(value.chain.id, value.multisigAccountId, value.operation.callHash)

        return hasPendingCallHash isTrueOrError {
            ApproveMultisigOperationValidationFailure.TransactionIsNotAvailable
        }
    }
}
