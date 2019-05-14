package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.models.CredentialOffer
import com.luxoft.blockchainlab.hyperledger.indy.models.KeyCorrectnessProof
import org.junit.Ignore
import org.junit.Test
import rx.Single
import kotlin.test.assertEquals
import java.net.URI
import java.net.URL
import java.util.*

fun agentInitEndpoint(agentUrl: String) {
    /**
     * HTTP GET / in order to let the agent (pythonic indy-agent) know its endpoint address
     * indy-agent.py is incapable of determining its endpoint other than this way
     */
    val uri = URI(agentUrl)
    val rootPath = "http://${uri.host}:${uri.port}/"
    val rootUrl = URL(rootPath)
    rootUrl.openConnection().getInputStream().close()
}

class PythonRefAgentConnectionTest {

    class InvitedPartyProcess (
            private val agentUrl: String,
            val proofSchemaId: String = "${Random().nextInt()}:::1"
            ) {

        fun start(invitationString: String) {
            val rand = Random().nextInt()
            agentInitEndpoint(agentUrl)
            PythonRefAgentConnection().apply {
                connect(agentUrl, "User$rand", "pass$rand").handle { _, ex ->
                    if (ex != null) {
                        throw AgentConnectionException(ex.message!!)
                    }
                    else acceptInvite(invitationString).subscribe { master ->
                        val offer = CredentialOffer(proofSchemaId, ":::1", KeyCorrectnessProof("", "", emptyList()), "")
                        master.sendCredentialOffer(offer)
                        disconnect()
                    }
                }
            }
        }
    }

    class MasterProcess (
            private val agentUrl: String,
            private val invitedPartyAgents: List<String>) {

        fun start() {
            val rand = Random().nextInt()
            agentInitEndpoint(agentUrl)
            PythonRefAgentConnection().apply {
                connect(agentUrl, "User$rand", "pass$rand").toBlocking().value()
                val invitedPartiesCompleted = mutableListOf<Single<Boolean>>()
                invitedPartyAgents.forEach { agentUrl ->
                    val party = InvitedPartyProcess(agentUrl)
                    invitedPartiesCompleted.add(Single.create { observer ->
                        generateInvite().subscribe {invitation ->
                            waitForInvitedParty(invitation).subscribe { invitedParty ->
                                invitedParty.receiveCredentialOffer().subscribe { proof ->
                                    assertEquals(proof?.schemaIdRaw, party.proofSchemaId)
                                    observer.onSuccess(true)
                                }
                            }
                            party.start(invitation)
                        }
                    })
                }
                Single.zip(invitedPartiesCompleted) {}.toBlocking().value()
                disconnect()
            }
        }
    }

    private val invitedPartyAgents = listOf(
            "ws://127.0.0.1:8094/ws",
            "ws://127.0.0.1:8096/ws",
            "ws://127.0.0.1:8097/ws",
            "ws://127.0.0.1:8098/ws",
            "ws://127.0.0.1:8099/ws"
            )
    private val masterAgent = "ws://127.0.0.1:8095/ws"

    @Ignore("Requires external services")
    @Test
    fun `externalTest`() = repeat(1) {
        MasterProcess(masterAgent, invitedPartyAgents).apply { start() }
    }
}
