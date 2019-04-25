package com.luxoft.blockchainlab.corda.hyperledger.indy.service

import co.paralleluniverse.fibers.FiberAsync
import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.PythonRefAgentConnection
import com.luxoft.blockchainlab.corda.hyperledger.indy.AgentConnection
import com.luxoft.blockchainlab.hyperledger.indy.helpers.ConfigHelper
import com.luxoft.blockchainlab.hyperledger.indy.models.*
import net.corda.core.flows.FlowLogic
import net.corda.core.node.AppServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.SingletonSerializeAsToken
import rx.Single
import java.lang.RuntimeException


@CordaService
class ConnectionService (services: AppServiceHub) : SingletonSerializeAsToken() {
    fun sendCredentialOffer(offer: CredentialOffer, partyDID: String) = getPartyConnection(partyDID).sendCredentialOffer(offer)

    fun receiveCredentialOffer(partyDID: String) = getPartyConnection(partyDID).receiveCredentialOffer()

    fun sendCredentialRequest(request: CredentialRequestInfo, partyDID: String) = getPartyConnection(partyDID).sendCredentialRequest(request)

    fun receiveCredentialRequest(partyDID: String) = getPartyConnection(partyDID).receiveCredentialRequest()

    fun sendCredential(credential: CredentialInfo, partyDID: String) = getPartyConnection(partyDID).sendCredential(credential)

    fun receiveCredential(partyDID: String) = getPartyConnection(partyDID).receiveCredential()

    fun sendProofRequest(request: ProofRequest, partyDID: String) = getPartyConnection(partyDID).sendProofRequest(request)

    fun receiveProofRequest(partyDID: String) = getPartyConnection(partyDID).receiveProofRequest()

    fun sendProof(proof: ProofInfo, partyDID: String) = getPartyConnection(partyDID).sendProof(proof)

    fun receiveProof(partyDID: String) = getPartyConnection(partyDID).receiveProof()

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

            PythonRefAgentConnection().apply { connect(agentWsEndpoint, agentLogin, agentPassword).awaitFiber() }
        } else
            null
    }

    fun getConnection(): AgentConnection {
        return connection
                ?: throw RuntimeException("Unable to get connection: Please specify 'agentWSEndpoint', 'agentUser', 'agentPassword' properties in config")
    }

    private fun getPartyConnection(partyDID: String) = getConnection().getIndyPartyConnection(partyDID).awaitFiber()
            ?: throw RuntimeException("Unable to get IndyPartyConnection for DID: $partyDID")
}

@Suspendable
fun <T> Single<T>.awaitFiber(): T {
    val observable = this
    return object : FiberAsync<T, Throwable>() {
        override fun requestAsync() {
            observable.subscribe({ asyncCompleted(it) }, { asyncFailed(it) })
        }
    }.run()
}

fun FlowLogic<Any>.connectionService() = serviceHub.cordaService(ConnectionService::class.java)