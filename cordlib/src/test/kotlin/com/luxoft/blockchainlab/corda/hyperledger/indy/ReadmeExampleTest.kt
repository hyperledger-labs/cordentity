package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.CreateCredentialDefinitionFlow
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.CreateSchemaFlow
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2b.*
import com.luxoft.blockchainlab.hyperledger.indy.models.CredentialValue
import com.luxoft.blockchainlab.hyperledger.indy.utils.WalletUtils
import com.luxoft.blockchainlab.hyperledger.indy.utils.proofRequest
import com.luxoft.blockchainlab.hyperledger.indy.utils.proveGreaterThan
import net.corda.core.identity.CordaX500Name
import net.corda.node.internal.StartedNode
import net.corda.testing.node.internal.InternalMockNetwork
import net.corda.testing.node.internal.InternalMockNetwork.MockNode
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.time.LocalDateTime


class ReadmeExampleTest : CordaTestBase() {

    private lateinit var issuer: StartedNode<MockNode>
    private lateinit var alice: StartedNode<MockNode>
    private lateinit var bob: StartedNode<MockNode>

    @Before
    fun setup() {
        trustee = createPartyNode(CordaX500Name("Trustee", "London", "GB"))
        issuer = createPartyNode(CordaX500Name("Issuer", "London", "GB"))
        alice = createPartyNode(CordaX500Name("Alice", "London", "GB"))
        bob = createPartyNode(CordaX500Name("Bob", "London", "GB"))

        trustee.setPermissions(issuer, net)
    }

    @Test
    @Ignore("The test not represents the logic it should")
    fun `grocery store example`() {
        val ministry: StartedNode<InternalMockNetwork.MockNode> = issuer
        val alice: StartedNode<MockNode> = alice
        val store: StartedNode<MockNode> = bob

        // Each Corda node has a X500 name:

        val ministryX500 = ministry.getName()
        val aliceX500 = alice.getName()

        // And each Indy node has a DID, a.k.a Decentralized ID:

        val ministryDID = ministry.getPartyDid()
        val aliceDID = alice.getPartyDid()

        // To allow customers and shops to communicate, Ministry issues a shopping scheme:

        val schema = ministry.services.startFlow(
            net,
            CreateSchemaFlow.Authority(
                "shopping scheme",
                "1.0",
                listOf("NAME", "BORN")
            )
        ).resultFuture.get()
        val schemaId = schema.getSchemaIdObject()

        // Ministry creates a credential definition for the shopping scheme:

        val credentialDefinition = ministry.services.startFlow(
            net,
            CreateCredentialDefinitionFlow.Authority(schemaId, enableRevocation = false)
        ).resultFuture.get()
        val credentialDefinitionId = credentialDefinition.getCredentialDefinitionIdObject()

        // Ministry verifies Alice's legal status and issues her a shopping credential:

        ministry.services.startFlow(
            net,
            IssueCredentialFlowB2B.Issuer(aliceX500, credentialDefinitionId, null) {
                attributes["NAME"] = CredentialValue("Alice")
                attributes["BORN"] = CredentialValue("2000")
            }
        ).resultFuture.get()

        // When Alice comes to grocery store, the store asks Alice to verify that she is legally allowed to buy drinks:

        // Alice.BORN >= currentYear - 18
        val eighteenYearsAgo = LocalDateTime.now().minusYears(18).year

        // Use special proof request DSL
        val proofRequest = proofRequest("legal age proof", "1.0") {
            proveGreaterThan("BORN", eighteenYearsAgo)
        }

        val verified = store.services.startFlow(
            net,
            VerifyCredentialFlowB2B.Verifier(aliceX500, proofRequest)
        ).resultFuture.get()

        // If the verification succeeds, the store can be sure that Alice's age is above 18.

        println("You can buy drinks: $verified")
    }
}
