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

    private lateinit var agentUrl1: String
    private lateinit var agentUrl2: String
    private lateinit var agentUrl3: String
    private lateinit var agentUrl4: String
    private lateinit var agentUrl5: String
    private lateinit var agentUrl6: String

    @Before
    fun preInit(){
        agentUrl1 = "ws://127.0.0.1:8094/ws"
        agentInitEndpoint(agentUrl1)
        agentUrl2 = "ws://127.0.0.1:8095/ws"
        agentInitEndpoint(agentUrl2)
        agentUrl3 = "ws://127.0.0.1:8096/ws"
        agentInitEndpoint(agentUrl3)
        agentUrl4 = "ws://127.0.0.1:8097/ws"
        agentInitEndpoint(agentUrl4)
        agentUrl5 = "ws://127.0.0.1:8098/ws"
        agentInitEndpoint(agentUrl5)
        agentUrl6 = "ws://127.0.0.1:8099/ws"
        agentInitEndpoint(agentUrl6)
    }
    private var inviteStr:String = ""
    private var inviteStr2: String = ""
    private var inviteStr3: String = ""
    private var inviteStr4: String = ""
    private var inviteStr5: String = ""

    @Ignore("Requires external services")
    @Test
    fun `externalTest`() {
        val agent95completed = CompletableFuture<Unit>()
        val agent95completed2 = CompletableFuture<Unit>()
        val agent95completed3 = CompletableFuture<Unit>()
        val agent95completed4 = CompletableFuture<Unit>()
        val agent95completed5 = CompletableFuture<Unit>()
        val agent94completed = CompletableFuture<Unit>()
        val agent96completed = CompletableFuture<Unit>()
        val agent97completed = CompletableFuture<Unit>()
        val agent98completed = CompletableFuture<Unit>()
        val agent99completed = CompletableFuture<Unit>()
        PythonRefAgentConnection().apply {
            connect(agentUrl1, "user95", "pass95").handle { _, ex ->
                if (ex != null) throw AgentConnectionException(ex.message!!)
                else genInvite().subscribe {
                    inviteStr = it!!
                    waitForInvitedParty(inviteStr).subscribe { user94 ->
                        user94.receiveCredentialOffer().subscribe { proof94 ->
                            assertEquals(proof94?.schemaId, "94")
                            agent95completed.complete(Unit)
                        }
                    }
                    PythonRefAgentConnection().apply {
                        connect(agentUrl2, "user94", "pass94").handle { _, ex ->
                            if (ex != null) throw AgentConnectionException(ex.message!!)
                            else acceptInvite(inviteStr).subscribe { user95 ->
                                user95.sendCredentialOffer(CredentialOffer("94", "", KeyCorrectnessProof("", "", emptyList()), ""))
                                agent94completed.complete(Unit)
                            }
                        }
                    }
                    genInvite().subscribe { invite2 ->
                        inviteStr2 = invite2
                        waitForInvitedParty(inviteStr2).subscribe { user96 ->
                            user96.receiveCredentialOffer().subscribe { proof96 ->
                                assertEquals(proof96?.schemaId, "96")
                                agent95completed2.complete(Unit)
                            }
                        }
                        PythonRefAgentConnection().apply {
                            connect(agentUrl3, "user96", "pass96").handle { _, ex ->
                                if (ex != null) throw AgentConnectionException(ex.message!!)
                                else acceptInvite(inviteStr2).subscribe { user95 ->
                                    user95.sendCredentialOffer(CredentialOffer("96", "", KeyCorrectnessProof("", "", emptyList()), ""))
                                    agent96completed.complete(Unit)
                                }
                            }
                        }
                    }
                    genInvite().subscribe { invite3 ->
                        inviteStr3 = invite3
                        waitForInvitedParty(inviteStr3).subscribe { user97 ->
                            user97.receiveCredentialOffer().subscribe { proof97 ->
                                assertEquals(proof97?.schemaId, "97")
                                agent95completed3.complete(Unit)
                            }
                        }
                        PythonRefAgentConnection().apply {
                            connect(agentUrl4, "user97", "pass97").handle { _, ex ->
                                if (ex != null) throw AgentConnectionException(ex.message!!)
                                else acceptInvite(inviteStr3).subscribe { user95 ->
                                    user95.sendCredentialOffer(CredentialOffer("97", "", KeyCorrectnessProof("", "", emptyList()), ""))
                                    agent97completed.complete(Unit)
                                }
                            }
                        }
                    }
                    genInvite().subscribe { invite4 ->
                        inviteStr4 = invite4
                        waitForInvitedParty(inviteStr4).subscribe { user98 ->
                            user98.receiveCredentialOffer().subscribe { proof98 ->
                                assertEquals(proof98?.schemaId, "98")
                                agent95completed4.complete(Unit)
                            }
                        }
                        PythonRefAgentConnection().apply {
                            connect(agentUrl5, "user98", "pass98").handle { _, ex ->
                                if (ex != null) throw AgentConnectionException(ex.message!!)
                                else acceptInvite(inviteStr4).subscribe { user95 ->
                                    user95.sendCredentialOffer(CredentialOffer("98", "", KeyCorrectnessProof("", "", emptyList()), ""))
                                    agent98completed.complete(Unit)
                                }
                            }
                        }
                    }
                    genInvite().subscribe { invite5 ->
                        inviteStr5 = invite5
                        waitForInvitedParty(inviteStr5).subscribe { user99 ->
                            user99.receiveCredentialOffer().subscribe { proof99 ->
                                assertEquals(proof99?.schemaId, "99")
                                agent95completed5.complete(Unit)
                            }
                        }
                        PythonRefAgentConnection().apply {
                            connect(agentUrl6, "user99", "pass99").handle { _, ex ->
                                if (ex != null) throw AgentConnectionException(ex.message!!)
                                else acceptInvite(inviteStr5).subscribe { user95 ->
                                    user95.sendCredentialOffer(CredentialOffer("99", "", KeyCorrectnessProof("", "", emptyList()), ""))
                                    agent99completed.complete(Unit)
                                }
                            }
                        }
                    }
                }
            }
        }
        CompletableFuture.allOf(agent94completed, agent95completed, agent95completed2, agent96completed, agent97completed,
                agent98completed, agent99completed, agent95completed3, agent95completed4, agent95completed5).get()
    }
}