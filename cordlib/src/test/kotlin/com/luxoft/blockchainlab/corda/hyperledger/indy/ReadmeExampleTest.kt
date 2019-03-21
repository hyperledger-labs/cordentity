package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.CreateCredentialDefinitionFlow
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.CreateSchemaFlow
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.ProofPredicate
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2b.*
import net.corda.core.identity.CordaX500Name
import net.corda.node.internal.StartedNode
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.internal.InternalMockNetwork
import net.corda.testing.node.internal.InternalMockNetwork.MockNode
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.time.LocalDateTime
import java.util.*


class ReadmeExampleTest : CordaTestBase() {

    private lateinit var trustee: StartedNode<MockNode>
    private lateinit var issuer: StartedNode<MockNode>
    private lateinit var alice: StartedNode<MockNode>
    private lateinit var bob: StartedNode<MockNode>

    @Before
    fun setup() {
        trustee = createPartyNode(CordaX500Name("Trustee", "London", "GB"))
        issuer = createPartyNode(CordaX500Name("Issuer", "London", "GB"))
        alice = createPartyNode(CordaX500Name("Alice", "London", "GB"))
        bob = createPartyNode(CordaX500Name("Bob", "London", "GB"))

        setPermissions(issuer, trustee)
    }

    @Test
    @Ignore("The test not represents the logic it should")
    fun `grocery store example`() {
        val ministry: StartedNode<InternalMockNetwork.MockNode> = issuer
        val alice: StartedNode<*> = alice
        val store: StartedNode<*> = bob

        // Each Corda node has a X500 name:

        val ministryX500 = ministry.info.singleIdentity().name
        val aliceX500 = alice.info.singleIdentity().name

        // And each Indy node has a DID, a.k.a Decentralized ID:

        val ministryDID = store.services.startFlow(
            GetDidFlowB2B.Initiator(ministryX500)
        ).resultFuture.get()

        // To allow customers and shops to communicate, Ministry issues a shopping scheme:

        val schemaId = ministry.services.startFlow(
            CreateSchemaFlow.Authority(
                "shopping scheme",
                "1.0",
                listOf("NAME", "BORN")
            )
        ).resultFuture.get()

        // Ministry creates a credential definition for the shopping scheme:

        val credentialDefinitionId = ministry.services.startFlow(
            CreateCredentialDefinitionFlow.Authority(schemaId)
        ).resultFuture.get()

        // Ministry verifies Alice's legal status and issues her a shopping credential:

        val credentialProposal = """
        {
        "NAME":{"raw":"Alice", "encoded":"119191919"},
        "BORN":{"raw":"2000",  "encoded":"2000"}
        }
        """

        ministry.services.startFlow(
            IssueCredentialFlowB2B.Issuer(
                UUID.randomUUID().toString(),
                credentialProposal,
                credentialDefinitionId,
                aliceX500
            )
        ).resultFuture.get()

        // When Alice comes to grocery store, the store asks Alice to verify that she is legally allowed to buy drinks:

        // Alice.BORN >= currentYear - 18
        val eighteenYearsAgo = LocalDateTime.now().minusYears(18).year
        val legalAgePredicate =
            ProofPredicate(schemaId, credentialDefinitionId, "BORN", eighteenYearsAgo)

        val verified = store.services.startFlow(
            VerifyCredentialFlowB2B.Verifier(
                UUID.randomUUID().toString(),
                emptyList(),
                listOf(legalAgePredicate),
                aliceX500
            )
        ).resultFuture.get()

        // If the verification succeeds, the store can be sure that Alice's age is above 18.

        println("You can buy drinks: $verified")
    }
}