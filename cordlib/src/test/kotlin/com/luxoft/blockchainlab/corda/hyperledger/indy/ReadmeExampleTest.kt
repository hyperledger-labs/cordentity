package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.CreateCredentialDefinitionFlow
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.CreateSchemaFlow
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2b.IssueCredentialFlowB2B
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2b.VerifyCredentialFlowB2B
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.name
import com.luxoft.blockchainlab.hyperledger.indy.models.CredentialValue
import com.luxoft.blockchainlab.hyperledger.indy.utils.proofRequest
import com.luxoft.blockchainlab.hyperledger.indy.utils.proveGreaterThan
import net.corda.core.identity.CordaX500Name
import net.corda.testing.node.StartedMockNode
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.time.LocalDateTime


class ReadmeExampleTest : CordentityTestBase() {

    private lateinit var issuer: StartedMockNode
    private lateinit var alice: StartedMockNode
    private lateinit var bob: StartedMockNode

    @Before
    fun setup() {
        trustee = createPartyNode(CordaX500Name("Trustee", "London", "GB"))
        issuer = createPartyNode(CordaX500Name("Issuer", "London", "GB"))
        alice = createPartyNode(CordaX500Name("Alice", "London", "GB"))
        bob = createPartyNode(CordaX500Name("Bob", "London", "GB"))

        trustee.setPermissions(issuer)
    }

    @Test
    @Ignore("The test not represents the logic it should")
    fun `grocery store example`() {
        val ministry: StartedMockNode = issuer
        val alice: StartedMockNode = alice
        val store: StartedMockNode = bob

        // Each Corda node has a X500 name:

        val ministryX500 = ministry.info.name()
        val aliceX500 = alice.info.name()

        // And each Indy node has a DID, a.k.a Decentralized ID:

        val ministryDID = ministry.getPartyDid()
        val aliceDID = alice.getPartyDid()

        // To allow customers and shops to communicate, Ministry issues a shopping scheme:

        val schema = ministry.runFlow(
            CreateSchemaFlow.Authority(
                "shopping scheme",
                "1.0",
                listOf("NAME", "BORN")
            )
        ).get()
        val schemaId = schema.getSchemaIdObject()

        // Ministry creates a credential definition for the shopping scheme:

        val credentialDefinition = ministry.runFlow(
            CreateCredentialDefinitionFlow.Authority(schemaId, enableRevocation = false)
        ).get()
        val credentialDefinitionId = credentialDefinition.getCredentialDefinitionIdObject()

        // Ministry verifies Alice's legal status and issues her a shopping credential:

        ministry.runFlow(
            IssueCredentialFlowB2B.Issuer(aliceX500, credentialDefinitionId, null) {
                attributes["NAME"] = CredentialValue("Alice")
                attributes["BORN"] = CredentialValue("2000")
            }
        ).get()

        // When Alice comes to grocery store, the store asks Alice to verify that she is legally allowed to buy drinks:

        // Alice.BORN >= currentYear - 18
        val eighteenYearsAgo = LocalDateTime.now().minusYears(18).year

        // Use special proof request DSL
        val proofRequest = proofRequest("legal age proof", "1.0") {
            proveGreaterThan("BORN", eighteenYearsAgo)
        }

        val verified = store.runFlow(
            VerifyCredentialFlowB2B.Verifier(aliceX500, proofRequest)
        ).get()

        // If the verification succeeds, the store can be sure that Alice's age is above 18.

        println("You can buy drinks: $verified")
    }
}
