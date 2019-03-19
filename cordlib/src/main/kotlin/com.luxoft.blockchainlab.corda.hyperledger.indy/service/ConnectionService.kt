package com.luxoft.blockchainlab.corda.hyperledger.indy.service

import com.luxoft.blockchainlab.corda.hyperledger.indy.PythonRefAgentConnection
import com.luxoft.blockchainlab.corda.hyperledger.indy.AgentConnection
import com.luxoft.blockchainlab.hyperledger.indy.helpers.ConfigHelper
import com.luxoft.blockchainlab.hyperledger.indy.helpers.indyuser
import com.luxoft.blockchainlab.hyperledger.indy.models.*
import net.corda.core.flows.FlowLogic
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.SingletonSerializeAsToken


@CordaService
class ConnectionService : SingletonSerializeAsToken() {
    private val config = ConfigHelper.getConfig()

    fun getCounterParty() = getConnection().getCounterParty()

    fun sendCredentialOffer(offer: CredentialOffer) = getConnection().sendCredentialOffer(offer)

    fun receiveCredentialOffer() = getConnection().receiveCredentialOffer()

    fun sendCredentialRequest(request: CredentialRequestInfo) = getConnection().sendCredentialRequest(request)

    fun receiveCredentialRequest() = getConnection().receiveCredentialRequest()

    fun sendCredential(credential: CredentialInfo) = getConnection().sendCredential(credential)

    fun receiveCredential() = getConnection().receiveCredential()

    fun sendProofRequest(request: ProofRequest) = getConnection().sendProofRequest(request)

    fun receiveProofRequest() = getConnection().receiveProofRequest()

    fun sendProof(proof: ProofInfo) = getConnection().sendProof(proof)

    fun receiveProof() = getConnection().receiveProof()

    private val connection = if (config.getOrNull(indyuser.agentWSEndpoint) != null)
        PythonRefAgentConnection().apply { connect(config[indyuser.agentWSEndpoint], login = config[indyuser.agentUser], password = config[indyuser.agentPassword]) }
    else
        null

    fun getConnection(): AgentConnection {
        return connection!!
    }
}

fun FlowLogic<Any>.connectionService() = serviceHub.cordaService(ConnectionService::class.java)