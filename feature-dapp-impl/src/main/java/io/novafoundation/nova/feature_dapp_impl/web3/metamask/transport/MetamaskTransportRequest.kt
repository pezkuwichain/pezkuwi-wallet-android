package io.novafoundation.nova.feature_dapp_impl.web3.metamask.transport

import com.google.gson.Gson
import io.novafoundation.nova.feature_account_api.data.ethereum.transaction.TransactionHash
import io.novafoundation.nova.feature_dapp_impl.web3.Web3Transport
import io.novafoundation.nova.feature_dapp_impl.web3.metamask.model.EthereumAddress
import io.novafoundation.nova.feature_dapp_impl.web3.metamask.model.MetamaskChain
import io.novafoundation.nova.feature_dapp_impl.web3.metamask.model.MetamaskPersonalSignMessage
import io.novafoundation.nova.feature_dapp_impl.web3.metamask.model.MetamaskTransaction
import io.novafoundation.nova.feature_dapp_impl.web3.metamask.model.MetamaskTypedMessage
import io.novafoundation.nova.feature_dapp_impl.web3.metamask.model.SignedMessage

sealed class MetamaskTransportRequest<R>(
    val id: String,
    override val origin: String,
    private val gson: Gson,
    protected val responder: MetamaskResponder,
) : Web3Transport.Request<R> {

    override fun reject(error: Throwable) {
        require(error is MetamaskError) {
            "Metamask transport allows only instances of MetamaskError as errors"
        }

        responder.respondError(id, error)
    }

    override fun accept(response: R) {
        if (response is Unit) {
            responder.respondNullResult(id)
        } else {
            responder.respondResult(id, gson.toJson(response))
        }
    }

    enum class Identifier(val id: String) {
        REQUEST_ACCOUNTS("requestAccounts"),
        ADD_ETHEREUM_CHAIN("addEthereumChain"),
        SWITCH_ETHEREUM_CHAIN("switchEthereumChain"),
        SIGN_TRANSACTION("signTransaction"),
        SIGN_TYPED_MESSAGE("signTypedMessage"),
        PERSONAL_SIGN("signPersonalMessage")
    }

    class RequestAccounts(
        id: String,
        origin: String,
        gson: Gson,
        responder: MetamaskResponder
    ) : MetamaskTransportRequest<List<EthereumAddress>>(id, origin, gson, responder)

    class AddEthereumChain(
        id: String,
        origin: String,
        gson: Gson,
        responder: MetamaskResponder,
        val chain: MetamaskChain,
    ) : MetamaskTransportRequest<Unit>(id, origin, gson, responder)

    class SwitchEthereumChain(
        id: String,
        origin: String,
        gson: Gson,
        responder: MetamaskResponder,
        val chainId: String
    ) : MetamaskTransportRequest<Unit>(id, origin, gson, responder)

    class SendTransaction(
        id: String,
        origin: String,
        gson: Gson,
        responder: MetamaskResponder,
        val transaction: MetamaskTransaction
    ) : MetamaskTransportRequest<TransactionHash>(id, origin, gson, responder)

    class SignTypedMessage(
        id: String,
        origin: String,
        gson: Gson,
        responder: MetamaskResponder,
        val message: MetamaskTypedMessage
    ) : MetamaskTransportRequest<SignedMessage>(id, origin, gson, responder)

    class PersonalSign(
        id: String,
        origin: String,
        gson: Gson,
        responder: MetamaskResponder,
        val message: MetamaskPersonalSignMessage
    ) : MetamaskTransportRequest<SignedMessage>(id, origin, gson, responder)
}
