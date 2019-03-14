package com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2c

import com.luxoft.blockchainlab.corda.hyperledger.indy.PythonRefAgentConnection
import com.luxoft.blockchainlab.corda.hyperledger.indy.AgentConnection
import com.luxoft.blockchainlab.corda.hyperledger.indy.service.indyuser
import com.luxoft.blockchainlab.hyperledger.indy.*
import com.natpryce.konfig.*
import net.corda.core.flows.FlowLogic
import net.corda.core.node.AppServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.SingletonSerializeAsToken
import java.io.File


@CordaService
class ConnectionService(serviceHub: AppServiceHub) : SingletonSerializeAsToken() {
    private val config = TestConfigurationsProvider.config(serviceHub.myInfo.legalIdentities.first().name.organisation)
            ?: EmptyConfiguration
                    .ifNot(
                            ConfigurationProperties.fromFileOrNull(File("indyconfig", "indy.properties")),
                            indyuser
                    ) // file with common name if you go for file-based config
                    .ifNot(
                            ConfigurationProperties.fromFileOrNull(
                                    File(
                                            "indyconfig",
                                            "${serviceHub.myInfo.legalIdentities.first().name.organisation}.indy.properties"
                                    )
                            ),
                            indyuser
                    )  //  file with node-specific name
                    .ifNot(EnvironmentVariables(), indyuser) // Good for docker-compose, ansible-playbook or similar

    fun getCounterParty() = connection!!.getCounterParty()

    fun sendCredentialOffer(offer: CredentialOffer) = connection!!.sendCredentialOffer(offer)

    fun receiveCredentialOffer() = connection!!.receiveCredentialOffer()

    fun sendCredentialRequest(request: CredentialRequestInfo) = connection!!.sendCredentialRequest(request)

    fun receiveCredentialRequest() = connection!!.receiveCredentialRequest()

    fun sendCredential(credential: CredentialInfo) = connection!!.sendCredential(credential)

    fun receiveCredential() = connection!!.receiveCredential()

    fun sendProofRequest(request: ProofRequest) = connection!!.sendProofRequest(request)

    fun receiveProofRequest() = connection!!.receiveProofRequest()

    fun sendProof(proof: ProofInfo) = connection!!.sendProof(proof)

    fun receiveProof() = connection!!.receiveProof()

    private val connection = if (config.getOrNull(indyuser.agentWSEndpoint) != null)
        PythonRefAgentConnection().apply { connect(config[indyuser.agentWSEndpoint], login = config[indyuser.agentUser], password = config[indyuser.agentPassword]) }
    else
        null

    fun getConnection(): AgentConnection {
        return connection!!
    }
}

fun FlowLogic<Any>.connectionService() = serviceHub.cordaService(ConnectionService::class.java)