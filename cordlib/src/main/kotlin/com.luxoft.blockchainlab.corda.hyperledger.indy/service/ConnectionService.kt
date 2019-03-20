package com.luxoft.blockchainlab.corda.hyperledger.indy.service

import com.luxoft.blockchainlab.corda.hyperledger.indy.PythonRefAgentConnection
import com.luxoft.blockchainlab.corda.hyperledger.indy.AgentConnection
import com.luxoft.blockchainlab.hyperledger.indy.helpers.ConfigHelper
import com.luxoft.blockchainlab.hyperledger.indy.helpers.indyuser
import com.luxoft.blockchainlab.hyperledger.indy.models.*
import net.corda.core.flows.FlowLogic
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.SingletonSerializeAsToken
import java.lang.RuntimeException


@CordaService
class ConnectionService : SingletonSerializeAsToken() {
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

    /**
     * These next private values should be initialized here despite the fact they are used only in [connection] initialization.
     * This is so because we're unable to mock Kotlin-object in our case properly (several times with context save) in
     * tests, but in production we also should have lazy initialization.
     *
     * So basically we need to access config at static init time, but use config values at lazy init time
     */
    private val agentWsEndpoint = ConfigHelper.getAgentWSEndpoint()
    private val agentLogin = ConfigHelper.getAgentUser()
    private val agentPassword = ConfigHelper.getAgentPassword()

    private val connection by lazy {
        if (agentWsEndpoint != null) {
            agentLogin ?: throw RuntimeException("Agent websocket endpoint specified but agent user name is missing")
            agentPassword ?: throw RuntimeException("Agent websocket endpoint specified but agent password is missing")

            PythonRefAgentConnection().apply { connect(agentWsEndpoint, agentLogin, agentPassword) }
        } else
            null
    }

    fun getConnection(): AgentConnection {
        return connection ?:
            throw RuntimeException("Unable to get connection: Please specify 'agentWSEndpoint', 'agentUser', 'agentPassword' properties in config")
    }
}

fun FlowLogic<Any>.connectionService() = serviceHub.cordaService(ConnectionService::class.java)