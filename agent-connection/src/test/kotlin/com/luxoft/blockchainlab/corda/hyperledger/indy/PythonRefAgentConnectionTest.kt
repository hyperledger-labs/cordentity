package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.CredentialOffer
import com.luxoft.blockchainlab.hyperledger.indy.KeyCorrectnessProof
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import java.net.URI
import java.net.URL

class PythonRefAgentConnectionTest {

    private fun agentInitEndpoint(agentUrl: String) {
        /**
         * HTTP GET / in order to let the agent (pythonic indy-agent) know its endpoint address
         * indy-agent.py is incapable of determining its endpoint other than this way
         */
        val uri = URI(agentUrl)
        val rootPath = "http://${uri.host}:${uri.port}/"
        val rootUrl = URL(rootPath)
        rootUrl.openConnection().getInputStream().close()
    }

    private lateinit var agentUrl1:String
    private lateinit var agentUrl2:String

    @Before
    fun preInit(){
        agentUrl1 = "ws://127.0.0.1:8094/ws"
        agentInitEndpoint(agentUrl1)
        agentUrl2 = "ws://127.0.0.1:8095/ws"
        agentInitEndpoint(agentUrl2)
    }
    private var inviteStr:String = ""
    @Ignore("Requires external services")
    @Test
    fun `externalTest`() {
        val agent95completed = CompletableFuture<Unit>()
        val agent94completed = CompletableFuture<Unit>()
        PythonRefAgentConnection().apply {
            connect(agentUrl1, "user95", "pass95").handle { _, ex ->
                if (ex != null) throw AgentConnectionException(ex.message!!)
                else genInvite().subscribe {
                    inviteStr = it!!
                    waitForInvitedParty().subscribe {
                        receiveCredentialOffer().subscribe { proof1 ->
                            assertEquals(proof1?.schemaId,"1")
                            receiveCredentialOffer().subscribe { proof2 ->
                                assertEquals(proof2?.schemaId,"2")
                                receiveCredentialOffer().subscribe { proof3 ->
                                    assertEquals(proof3?.schemaId, "3")
                                    disconnect()
                                    agent95completed.complete(Unit)
                                }
                            }
                        }
                    }
                    PythonRefAgentConnection().apply {
                        connect(agentUrl2, "user94", "pass94").handle { _, ex ->
                            if (ex != null) throw AgentConnectionException(ex.message!!)
                            else acceptInvite(inviteStr).subscribe {
                                sendCredentialOffer(CredentialOffer("1", "", KeyCorrectnessProof("", "", emptyList()), ""))
                                sendCredentialOffer(CredentialOffer("2", "", KeyCorrectnessProof("", "", emptyList()), ""))
                                sendCredentialOffer(CredentialOffer("3", "", KeyCorrectnessProof("", "", emptyList()), ""))
                                disconnect()
                                agent94completed.complete(Unit)
                            }
                        }
                    }
                }
            }
        }
        CompletableFuture.allOf(agent94completed,agent95completed).get()
    }
}