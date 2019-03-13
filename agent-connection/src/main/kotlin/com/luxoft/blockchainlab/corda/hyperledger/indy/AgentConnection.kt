package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.*
import rx.Observable

enum class AgentConnectionStatus { AGENT_CONNECTED, AGENT_DISCONNECTED }

data class IndyParty(val did: String, val endpoint: String, val verkey: String? = null)

class AgentConnectionException(obj: Any) :
        RuntimeException("AgentConnection exception: $obj")

fun <T> Observable<T>.handle(handle: (message: T?, ex: Throwable?) -> Unit) = this.subscribe({ handle(it, null) }, { handle(null, it) }, {})

interface AgentConnection {
    fun connect(url: String, login: String, password: String): Observable<Unit>
    fun disconnect()

    fun genInvite(): Observable<String>
    fun acceptInvite(invite: String): Observable<Unit>
    fun waitForInvitedParty(): Observable<Unit>

    fun getConnectionStatus(): AgentConnectionStatus
    fun getCounterParty(): IndyParty?

    fun sendCredentialOffer(offer: CredentialOffer)
    fun receiveCredentialOffer(): Observable<CredentialOffer>

    fun sendCredentialRequest(request: CredentialRequestInfo)
    fun receiveCredentialRequest(): Observable<CredentialRequestInfo>

    fun sendCredential(credential: CredentialInfo)
    fun receiveCredential(): Observable<CredentialInfo>

    fun sendProofRequest(request: ProofRequest)
    fun receiveProofRequest(): Observable<ProofRequest>

    fun sendProof(proof: ProofInfo)
    fun receiveProof(): Observable<ProofInfo>
}

