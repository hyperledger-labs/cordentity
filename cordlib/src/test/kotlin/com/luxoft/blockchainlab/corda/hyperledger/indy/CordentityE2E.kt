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
        credentialProposalProvider: () -> CredentialProposal
    ): String {

        val identifier = UUID.randomUUID().toString()

        val credentialFuture = credentialIssuer.services.startFlow(
            IssueCredentialFlowB2B.Issuer(
                identifier,
                credentialDefId,
                revocationRegistryId,
                credentialProver.getName(),
                credentialProposalProvider
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
            attributes: List<ProofAttribute>,
            predicates: List<ProofPredicate>,
            nonRevoked: Interval?
    ): Boolean {
        val identifier = UUID.randomUUID().toString()

        val proofCheckResultFuture = verifier.services.startFlow(
            VerifyCredentialFlowB2B.Verifier(
                identifier,
                attributes,
                predicates,
                prover.getName(),
                nonRevoked
            )
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
        issueCredential(alice, issuer, personCredentialDefId, personRevocationRegistryId) { mapOf(
            "attr1" to CredentialValue(attr1.key),
            "attr2" to CredentialValue(pred1.key)
        ) }

        // Issue credential #2
        issueCredential(alice, bob, educationCredentialDefId, educationRevocationRegistryId) { mapOf(
            "attrX" to CredentialValue(attr2.key),
            "attrY" to CredentialValue(pred2.key)
        ) }

        // Verify credentials
        val attributes = listOf(
            ProofAttribute(
                personSchemaId,
                personCredentialDefId,
                schemaPerson.schemaAttr1,
                attr1.value
            ),
            ProofAttribute(
                educationSchemaId,
                educationCredentialDefId,
                schemaEducation.schemaAttr1,
                attr2.value
            )
        )

        val predicates = listOf(
            ProofPredicate(
                personSchemaId,
                personCredentialDefId,
                schemaPerson.schemaAttr2,
                pred1.value.toInt()
            ),
            ProofPredicate(
                educationSchemaId,
                educationCredentialDefId,
                schemaEducation.schemaAttr2,
                pred2.value.toInt()
            )
        )

        return verifyCredential(bob, alice, attributes, predicates, Interval.allTime())
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

        issueCredential(alice, issuer, credentialDefId, null) { mapOf(
            "attr1" to CredentialValue("John Smith"),
            "attr2" to CredentialValue(schemaAttrInt)
        ) }

        // Verify credential
        val attributes = listOf(
            ProofAttribute(
                schemaId,
                credentialDefId,
                schemaPerson.schemaAttr1,
                "John Smith"
            )
        )

        val predicates = listOf(
            // -10 to check >=
            ProofPredicate(
                schemaId,
                credentialDefId,
                schemaPerson.schemaAttr2,
                schemaAttrInt.toInt() - 10
            )
        )

        val credentialVerified = verifyCredential(bob, alice, attributes, predicates, null)
        assertTrue(credentialVerified)
    }

    @Test
    fun `revocation works fine`() {

        val schemaPerson = SchemaPerson()

        val (schemaId, credentialDefinitionId, revocationRegistryId) = issueMetadata(issuer, schemaPerson)

        // Issue credential
        val schemaAttrInt = "1988"

        val credentialId = issueCredential(alice, issuer, credentialDefinitionId, revocationRegistryId) { mapOf(
            "attr1" to CredentialValue("John Smith"),
            "attr2" to CredentialValue(schemaAttrInt)
        ) }

        // Verify credential
        val attributes = listOf(
            ProofAttribute(
                schemaId,
                credentialDefinitionId,
                schemaPerson.schemaAttr1,
                "John Smith"
            )
        )

        val predicates = listOf(
            // -10 to check >=
            ProofPredicate(
                schemaId,
                credentialDefinitionId,
                schemaPerson.schemaAttr2,
                schemaAttrInt.toInt() - 10
            )
        )

        val credentialVerified = verifyCredential(bob, alice, attributes, predicates, Interval.allTime())
        assertTrue(credentialVerified)

        revokeCredential(issuer, credentialId)

        Thread.sleep(3000)

        val credentialAfterRevocationVerified = verifyCredential(bob, alice, attributes, predicates, Interval.now())
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

        issueCredential(alice, issuer, personCredentialDefId, null) { mapOf(
            "attr1" to CredentialValue("John Smith"),
            "attr2" to CredentialValue(schemaPersonAttrInt)
        ) }

        // Issue credential #2
        val schemaEducationAttrInt = "2016"

        issueCredential(alice, issuer, educationCredentialDefId, null) { mapOf(
            "attrX" to CredentialValue("University"),
            "attrY" to CredentialValue(schemaEducationAttrInt)
        ) }

        // Verify credentials
        val attributes = listOf(
            ProofAttribute(
                personSchemaId,
                personCredentialDefId,
                schemaPerson.schemaAttr1,
                "John Smith"
            ),
            ProofAttribute(
                educationSchemaId,
                educationCredentialDefId,
                schemaEducation.schemaAttr1,
                "University"
            )
        )

        val predicates = listOf(
            // -10 to check >=
            ProofPredicate(
                personSchemaId,
                personCredentialDefId,
                schemaPerson.schemaAttr2,
                schemaPersonAttrInt.toInt() - 10
            ),
            ProofPredicate(
                educationSchemaId,
                educationCredentialDefId,
                schemaEducation.schemaAttr2,
                schemaEducationAttrInt.toInt() - 10
            )
        )

        val credentialVerified = verifyCredential(issuer, alice, attributes, predicates, null)
        assertTrue(credentialVerified)
    }

    @Test
    fun `1 credential 1 prover without predicates and revocation setup works fine`() {

        val schemaPerson = SchemaPerson()

        val schemaId = issueSchema(issuer, schemaPerson)
        val credentialDefId = issueCredentialDefinition(issuer, schemaId, false)

        // Issue credential
        val schemaAttrInt = "1988"
        issueCredential(alice, issuer, credentialDefId, null) { mapOf(
            "attr1" to CredentialValue("John Smith"),
            "attr2" to CredentialValue(schemaAttrInt)
        ) }

        // Verify credential
        val attributes = listOf(
            ProofAttribute(
                schemaId,
                credentialDefId,
                schemaPerson.schemaAttr1,
                "John Smith"
            )
        )

        val credentialVerified = verifyCredential(bob, alice, attributes, emptyList(), null)
        assertTrue(credentialVerified)
    }

    @Test
    fun `1 credential 1 prover without revocation not all attributes to verify setup works fine`() {

        val schemaPerson = SchemaPerson()

        val schemaId = issueSchema(issuer, schemaPerson)
        val credentialDefId = issueCredentialDefinition(issuer, schemaId, false)

        // Issue credential
        val schemaAttrInt = "1988"

        issueCredential(alice, issuer, credentialDefId, null) { mapOf(
            "attr1" to CredentialValue("John Smith"),
            "attr2" to CredentialValue(schemaAttrInt)
        ) }

        // Verify credential
        val attributes = listOf(
            ProofAttribute(
                schemaId,
                credentialDefId,
                schemaPerson.schemaAttr1,
                "John Smith"
            ),
            ProofAttribute(
                schemaId,
                credentialDefId,
                schemaPerson.schemaAttr2,
                ""
            )
        )

        val credentialVerified = verifyCredential(bob, alice, attributes, emptyList(), null)
        assertTrue(credentialVerified)
    }
}
