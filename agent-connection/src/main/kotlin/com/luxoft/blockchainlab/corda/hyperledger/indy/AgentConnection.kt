package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.models.*
import rx.Single
import rx.Subscription


enum class AgentConnectionStatus { AGENT_CONNECTED, AGENT_DISCONNECTED }

interface IndyPartyConnection {
    fun sendCredentialOffer(offer: CredentialOffer)
    fun receiveCredentialOffer(): Single<CredentialOffer>

    fun sendCredentialRequest(request: CredentialRequestInfo)
    fun receiveCredentialRequest(): Single<CredentialRequestInfo>

    fun sendCredential(credential: CredentialInfo)
    fun receiveCredential(): Single<CredentialInfo>

    fun sendProofRequest(request: ProofRequest)
    fun receiveProofRequest(): Single<ProofRequest>

    fun sendProof(proof: ProofInfo)
    fun receiveProof(): Single<ProofInfo>
}

class AgentConnectionException(obj: Any) :
        RuntimeException("AgentConnection exception: $obj")

fun <T> Single<T>.handle(handle: (message: T?, ex: Throwable?) -> Unit): Subscription = this.subscribe({ handle(it, null) }, { handle(null, it) })

interface AgentConnection {
    fun connect(url: String, login: String, password: String): Single<Unit>
    fun disconnect()

    fun genInvite(): Single<String>
    fun acceptInvite(invite: String): Single<IndyPartyConnection>
    fun waitForInvitedParty(invite: String): Single<IndyPartyConnection>

    fun getConnectionStatus(): AgentConnectionStatus
}

