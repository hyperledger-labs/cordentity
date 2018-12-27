package com.luxoft.blockchainlab.corda.hyperledger.indy


import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.*
import com.luxoft.blockchainlab.hyperledger.indy.CredentialDefinitionId
import com.luxoft.blockchainlab.hyperledger.indy.Interval
import com.luxoft.blockchainlab.hyperledger.indy.SchemaId
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
        schemaId: SchemaId
    ): CredentialDefinitionId {
        val credentialDefFuture = credentialDefOwner.services.startFlow(
            CreateCredentialDefinitionFlow.Authority(schemaId)
        ).resultFuture

        return credentialDefFuture.getOrThrow(Duration.ofSeconds(30))
    }

    private fun issueCredential(
        credentialProver: StartedNode<MockNode>,
        credentialIssuer: StartedNode<MockNode>,
        credentialProposal: String,
        credentialDefId: CredentialDefinitionId
    ): String {

        val identifier = UUID.randomUUID().toString()

        val credentialFuture = credentialIssuer.services.startFlow(
            IssueCredentialFlow.Issuer(
                identifier,
                credentialProposal,
                credentialDefId,
                credentialProver.getName()
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
        attributes: List<VerifyCredentialFlow.ProofAttribute>,
        predicates: List<VerifyCredentialFlow.ProofPredicate>,
        nonRevoked: Interval? = null
    ): Boolean {
        val identifier = UUID.randomUUID().toString()

        val proofCheckResultFuture = verifier.services.startFlow(
            VerifyCredentialFlow.Verifier(
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

        val personCredentialDefId = issueCredentialDefinition(issuer, personSchemaId)
        val educationCredentialDefId = issueCredentialDefinition(bob, educationSchemaId)

        // Issue credential #1
        var credentialProposal = schemaPerson.formatProposal(attr1.key, "119191919", pred1.key, pred1.key)

        issueCredential(alice, issuer, credentialProposal, personCredentialDefId)

        // Issue credential #2
        credentialProposal = schemaEducation.formatProposal(attr2.key, "119191918", pred2.key, pred2.key)

        issueCredential(alice, bob, credentialProposal, educationCredentialDefId)

        // Verify credentials
        val attributes = listOf(
            VerifyCredentialFlow.ProofAttribute(
                personSchemaId,
                personCredentialDefId,
                schemaPerson.schemaAttr1,
                attr1.value
            ),
            VerifyCredentialFlow.ProofAttribute(
                educationSchemaId,
                educationCredentialDefId,
                schemaEducation.schemaAttr1,
                attr2.value
            )
        )

        val predicates = listOf(
            VerifyCredentialFlow.ProofPredicate(
                personSchemaId,
                personCredentialDefId,
                schemaPerson.schemaAttr2,
                pred1.value.toInt()
            ),
            VerifyCredentialFlow.ProofPredicate(
                educationSchemaId,
                educationCredentialDefId,
                schemaEducation.schemaAttr2,
                pred2.value.toInt()
            )
        )

        return verifyCredential(bob, alice, attributes, predicates, Interval.allTime())
    }

    @Test
    fun `2 issuers 1 prover 2 credentials setup works fine`() {
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
    fun `2 issuers 1 prover 2 credentials invalid predicates setup works fine`() {
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
    fun `2 issuers 1 prover 2 credentials invalid attributes setup works fine`() {
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
    fun `1 credential 1 prover setup works fine`() {

        val schemaPerson = SchemaPerson()

        // issue schema
        val schemaId = issueSchema(issuer, schemaPerson)

        // issuer credential definition
        val credentialDefId = issueCredentialDefinition(issuer, schemaId)

        // Issue credential
        val schemaAttrInt = "1988"
        val credentialProposal = schemaPerson.formatProposal("John Smith", "119191919", schemaAttrInt, schemaAttrInt)

        issueCredential(alice, issuer, credentialProposal, credentialDefId)

        // Verify credential
        val attributes = listOf(
            VerifyCredentialFlow.ProofAttribute(
                schemaId,
                credentialDefId,
                schemaPerson.schemaAttr1,
                "John Smith"
            )
        )

        val predicates = listOf(
            // -10 to check >=
            VerifyCredentialFlow.ProofPredicate(
                schemaId,
                credentialDefId,
                schemaPerson.schemaAttr2,
                schemaAttrInt.toInt() - 10
            )
        )

        val credentialVerified = verifyCredential(bob, alice, attributes, predicates, Interval.allTime())
        assertTrue(credentialVerified)
    }

    @Test
    fun `revocation works fine`() {

        val schemaPerson = SchemaPerson()

        // issue schema
        val schemaId = issueSchema(issuer, schemaPerson)

        // issuer credential definition
        val credentialDefinitionId = issueCredentialDefinition(issuer, schemaId)

        // Issue credential
        val schemaAttrInt = "1988"
        val credentialProposal = schemaPerson.formatProposal("John Smith", "119191919", schemaAttrInt, schemaAttrInt)

        val credentialId =
            issueCredential(alice, issuer, credentialProposal, credentialDefinitionId)

        // Verify credential
        val attributes = listOf(
            VerifyCredentialFlow.ProofAttribute(
                schemaId,
                credentialDefinitionId,
                schemaPerson.schemaAttr1,
                "John Smith"
            )
        )

        val predicates = listOf(
            // -10 to check >=
            VerifyCredentialFlow.ProofPredicate(
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

        val credentialAfterRevocationVerified = verifyCredential(bob, alice, attributes, predicates, Interval.recent())
        assertFalse(credentialAfterRevocationVerified)
    }

    @Test
    fun `2 credentials 1 issuer 1 prover setup works fine`() {

        val schemaPerson = SchemaPerson()
        val schemaEducation = SchemaEducation()

        val personSchemaId = issueSchema(issuer, schemaPerson)
        val educationSchemaId = issueSchema(issuer, schemaEducation)

        val personCredentialDefId = issueCredentialDefinition(issuer, personSchemaId)
        val educationCredentialDefId = issueCredentialDefinition(issuer, educationSchemaId)

        // Issue credential #1
        val schemaPersonAttrInt = "1988"
        var credentialProposal =
            schemaPerson.formatProposal("John Smith", "119191919", schemaPersonAttrInt, schemaPersonAttrInt)

        issueCredential(alice, issuer, credentialProposal, personCredentialDefId)

        // Issue credential #2
        val schemaEducationAttrInt = "2016"
        credentialProposal = schemaEducation.formatProposal(
            "University",
            "119191918",
            schemaEducationAttrInt,
            schemaEducationAttrInt
        )

        issueCredential(alice, issuer, credentialProposal, educationCredentialDefId)

        // Verify credentials
        val attributes = listOf(
            VerifyCredentialFlow.ProofAttribute(
                personSchemaId,
                personCredentialDefId,
                schemaPerson.schemaAttr1,
                "John Smith"
            ),
            VerifyCredentialFlow.ProofAttribute(
                educationSchemaId,
                educationCredentialDefId,
                schemaEducation.schemaAttr1,
                "University"
            )
        )

        val predicates = listOf(
            // -10 to check >=
            VerifyCredentialFlow.ProofPredicate(
                personSchemaId,
                personCredentialDefId,
                schemaPerson.schemaAttr2,
                schemaPersonAttrInt.toInt() - 10
            ),
            VerifyCredentialFlow.ProofPredicate(
                educationSchemaId,
                educationCredentialDefId,
                schemaEducation.schemaAttr2,
                schemaEducationAttrInt.toInt() - 10
            )
        )

        val credentialVerified = verifyCredential(bob, alice, attributes, predicates, Interval.allTime())
        assertTrue(credentialVerified)
    }

    @Test
    fun `1 credential 1 prover without predicates setup works fine`() {

        val schemaPerson = SchemaPerson()

        val schemaId = issueSchema(issuer, schemaPerson)
        val credentialDefId = issueCredentialDefinition(issuer, schemaId)

        // Issue credential
        val schemaAttrInt = "1988"
        val credentialProposal = schemaPerson.formatProposal("John Smith", "119191919", schemaAttrInt, schemaAttrInt)

        issueCredential(alice, issuer, credentialProposal, credentialDefId)

        // Verify credential
        val attributes = listOf(
            VerifyCredentialFlow.ProofAttribute(
                schemaId,
                credentialDefId,
                schemaPerson.schemaAttr1,
                "John Smith"
            )
        )

        val credentialVerified = verifyCredential(bob, alice, attributes, emptyList(), Interval.allTime())
        assertTrue(credentialVerified)
    }

    @Test
    fun `1 credential 1 prover not all attributes to verify setup works fine`() {

        val schemaPerson = SchemaPerson()

        val schemaId = issueSchema(issuer, schemaPerson)
        val credentialDefId = issueCredentialDefinition(issuer, schemaId)

        // Issue credential
        val schemaAttrInt = "1988"
        val credentialProposal = schemaPerson.formatProposal("John Smith", "119191919", schemaAttrInt, schemaAttrInt)

        issueCredential(alice, issuer, credentialProposal, credentialDefId)

        // Verify credential
        val attributes = listOf(
            VerifyCredentialFlow.ProofAttribute(
                schemaId,
                credentialDefId,
                schemaPerson.schemaAttr1,
                "John Smith"
            ),
            VerifyCredentialFlow.ProofAttribute(
                schemaId,
                credentialDefId,
                schemaPerson.schemaAttr2,
                ""
            )
        )

        val credentialVerified = verifyCredential(bob, alice, attributes, emptyList(), Interval.allTime())
        assertTrue(credentialVerified)
    }
}