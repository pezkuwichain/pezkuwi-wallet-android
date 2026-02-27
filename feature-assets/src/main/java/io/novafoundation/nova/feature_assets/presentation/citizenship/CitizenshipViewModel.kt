package io.novafoundation.nova.feature_assets.presentation.citizenship

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.novafoundation.nova.common.base.BaseViewModel
import io.novafoundation.nova.common.resources.ResourceManager
import io.novafoundation.nova.common.utils.Event
import io.novafoundation.nova.feature_account_api.data.ethereum.transaction.TransactionOrigin
import io.novafoundation.nova.feature_account_api.data.extrinsic.ExtrinsicService
import io.novafoundation.nova.feature_account_api.domain.interfaces.SelectedAccountUseCase
import io.novafoundation.nova.feature_account_api.domain.model.addressIn
import io.novafoundation.nova.feature_assets.R
import io.novafoundation.nova.feature_assets.data.repository.PendingApproval
import io.novafoundation.nova.feature_assets.data.repository.PezkuwiDashboardRepository
import io.novafoundation.nova.runtime.ext.ChainGeneses
import io.novafoundation.nova.runtime.multiNetwork.ChainRegistry
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novasama.substrate_sdk_android.runtime.extrinsic.call
import io.novasama.substrate_sdk_android.ss58.SS58Encoder.toAccountId
import kotlinx.coroutines.launch
import org.bouncycastle.jcajce.provider.digest.Keccak
import java.math.BigInteger

enum class CitizenshipStatus {
    NOT_STARTED,
    PENDING_REFERRAL,
    REFERRER_APPROVED,
    APPROVED,
    LOADING
}

class CitizenshipViewModel(
    private val extrinsicService: ExtrinsicService,
    private val chainRegistry: ChainRegistry,
    private val selectedAccountUseCase: SelectedAccountUseCase,
    private val resourceManager: ResourceManager,
    private val pezkuwiDashboardRepository: PezkuwiDashboardRepository
) : BaseViewModel() {

    private val _citizenshipStatus = MutableLiveData(CitizenshipStatus.LOADING)
    val citizenshipStatus: LiveData<CitizenshipStatus> = _citizenshipStatus

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _dismissEvent = MutableLiveData<Event<Unit>>()
    val dismissEvent: LiveData<Event<Unit>> = _dismissEvent

    private val _shareEvent = MutableLiveData<Event<String>>()
    val shareEvent: LiveData<Event<String>> = _shareEvent

    private val _pendingApprovals = MutableLiveData<List<PendingApproval>>(emptyList())
    val pendingApprovals: LiveData<List<PendingApproval>> = _pendingApprovals

    private var peopleChain: Chain? = null
    private var cachedAccountId: ByteArray? = null

    companion object {
        private val MIN_BALANCE_PLANCK = BigInteger("1100000000000") // 1.1 HEZ
        private const val TAG = "CitizenshipVM"
    }

    init {
        loadStatus()
    }

    private fun loadStatus() {
        launch {
            try {
                val chain = chainRegistry.getChain(ChainGeneses.PEZKUWI_PEOPLE)
                peopleChain = chain

                val metaAccount = selectedAccountUseCase.getSelectedMetaAccount()
                val accountId = metaAccount.accountIdIn(chain) ?: run {
                    _citizenshipStatus.postValue(CitizenshipStatus.NOT_STARTED)
                    return@launch
                }
                cachedAccountId = accountId

                val status = queryKycStatus(accountId)
                Log.d(TAG, "KYC status loaded: $status")

                // Balance check only for new applications — don't block sign/approve
                if (status == CitizenshipStatus.NOT_STARTED) {
                    val freeBalance = pezkuwiDashboardRepository.queryFreeBalance(ChainGeneses.PEZKUWI_PEOPLE, accountId)
                    if (freeBalance < MIN_BALANCE_PLANCK) {
                        showError(resourceManager.getString(R.string.citizenship_insufficient_balance))
                        _dismissEvent.postValue(Event(Unit))
                        return@launch
                    }
                }

                _citizenshipStatus.postValue(status)

                if (status == CitizenshipStatus.APPROVED) {
                    loadPendingApprovals(chain, accountId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load KYC status", e)
                _citizenshipStatus.postValue(CitizenshipStatus.NOT_STARTED)
            }
        }
    }

    private suspend fun queryKycStatus(accountId: ByteArray): CitizenshipStatus {
        return try {
            pezkuwiDashboardRepository.queryKycStatus(ChainGeneses.PEZKUWI_PEOPLE, accountId)
        } catch (e: Exception) {
            Log.e(TAG, "queryKycStatus failed", e)
            CitizenshipStatus.NOT_STARTED
        }
    }

    fun submitApplication(
        name: String,
        fatherName: String,
        grandfatherName: String,
        motherName: String,
        tribe: String,
        region: String,
        referrerAddress: String?
    ) {
        launch {
            _isLoading.postValue(true)
            try {
                val chain = peopleChain ?: chainRegistry.getChain(ChainGeneses.PEZKUWI_PEOPLE)
                val accountId = cachedAccountId ?: run {
                    val metaAccount = selectedAccountUseCase.getSelectedMetaAccount()
                    metaAccount.accountIdIn(chain) ?: throw IllegalStateException("No account for People Chain")
                }

                val jsonString = """{"name":"${name.trim().lowercase()}","email":"","documents":[]}"""
                val identityHash = keccak256(jsonString.toByteArray())

                val referrerAccountId = referrerAddress?.let {
                    try {
                        it.toAccountId()
                    } catch (e: Exception) {
                        Log.w(TAG, "Invalid referrer address: $it", e)
                        null
                    }
                }

                val arguments = mutableMapOf<String, Any?>(
                    "identity_hash" to identityHash,
                    "referrer" to referrerAccountId
                )

                val result = extrinsicService.submitExtrinsic(
                    chain = chain,
                    origin = TransactionOrigin.SelectedWallet
                ) {
                    call(
                        moduleName = "IdentityKyc",
                        callName = "apply_for_citizenship",
                        arguments = arguments
                    )
                }
                result.getOrThrow()

                showToast(resourceManager.getString(R.string.citizenship_success))
                _citizenshipStatus.postValue(CitizenshipStatus.PENDING_REFERRAL)
                _dismissEvent.postValue(Event(Unit))
            } catch (e: Exception) {
                Log.e(TAG, "submitApplication failed", e)
                showError(e)
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    fun signApplication() {
        launch {
            _isLoading.postValue(true)
            try {
                val chain = peopleChain ?: chainRegistry.getChain(ChainGeneses.PEZKUWI_PEOPLE)

                val result = extrinsicService.submitExtrinsic(
                    chain = chain,
                    origin = TransactionOrigin.SelectedWallet
                ) {
                    call(
                        moduleName = "IdentityKyc",
                        callName = "confirm_citizenship",
                        arguments = emptyMap()
                    )
                }
                result.getOrThrow()

                showToast(resourceManager.getString(R.string.citizenship_success))
                _citizenshipStatus.postValue(CitizenshipStatus.APPROVED)
                _dismissEvent.postValue(Event(Unit))
            } catch (e: Exception) {
                Log.e(TAG, "signApplication failed", e)
                showError(e)
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    fun shareReferralLink() {
        launch {
            try {
                val chain = peopleChain ?: chainRegistry.getChain(ChainGeneses.PEZKUWI_PEOPLE)
                val metaAccount = selectedAccountUseCase.getSelectedMetaAccount()
                val address = metaAccount.addressIn(chain) ?: return@launch

                val deepLink = "pezkuwiwallet://pezkuwi/open/citizenship?referrer=$address"
                val shareText = resourceManager.getString(R.string.citizenship_share_referral, deepLink, address)
                _shareEvent.postValue(Event(shareText))
            } catch (e: Exception) {
                Log.e(TAG, "shareReferralLink failed", e)
            }
        }
    }

    fun approveReferral(applicantAccountId: ByteArray) {
        launch {
            _isLoading.postValue(true)
            try {
                val chain = peopleChain ?: chainRegistry.getChain(ChainGeneses.PEZKUWI_PEOPLE)

                val result = extrinsicService.submitExtrinsic(
                    chain = chain,
                    origin = TransactionOrigin.SelectedWallet
                ) {
                    call(
                        moduleName = "IdentityKyc",
                        callName = "approve_referral",
                        arguments = mapOf("applicant" to applicantAccountId)
                    )
                }
                result.getOrThrow()

                showToast(resourceManager.getString(R.string.citizenship_approve_success))
                // Reload the list
                val accountId = cachedAccountId
                if (accountId != null) {
                    loadPendingApprovals(chain, accountId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "approveReferral failed", e)
                showError(e)
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    private suspend fun loadPendingApprovals(chain: Chain, referrerAccountId: ByteArray) {
        val approvals = pezkuwiDashboardRepository.getPendingApprovals(chain, referrerAccountId)
        Log.d(TAG, "Loaded ${approvals.size} pending approvals")
        _pendingApprovals.postValue(approvals)
    }

    private fun keccak256(input: ByteArray): ByteArray {
        val digest = Keccak.Digest256()
        return digest.digest(input)
    }
}
