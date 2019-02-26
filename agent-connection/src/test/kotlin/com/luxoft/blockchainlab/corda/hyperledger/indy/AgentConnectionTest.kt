package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.CredentialOffer
import com.luxoft.blockchainlab.hyperledger.indy.KeyCorrectnessProof
import org.junit.Ignore
import org.junit.Test
import java.util.*
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentConnectionTest {

    @Ignore("Requires external services")
    @Test
    fun `externalTest`() {
        val agent95completed = CompletableFuture<Unit>()
        val agent94completed = CompletableFuture<Unit>()
        val agent95 = AgentConnection("ws://10.255.255.21:8095/ws", userName = "user${Random().nextInt()}")
        val inviteMsg = agent95.genInvite()
        CompletableFuture.runAsync {
            agent95.run {
                waitForCounterParty()
                val proof = receiveCredentialOffer()
                val proof2 = receiveCredentialOffer()
                val proof3 = receiveCredentialOffer()
                assertEquals(proof.schemaId,"1")
                assertEquals(proof2.schemaId,"2")
                assertEquals(proof3.schemaId,"3")

            }
        }.handle { t, u ->
            if (u != null) u.printStackTrace()
            assertNull(u)
            agent95completed.complete(Unit)
        }

        val agent94 = AgentConnection("ws://10.255.255.21:8094/ws", invite = inviteMsg.invite, userName = "user${Random().nextInt()}").apply {
            sendCredentialOffer(CredentialOffer("1", "", KeyCorrectnessProof("", "", emptyList()), ""))
            sendCredentialOffer(CredentialOffer("2", "", KeyCorrectnessProof("", "", emptyList()), ""))
            sendCredentialOffer(CredentialOffer("3", "", KeyCorrectnessProof("", "", emptyList()), ""))
            agent94completed.complete(Unit)
        }

        CompletableFuture.allOf(agent94completed,agent95completed).get()
    }
}