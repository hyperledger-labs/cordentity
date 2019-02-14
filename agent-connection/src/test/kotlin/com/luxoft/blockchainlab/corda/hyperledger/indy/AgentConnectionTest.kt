package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.Proof
import org.junit.Ignore
import org.junit.Test
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals

class AgentConnectionTest {

    @Ignore("Requires external services")
    @Test
    fun `externalTest`() {
        val agent95completed = CompletableFuture<Unit>()
        val agent94completed = CompletableFuture<Unit>()
        val agent95 = AgentConnection("ws://172.17.0.1:8095/ws")
        val inviteMsg = agent95.genInvite()
        CompletableFuture.runAsync {
            agent95.run {
                waitForCounterParty()
                val proof = waitForTypedMessage<Proof>()
                val proof2 = waitForTypedMessage<Proof>()
                val proof3 = waitForTypedMessage<Proof>()
                assertEquals(proof.aggregatedProof,"1")
                assertEquals(proof2.aggregatedProof,"2")
                assertEquals(proof3.aggregatedProof,"3")
                agent95completed.complete(Unit)
            }
        }

        val agent94 = AgentConnection("ws://172.17.0.1:8094/ws", invite = inviteMsg.invite).apply {
            sendProof(Proof(emptyList(), "1"))
            sendProof(Proof(emptyList(), "2"))
            sendProof(Proof(emptyList(), "3"))
            agent94completed.complete(Unit)
        }
        agent95.run {
            //TODO: find out how to get DID
            var did = ""
            sendJson(AgentConnection.LoadMessage(did))
        }
        CompletableFuture.allOf(agent94completed,agent95completed).get()
    }
}