package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.*

enum class AgentConnectionStatus { AGENT_CONNECTION_CONNECTED, AGENT_CONNECTION_DISCONNECTED }

data class IndyParty(val did: String, val endpoint: String, val verkey: String? = null)

class AgentConnectionException(obj: Any) :
        RuntimeException("AgentConnection exception: $obj")

interface AgentConnection {
    fun connect(url: String, login: String, password: String)
    fun disconnect()

    fun genInvite(): String
    fun acceptInvite(invite: String)
    fun waitForInvitedParty()

    fun getConnectionStatus(): AgentConnectionStatus
    fun getCounterParty(): IndyParty?

    fun sendCredentialOffer(offer: CredentialOffer)
    fun receiveCredentialOffer(): CredentialOffer

    fun sendCredentialRequest(request: CredentialRequestInfo)
    fun receiveCredentialRequest(): CredentialRequestInfo

    fun sendCredential(credential: CredentialInfo)
    fun receiveCredential(): CredentialInfo

    fun sendProofRequest(request: ProofRequest)
    fun receiveProofRequest(): ProofRequest

    fun sendProof(proof: ProofInfo)
    fun receiveProof(): ProofInfo
}

