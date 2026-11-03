package io.novafoundation.nova.feature_account_impl

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import io.novafoundation.nova.common.sequrity.biometry.BiometricResponse
import io.novafoundation.nova.common.sequrity.biometry.BiometricService
import io.novafoundation.nova.common.utils.singleReplaySharedFlow
import io.novafoundation.nova.feature_account_api.domain.interfaces.AccountRepository
import io.novafoundation.nova.common.sequrity.biometry.BiometricPromptFactory
import io.novafoundation.nova.common.sequrity.biometry.BiometricServiceFactory
import kotlinx.coroutines.flow.Flow
import javax.crypto.Cipher

class RealBiometricServiceFactory(
    private val accountRepository: AccountRepository,
    private val unlockKey: BiometricUnlockKey,
) : BiometricServiceFactory {
    override fun create(
        biometricManager: BiometricManager,
        biometricPromptFactory: BiometricPromptFactory,
        promptInfo: BiometricPrompt.PromptInfo
    ): BiometricService {
        return RealBiometricService(
            accountRepository,
            biometricManager,
            biometricPromptFactory,
            promptInfo,
            unlockKey
        )
    }
}

class RealBiometricService(
    private val accountRepository: AccountRepository,
    private val biometricManager: BiometricManager,
    private val biometricPromptFactory: BiometricPromptFactory,
    private val promptInfo: BiometricPrompt.PromptInfo,
    private val unlockKey: BiometricUnlockKey,
) : BiometricPrompt.AuthenticationCallback(), BiometricService {

    override val biometryServiceResponseFlow = singleReplaySharedFlow<BiometricResponse>()

    private val biometricPrompt = biometricPromptFactory.create(this)

    // Whether the running prompt was bound to [unlockKey], so its success must come with a usable cipher
    private var promptUsesKey = false

    override fun isEnabled(): Boolean {
        return accountRepository.isBiometricEnabled()
    }

    override fun isEnabledFlow(): Flow<Boolean> = accountRepository.isBiometricEnabledFlow()

    override suspend fun toggle() {
        enableBiometry(!isEnabled())
    }

    override fun cancel() {
        biometricPrompt.cancelAuthentication()
    }

    override fun enableBiometry(enable: Boolean) {
        if (!isBiometricReady()) {
            biometryServiceResponseFlow.tryEmit(BiometricResponse.NotReady)
            return
        }

        if (enable) {
            accountRepository.setBiometricOn()
        } else {
            accountRepository.setBiometricOff()
            clearUnlockKey()
        }
    }

    override fun refreshBiometryState() {
        if (!isBiometricReady() && isEnabled()) {
            accountRepository.setBiometricOff()
            clearUnlockKey()
        }
    }

    override fun requestBiometric() {
        if (!isBiometricReady()) {
            biometryServiceResponseFlow.tryEmit(BiometricResponse.NotReady)
            return
        }

        // Weak biometrics (most face unlock) cannot use a keystore key; they keep the plain prompt
        if (biometricManager.canAuthenticate(BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) {
            promptUsesKey = false
            biometricPrompt.authenticate(promptInfo)
            return
        }

        val cipher = try {
            unlockCipher()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Biometric unlock key unusable", e)
            null
        }

        if (cipher == null) {
            biometryServiceResponseFlow.tryEmit(BiometricResponse.NotReady)
            return
        }

        promptUsesKey = true
        biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }

    override fun isBiometricReady(): Boolean {
        return biometricManager.canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }

    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
        val cancelledByUser = errorCode == BiometricPrompt.ERROR_CANCELED ||
            errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
            errorCode == BiometricPrompt.ERROR_USER_CANCELED

        biometryServiceResponseFlow.tryEmit(BiometricResponse.Error(cancelledByUser, errString.toString()))
    }

    override fun onAuthenticationFailed() {
        biometryServiceResponseFlow.tryEmit(BiometricResponse.Fail)
    }

    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
        val cipher = result.cryptoObject?.cipher
        val unlocked = when {
            cipher != null -> unlockKey.complete(cipher)
            else -> !promptUsesKey
        }

        biometryServiceResponseFlow.tryEmit(if (unlocked) BiometricResponse.Success else BiometricResponse.Fail)
    }

    /** Null when biometry was on but biometrics changed since: it has to be turned on again behind the PIN. */
    private fun unlockCipher(): Cipher? = try {
        unlockKey.cipher()
    } catch (e: KeyPermanentlyInvalidatedException) {
        clearUnlockKey()

        if (isEnabled()) {
            accountRepository.setBiometricOff()
            null
        } else {
            unlockKey.cipher()
        }
    }

    private fun clearUnlockKey() {
        runCatching { unlockKey.clear() }.onFailure { Log.w(LOG_TAG, "Cannot clear the biometric unlock key", it) }
    }

    private companion object {
        const val LOG_TAG = "BiometricService"
    }
}
