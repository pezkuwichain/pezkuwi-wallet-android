package io.novafoundation.nova.feature_assets.data.repository

import android.util.Log
import io.novafoundation.nova.common.data.network.runtime.binding.bindAccountInfo
import io.novafoundation.nova.common.data.network.runtime.binding.bindNumber
import io.novafoundation.nova.common.data.network.runtime.binding.castToDictEnum
import io.novafoundation.nova.common.data.network.runtime.binding.castToList
import io.novafoundation.nova.common.data.network.runtime.binding.castToStructOrNull
import io.novafoundation.nova.feature_assets.data.model.PezkuwiDashboardData
import io.novafoundation.nova.feature_assets.presentation.citizenship.CitizenshipStatus
import io.novafoundation.nova.runtime.ext.ChainGeneses
import io.novafoundation.nova.runtime.ext.addressOf
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novafoundation.nova.runtime.storage.source.StorageDataSource
import io.novasama.substrate_sdk_android.runtime.AccountId
import io.novasama.substrate_sdk_android.runtime.metadata.moduleOrNull
import io.novasama.substrate_sdk_android.runtime.metadata.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigInteger
import java.net.URL

class PezkuwiDashboardRepository(
    private val remoteStorageDataSource: StorageDataSource
) {

    companion object {
        private const val WELATI_COUNTER_URL = "https://telegram.pezkiwi.app/kurds"
    }

    suspend fun getDashboard(accountId: AccountId): PezkuwiDashboardData {
        val chainId = ChainGeneses.PEZKUWI_PEOPLE

        val roles = queryRoles(chainId, accountId)
        val trustScore = queryTrustScore(chainId, accountId)
        val welatiCount = fetchWelatiCount()
        val kycStatus = runCatching { queryKycStatus(chainId, accountId) }.getOrDefault(CitizenshipStatus.NOT_STARTED)

        return PezkuwiDashboardData(
            roles = roles.ifEmpty { listOf("Non-Citizen") },
            trustScore = trustScore,
            welatiCount = welatiCount,
            citizenshipStatus = kycStatus
        )
    }

    private suspend fun fetchWelatiCount(): Int = withContext(Dispatchers.IO) {
        runCatching {
            val response = URL(WELATI_COUNTER_URL).readText()
            JSONObject(response).getInt("count")
        }.getOrDefault(0)
    }

    private suspend fun queryRoles(chainId: String, accountId: AccountId): List<String> = runCatching {
        remoteStorageDataSource.query(chainId) {
            val tikiModule = runtime.metadata.moduleOrNull("Tiki") ?: return@query emptyList()
            val result = tikiModule.storage("UserTikis").query(accountId, binding = { decoded ->
                decoded?.castToList()?.map { entry ->
                    entry!!.castToDictEnum().name
                } ?: emptyList()
            })
            result
        }
    }.getOrDefault(emptyList())

    private suspend fun queryTrustScore(chainId: String, accountId: AccountId): BigInteger = runCatching {
        remoteStorageDataSource.query(chainId) {
            val trustModule = runtime.metadata.moduleOrNull("Trust") ?: return@query BigInteger.ZERO
            trustModule.storage("TrustScores").query(accountId, binding = { decoded ->
                decoded?.let { bindNumber(it) } ?: BigInteger.ZERO
            })
        }
    }.getOrDefault(BigInteger.ZERO)

    suspend fun queryFreeBalance(chainId: String, accountId: AccountId): BigInteger = runCatching {
        remoteStorageDataSource.query(chainId) {
            val systemModule = runtime.metadata.moduleOrNull("System") ?: return@query BigInteger.ZERO
            systemModule.storage("Account").query(accountId, binding = { decoded ->
                decoded?.let { bindAccountInfo(it).data.free } ?: BigInteger.ZERO
            })
        }
    }.getOrDefault(BigInteger.ZERO)

    suspend fun getPendingApprovals(chain: Chain, referrerAccountId: AccountId): List<PendingApproval> {
        return runCatching {
            remoteStorageDataSource.query(chain.id) {
                val kycModule = runtime.metadata.moduleOrNull("IdentityKyc") ?: return@query emptyList()
                val results = mutableListOf<PendingApproval>()
                val seenAccounts = mutableSetOf<String>()

                // 1) Confirmed referrals from Referral::Referrals (referred → referrer AccountId)
                val referralModule = runtime.metadata.moduleOrNull("Referral")
                if (referralModule != null) {
                    val allReferrals: Map<ByteArray, Any?> = referralModule.storage("Referrals").entries(
                        keyExtractor = { it.component1<ByteArray>() },
                        binding = { decoded, _ -> decoded }
                    )
                    allReferrals.forEach { (referredId, referrerValue) ->
                        val referrer = referrerValue as? ByteArray ?: return@forEach
                        if (!referrer.contentEquals(referrerAccountId)) return@forEach

                        val addr = chain.addressOf(referredId)
                        seenAccounts.add(addr)

                        val statusName = kycModule.storage("KycStatuses").query(referredId, binding = { d ->
                            d?.castToDictEnum()?.name
                        })
                        val status = mapKycStatus(statusName)
                        results.add(PendingApproval(referredId, addr, status))
                    }
                }

                // 2) Pending applications not yet in Referral pallet
                val allApplications: Map<ByteArray, Any?> = kycModule.storage("Applications").entries(
                    keyExtractor = { it.component1<ByteArray>() },
                    binding = { decoded, _ -> decoded }
                )
                allApplications.forEach { (applicantId, decoded) ->
                    val struct = decoded?.castToStructOrNull() ?: return@forEach
                    val referrer = struct["referrer"] as? ByteArray ?: return@forEach
                    if (!referrer.contentEquals(referrerAccountId)) return@forEach

                    val addr = chain.addressOf(applicantId)
                    if (addr in seenAccounts) return@forEach

                    val statusName = kycModule.storage("KycStatuses").query(applicantId, binding = { d ->
                        d?.castToDictEnum()?.name
                    })
                    val status = mapKycStatus(statusName)
                    results.add(PendingApproval(applicantId, addr, status))
                }

                results
            }
        }.getOrElse { e ->
            Log.e("PezkuwiDashboard", "getPendingApprovals failed", e)
            emptyList()
        }
    }

    private fun mapKycStatus(statusName: String?): CitizenshipStatus {
        return when (statusName) {
            "PendingReferral" -> CitizenshipStatus.PENDING_REFERRAL
            "ReferrerApproved" -> CitizenshipStatus.REFERRER_APPROVED
            "Approved" -> CitizenshipStatus.APPROVED
            else -> CitizenshipStatus.NOT_STARTED
        }
    }

    suspend fun queryKycStatus(chainId: String, accountId: AccountId): CitizenshipStatus {
        return remoteStorageDataSource.query(chainId) {
            val kycModule = runtime.metadata.moduleOrNull("IdentityKyc") ?: run {
                Log.w("PezkuwiDashboard", "IdentityKyc module not found in metadata")
                return@query CitizenshipStatus.NOT_STARTED
            }
            kycModule.storage("KycStatuses").query(accountId, binding = { decoded ->
                val enumName = decoded?.castToDictEnum()?.name
                Log.d("PezkuwiDashboard", "KYC status raw enum: '$enumName' (decoded=$decoded)")
                when (enumName) {
                    "PendingReferral" -> CitizenshipStatus.PENDING_REFERRAL
                    "ReferrerApproved" -> CitizenshipStatus.REFERRER_APPROVED
                    "Approved" -> CitizenshipStatus.APPROVED
                    else -> CitizenshipStatus.NOT_STARTED
                }
            })
        }
    }
}

data class PendingApproval(
    val applicantAccountId: ByteArray,
    val applicantAddress: String,
    val status: CitizenshipStatus
)
