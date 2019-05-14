package com.luxoft.blockchainlab.corda.hyperledger.indy


import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.*
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2b.*
import com.luxoft.blockchainlab.hyperledger.indy.models.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.getOrThrow
import net.corda.node.internal.StartedNode
import net.corda.testing.node.internal.InternalMockNetwork.MockNode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.util.*


class CordentityE2E : CordaTestBase() {

    private lateinit var trustee: StartedNode<MockNode>
    private lateinit var notary: StartedNode<MockNode>
    private lateinit var issuer: StartedNode<MockNode>
    private lateinit var alice: StartedNode<MockNode>
    private lateinit var bob: StartedNode<MockNode>

    @Before
    fun setup() {
        notary = net.defaultNotaryNode

        trustee = createPartyNode(CordaX500Name("Trustee", "London", "GB"))
        issuer = createPartyNode(CordaX500Name("Issuer", "London", "GB"))
        alice = createPartyNode(CordaX500Name("Alice", "London", "GB"))
        bob = createPartyNode(CordaX500Name("Bob", "London", "GB"))

        // Request permissions from trustee to write on ledger
        setPermissions(issuer, trustee)
        setPermissions(bob, trustee)
    }

    private fun issueSchema(schemaOwner: StartedNode<MockNode>, schema: Schema): SchemaId {
        val schemaFuture = schemaOwner.services.startFlow(
            CreateSchemaFlow.Authority(schema.schemaName, schema.schemaVersion, schema.schemaAttrs)
        ).resultFuture

        return schemaFuture.getOrThrow(Duration.ofSeconds(30))
    }

    private fun issueCredentialDefinition(
        credentialDefOwner: StartedNode<MockNode>,
        schemaId: SchemaId,
        enableRevocation: Boolean
    ): CredentialDefinitionId {
        val credentialDefFuture = credentialDefOwner.services.startFlow(
            CreateCredentialDefinitionFlow.Authority(schemaId, enableRevocation)
        ).resultFuture

        return credentialDefFuture.getOrThrow(Duration.ofSeconds(30))
    }

    private fun issueRevocationRegistry(
        revocationRegistryOwner: StartedNode<MockNode>,
        credentialDefId: CredentialDefinitionId,
        credMaxNumber: Int = 5
    ): RevocationRegistryDefinitionId {
        val revocationRegistryFuture = revocationRegistryOwner.services.startFlow(
            CreateRevocationRegistryFlow.Authority(credentialDefId, credMaxNumber)
        ).resultFuture

        return revocationRegistryFuture.getOrThrow(Duration.ofSeconds(30))
    }

    private fun issueMetadata(
        metadataOwner: StartedNode<MockNode>,
        schema: Schema,
        credMaxNumber: Int = 5
    ): Triple<SchemaId, CredentialDefinitionId, RevocationRegistryDefinitionId> {
        val metadataFuture = metadataOwner.services.startFlow(
            CreateCredentialMetadataFlow.Authority(schema.schemaName, schema.schemaVersion, schema.schemaAttrs, credMaxNumber)
        ).resultFuture

        return metadataFuture.getOrThrow(Duration.ofSeconds(90))
    }

    private fun issueCredential(
        credentialProver: StartedNode<MockNode>,
        credentialIssuer: StartedNode<MockNode>,
        credentialDefId: CredentialDefinitionId,
        revocationRegistryId: RevocationRegistryDefinitionId?,
        credentialProposalFiller: CredentialProposal.() -> Unit
    ): String {

        val identifier = UUID.randomUUID().toString()

        val credentialFuture = credentialIssuer.services.startFlow(
            IssueCredentialFlowB2B.Issuer(
                identifier,
                credentialDefId,
                revocationRegistryId,
                credentialProver.getName(),
                credentialProposalFiller
            )
        ).resultFuture

        credentialFuture.getOrThrow(Duration.ofSeconds(30))

        return identifier
    }

    private fun revokeCredential(
        issuer: StartedNode<MockNode>,
        credentialId: String
    ) {
        val flowResult = issuer.services.startFlow(
            RevokeCredentialFlow.Issuer(credentialId)
        ).resultFuture

        flowResult.getOrThrow(Duration.ofSeconds(30))
    }

    private fun verifyCredential(
        verifier: StartedNode<MockNode>,
        prover: StartedNode<MockNode>,
        proofRequest: ProofRequest
    ): Boolean {
        val identifier = UUID.randomUUID().toString()

        val proofCheckResultFuture = verifier.services.startFlow(
            VerifyCredentialFlowB2B.Verifier(identifier, prover.getName(), proofRequest)
        ).resultFuture

        return proofCheckResultFuture.getOrThrow(Duration.ofSeconds(30))
    }

    private fun multipleCredentialsByDiffIssuers(attrs: Map<String, String>, preds: Map<String, String>): Boolean {

        val (attr1, attr2) = attrs.entries.toList()
        val (pred1, pred2) = preds.entries.toList()

        // Issue schemas and credentialDefs
        val schemaPerson = SchemaPerson()
        val schemaEducation = SchemaEducation()

        val personSchemaId = issueSchema(issuer, schemaPerson)
        val educationSchemaId = issueSchema(bob, schemaEducation)

        val personCredentialDefId = issueCredentialDefinition(issuer, personSchemaId, true)
        val educationCredentialDefId = issueCredentialDefinition(bob, educationSchemaId, true)

        val personRevocationRegistryId = issueRevocationRegistry(issuer, personCredentialDefId)
        val educationRevocationRegistryId = issueRevocationRegistry(bob, educationCredentialDefId)

        // Issue credential #1
        issueCredential(alice, issuer, personCredentialDefId, personRevocationRegistryId) {
            attributes["attr1"] = CredentialValue(attr1.key)
            attributes["attr2"] = CredentialValue(pred1.key)
        }

        // Issue credential #2
        issueCredential(alice, bob, educationCredentialDefId, educationRevocationRegistryId) {
            attributes["attrX"] = CredentialValue(attr2.key)
            attributes["attrY"] = CredentialValue(pred2.key)
        }

        // Verify credentials
        val proofRequest = proofRequest("test proof request", "0.1") {
            reveal(schemaPerson.schemaAttr1) {
                FilterProperty.Value shouldBe attr1.value
                FilterProperty.SchemaId shouldBe personSchemaId.toString()
                FilterProperty.CredentialDefinitionId shouldBe personCredentialDefId.toString()
            }

            reveal(schemaEducation.schemaAttr1) {
                FilterProperty.Value shouldBe attr2.value
                FilterProperty.SchemaId shouldBe educationSchemaId.toString()
                FilterProperty.CredentialDefinitionId shouldBe educationCredentialDefId.toString()
            }

            proveGreaterThan(schemaPerson.schemaAttr2, pred1.value.toInt()) {
                FilterProperty.SchemaId shouldBe personSchemaId.toString()
                FilterProperty.CredentialDefinitionId shouldBe personCredentialDefId.toString()
            }

            proveGreaterThan(schemaEducation.schemaAttr2, pred2.value.toInt()) {
                FilterProperty.SchemaId shouldBe educationSchemaId.toString()
                FilterProperty.CredentialDefinitionId shouldBe educationCredentialDefId.toString()
            }

            proveNonRevocation(Interval.allTime())
        }

        return verifyCredential(bob, alice, proofRequest)
    }

    @Test
    fun `2 issuers 1 prover 2 credentials with revocation setup works fine`() {
        val attributes = mapOf(
            "John Smith" to "John Smith",
            "University" to "University"
        )
        val predicates = mapOf(
            "1988" to "1978",
            "2016" to "2006"
        )

        val credentialsVerified = multipleCredentialsByDiffIssuers(attributes, predicates)
        assertTrue(credentialsVerified)
    }

    @Test
    fun `2 issuers 1 prover 2 credentials with revocation invalid predicates setup works fine`() {
        val attributes = mapOf(
            "John Smith" to "John Smith",
            "University" to "University"
        )
        val predicates = mapOf(
            "1988" to "1978",
            "2016" to "2026"
        )

        val credentialsVerified = multipleCredentialsByDiffIssuers(attributes, predicates)
        assertFalse(credentialsVerified)
    }

    @Test
    fun `2 issuers 1 prover 2 credentials with revocation invalid attributes setup works fine`() {
        val attributes = mapOf(
            "John Smith" to "Vanga",
            "University" to "University"
        )
        val predicates = mapOf(
            "1988" to "1978",
            "2016" to "2006"
        )

        val credentialsVerified = multipleCredentialsByDiffIssuers(attributes, predicates)
        assertFalse(credentialsVerified)
    }

    @Test
    fun `1 credential 1 prover without revocation setup works fine`() {

        val schemaPerson = SchemaPerson()

        // issue schema
        val schemaId = issueSchema(issuer, schemaPerson)

        // issuer credential definition
        val credentialDefId = issueCredentialDefinition(issuer, schemaId, false)

        // Issue credential
        val schemaAttrInt = "1988"

        issueCredential(alice, issuer, credentialDefId, null) {
            attributes["attr1"] = CredentialValue("John Smith")
            attributes["attr2"] = CredentialValue(schemaAttrInt)
        }

        // Verify credential
        val proofRequest = proofRequest("test proof request", "0.1") {
            reveal(schemaPerson.schemaAttr1) {
                FilterProperty.Value shouldBe "John Smith"
                FilterProperty.SchemaId shouldBe schemaId.toString()
                FilterProperty.CredentialDefinitionId shouldBe credentialDefId.toString()
            }

            proveGreaterThan(schemaPerson.schemaAttr2, schemaAttrInt.toInt() - 10) {
                FilterProperty.SchemaId shouldBe schemaId.toString()
                FilterProperty.CredentialDefinitionId shouldBe credentialDefId.toString()
            }
        }

        val credentialVerified = verifyCredential(bob, alice, proofRequest)
        assertTrue(credentialVerified)
    }

    @Test
    fun `revocation works fine`() {

        val schemaPerson = SchemaPerson()

        val (schemaId, credentialDefinitionId, revocationRegistryId) = issueMetadata(issuer, schemaPerson)

        // Issue credential
        val schemaAttrInt = "1988"

        val credentialId = issueCredential(alice, issuer, credentialDefinitionId, revocationRegistryId) {
            attributes["attr1"] = CredentialValue("John Smith")
            attributes["attr2"] = CredentialValue(schemaAttrInt)
        }

        // Verify credential
        val proofRequest = proofRequest("test proof request", "0.1") {
            reveal(schemaPerson.schemaAttr1) {
                FilterProperty.Value shouldBe "John Smith"
                FilterProperty.SchemaId shouldBe schemaId.toString()
                FilterProperty.CredentialDefinitionId shouldBe credentialDefinitionId.toString()
            }

            proveGreaterThan(schemaPerson.schemaAttr2, schemaAttrInt.toInt() - 10) {
                FilterProperty.SchemaId shouldBe schemaId.toString()
                FilterProperty.CredentialDefinitionId shouldBe credentialDefinitionId.toString()
            }

            proveNonRevocation(Interval.allTime())
        }

        val credentialVerified = verifyCredential(bob, alice, proofRequest)
        assertTrue(credentialVerified)

        revokeCredential(issuer, credentialId)

        // modifying the proof request
        proofRequest.nonRevoked = Interval.now()
        proofRequest.version = "0.2"

        val credentialAfterRevocationVerified = verifyCredential(bob, alice, proofRequest)
        assertFalse(credentialAfterRevocationVerified)
    }

    @Test
    fun `2 credentials 1 issuer 1 prover without revocation setup works fine`() {

        val schemaPerson = SchemaPerson()
        val schemaEducation = SchemaEducation()

        val personSchemaId = issueSchema(issuer, schemaPerson)
        val educationSchemaId = issueSchema(issuer, schemaEducation)

        val personCredentialDefId = issueCredentialDefinition(issuer, personSchemaId, false)
        val educationCredentialDefId = issueCredentialDefinition(issuer, educationSchemaId, false)

        // Issue credential #1
        val schemaPersonAttrInt = "1988"

        val attr1PersonValue = "John Smith"
        issueCredential(alice, issuer, personCredentialDefId, null) {
            attributes[schemaPerson.schemaAttr1] = CredentialValue(attr1PersonValue)
            attributes[schemaPerson.schemaAttr2] = CredentialValue(schemaPersonAttrInt)
        }

        // Issue credential #2
        val schemaEducationAttrInt = "2016"

        val attr1EducationValue = "KKK"
        issueCredential(alice, issuer, educationCredentialDefId, null) {
            attributes[schemaEducation.schemaAttr1] = CredentialValue(attr1EducationValue)
            attributes[schemaEducation.schemaAttr2] = CredentialValue(schemaEducationAttrInt)
        }

        // Verify credentials
        val proofRequest = proofRequest("test proof request", "0.1") {
            reveal(schemaPerson.schemaAttr1) {
                FilterProperty.Value shouldBe attr1PersonValue
                FilterProperty.SchemaId shouldBe personSchemaId.toString()
                FilterProperty.CredentialDefinitionId shouldBe personCredentialDefId.toString()
            }

            reveal(schemaEducation.schemaAttr1) {
                FilterProperty.Value shouldBe attr1EducationValue
                FilterProperty.SchemaId shouldBe educationSchemaId.toString()
                FilterProperty.CredentialDefinitionId shouldBe educationCredentialDefId.toString()
            }

            proveGreaterThan(schemaPerson.schemaAttr2, schemaPersonAttrInt.toInt() - 10) {
                FilterProperty.SchemaId shouldBe personSchemaId.toString()
                FilterProperty.CredentialDefinitionId shouldBe personCredentialDefId.toString()
            }

            proveGreaterThan(schemaEducation.schemaAttr2, schemaEducationAttrInt.toInt() - 10) {
                FilterProperty.SchemaId shouldBe educationSchemaId.toString()
                FilterProperty.CredentialDefinitionId shouldBe educationCredentialDefId.toString()
            }
        }

        val credentialVerified = verifyCredential(issuer, alice, proofRequest)
        assertTrue(credentialVerified)
    }

    @Test
    fun `1 credential 1 prover without predicates and revocation setup works fine`() {

        val schemaPerson = SchemaPerson()

        val schemaId = issueSchema(issuer, schemaPerson)
        val credentialDefId = issueCredentialDefinition(issuer, schemaId, false)

        // Issue credential
        val schemaAttrInt = "1988"
        issueCredential(alice, issuer, credentialDefId, null) {
            attributes["attr1"] = CredentialValue("John Smith")
            attributes["attr2"] = CredentialValue(schemaAttrInt)
        }

        // Verify credential
        val proofRequest = proofRequest("test proof request", "0.1") {
            reveal(schemaPerson.schemaAttr1) {
                FilterProperty.Value shouldBe "John Smith"
                FilterProperty.SchemaId shouldBe schemaId.toString()
                FilterProperty.CredentialDefinitionId shouldBe credentialDefId.toString()
            }
        }

        val credentialVerified = verifyCredential(bob, alice, proofRequest)
        assertTrue(credentialVerified)
    }

    @Test
    fun `1 credential 1 prover without revocation not all attributes to verify setup works fine`() {

        val schemaPerson = SchemaPerson()

        val schemaId = issueSchema(issuer, schemaPerson)
        val credentialDefId = issueCredentialDefinition(issuer, schemaId, false)

        // Issue credential
        val schemaAttrInt = "1988"

        issueCredential(alice, issuer, credentialDefId, null) {
            attributes["attr1"] = CredentialValue("John Smith")
            attributes["attr2"] = CredentialValue(schemaAttrInt)
        }

        // Verify credential
        val proofRequest = proofRequest("test proof request", "0.1") {
            reveal(schemaPerson.schemaAttr1) {
                FilterProperty.Value shouldBe "John Smith"
                FilterProperty.SchemaId shouldBe schemaId.toString()
                FilterProperty.CredentialDefinitionId shouldBe credentialDefId.toString()
            }

            reveal(schemaPerson.schemaAttr2) {
                FilterProperty.Value shouldBe ""
                FilterProperty.SchemaId shouldBe schemaId.toString()
                FilterProperty.CredentialDefinitionId shouldBe credentialDefId.toString()
            }
        }

        val credentialVerified = verifyCredential(bob, alice, proofRequest)
        assertTrue(credentialVerified)
    }
}
