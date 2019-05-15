package com.luxoft.blockchainlab.corda.hyperledger.indy


import com.fasterxml.jackson.annotation.JsonProperty
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.*
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2b.*
import com.luxoft.blockchainlab.hyperledger.indy.models.*
import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.getOrThrow
import net.corda.node.internal.StartedNode
import net.corda.testing.node.internal.InternalMockNetwork.MockNode
import net.corda.testing.node.internal.startFlow
import org.hyperledger.indy.sdk.wallet.Wallet
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.util.*
import kotlin.math.absoluteValue


class CordentityE2E : CordaTestBase() {

    private lateinit var trustee: StartedNode<MockNode>
    private lateinit var notary: StartedNode<MockNode>

    @Before
    fun setup() {
        notary = net.defaultNotaryNode

        trustee = createPartyNode(CordaX500Name("Trustee", "London", "GB"))
    }

    fun createNodes(name: String, count: Int) = (0 until count)
        .map { createPartyNode(CordaX500Name("$name-$it", "London", "GB")) }

    fun createIssuerNodes(trustee: StartedNode<MockNode>, count: Int) = (0 until count)
        .map { createPartyNode(CordaX500Name("Issuer-$it", "London", "GB")) }
        .map { setPermissions(it, trustee); it }

    fun StartedNode<MockNode>.issueRandomSchema(): Schema {
        val rng = Random()
        val name = "schema-${rng.nextInt().absoluteValue}"
        val version = "${rng.nextInt().absoluteValue}.${rng.nextInt().absoluteValue}"
        val attributeCount = rng.nextInt().absoluteValue % 3 + 2
        val attributes = (0 until attributeCount).map { "attribute-${rng.nextInt().absoluteValue}" }

        val schemaFuture = services.startFlow(
            CreateSchemaFlow.Authority(name, version, attributes)
        ).resultFuture

        return schemaFuture.getOrThrow(Duration.ofSeconds(30))
    }

    fun StartedNode<MockNode>.issueCredentialDefinition(schemaId: SchemaId, enableRevocation: Boolean): CredentialDefinition {
        val credDefFuture = services.startFlow(
            CreateCredentialDefinitionFlow.Authority(schemaId, enableRevocation)
        ).resultFuture

        return credDefFuture.getOrThrow(Duration.ofSeconds(30))
    }

    fun StartedNode<MockNode>.issueRevocationRegistry(credentialDefId: CredentialDefinitionId, credentialsLimit: Int): RevocationRegistryInfo {
        val revocationRegistryFuture = services.startFlow(
            CreateRevocationRegistryFlow.Authority(credentialDefId, credentialsLimit)
        ).resultFuture

        return revocationRegistryFuture.getOrThrow(Duration.ofSeconds(30))
    }

    fun StartedNode<MockNode>.issueRandomCredential(
        prover: StartedNode<MockNode>,
        schemaAttributes: List<String>,
        credentialDefId: CredentialDefinitionId,
        revocationRegistryId: RevocationRegistryDefinitionId?
    ): Pair<String, Map<String, String>> {
        val rng = Random()
        val attributesToValues = mutableMapOf<String, String>()

        val credentialFuture = services.startFlow(
            IssueCredentialFlowB2B.Issuer(prover.getName(), credentialDefId, revocationRegistryId) {
                schemaAttributes.forEach {
                    val value = rng.nextInt().absoluteValue.toString()
                    attributes[it] = CredentialValue(value)

                    // for test purposes
                    attributesToValues[it] = value
                }
            }
        ).resultFuture

        val credId = credentialFuture.getOrThrow(Duration.ofSeconds(30))

        return Pair(credId, attributesToValues)
    }

    fun StartedNode<MockNode>.verify(prover: StartedNode<MockNode>, proofRequest: ProofRequest): Pair<String?, Boolean> {
        val proofCheckResultFuture = services.startFlow(
            VerifyCredentialFlowB2B.Verifier(prover.getName(), proofRequest)
        ).resultFuture

        return proofCheckResultFuture.getOrThrow(Duration.ofSeconds(30))
    }

    data class ProofRequestPayload(
        val attributeValues: Map<String, String>,
        val schemaId: SchemaId,
        var credDefId: CredentialDefinitionId,
        var schemaIssuerDid: String,
        var schemaName: String,
        var schemaVersion: String,
        var issuerDid: String
    ) {
        companion object {
            fun create(
                attributeValues: Map<String, String>,
                issuerDid: String,
                schemaId: SchemaId,
                credentialDefId: CredentialDefinitionId
            ) = ProofRequestPayload(
                attributeValues,
                schemaId,
                credentialDefId,
                schemaId.did,
                schemaId.name,
                schemaId.version,
                issuerDid
            )
        }
    }

    fun ProofRequest.applyPayloadRandomly(payload: ProofRequestPayload, nonRevoked: Interval?) {
        val rng = Random()

        payload.attributeValues.entries.forEach attributeLoop@ { (key, value) ->
            val proveGE = rng.nextBoolean()
            if (proveGE) {
                val valueInt = value.toInt()
                val greaterThan = rng.nextInt().absoluteValue % valueInt

                proveGreaterThan(key, greaterThan) {
                    FilterProperty.values().forEach filterLoop@ {
                        val skip = rng.nextBoolean()
                        if (skip) return@filterLoop

                        when (it) {
                            FilterProperty.CredentialDefinitionId -> it shouldBe payload.credDefId.toString()
                            FilterProperty.SchemaId -> it shouldBe payload.schemaId.toString()
                            FilterProperty.IssuerDid -> it shouldBe payload.issuerDid
                            FilterProperty.SchemaIssuerDid -> it shouldBe payload.schemaIssuerDid
                            FilterProperty.SchemaName -> it shouldBe payload.schemaName
                            FilterProperty.SchemaVersion -> it shouldBe payload.schemaVersion
                        }
                    }
                }
            } else {
                reveal(key) {
                    FilterProperty.values().forEach filterLoop@ {
                        val skip = rng.nextBoolean()
                        if (skip) return@filterLoop

                        when (it) {
                            FilterProperty.Value -> it shouldBe value
                            FilterProperty.CredentialDefinitionId -> it shouldBe payload.credDefId.toString()
                            FilterProperty.SchemaId -> it shouldBe payload.schemaId.toString()
                            FilterProperty.IssuerDid -> it shouldBe payload.issuerDid
                            FilterProperty.SchemaIssuerDid -> it shouldBe payload.schemaIssuerDid
                            FilterProperty.SchemaName -> it shouldBe payload.schemaName
                            FilterProperty.SchemaVersion -> it shouldBe payload.schemaVersion
                        }
                    }
                }
            }

            if (nonRevoked != null) proveNonRevocation(nonRevoked)
        }
    }

    fun createRandomProofRequest(
        attributeValues: Map<String, String>,
        issuerDid: String,
        schemaId: SchemaId,
        credentialDefId: CredentialDefinitionId,
        nonRevoked: Interval?
    ): ProofRequest {
        val rng = Random()
        val name = "proof-request-${rng.nextInt().absoluteValue}"
        val version = "${rng.nextInt().absoluteValue}.${rng.nextInt().absoluteValue}"

        val prPayload = ProofRequestPayload.create(attributeValues, issuerDid, schemaId, credentialDefId)

        return proofRequest(name, version) {
            applyPayloadRandomly(prPayload, nonRevoked)
        }
    }

    fun StartedNode<MockNode>.issueRandomSimilarCredentials(
        provers: List<StartedNode<MockNode>>,
        enableRevocation: Boolean,
        count: Int
    ): List<CredentialAndMetadata> {
        val schema = issueRandomSchema()
        val credDef = issueCredentialDefinition(schema.getSchemaIdObject(), enableRevocation)
        val revRegInfo = if (enableRevocation)
            issueRevocationRegistry(credDef.getCredentialDefinitionIdObject(), provers.size + 4)
        else
            null

        return provers.map { prover ->
            (0 until count).map {
                val idAndValues = issueRandomCredential(
                    prover,
                    schema.attributeNames,
                    credDef.getCredentialDefinitionIdObject(),
                    revRegInfo?.definition?.getRevocationRegistryIdObject()
                )

                CredentialAndMetadata(schema, credDef, revRegInfo, idAndValues, prover)
            }
        }.flatten()
    }

    data class CredentialAndMetadata(
        val schema: Schema,
        val credentialDefinition: CredentialDefinition,
        val revocationRegistryInfo: RevocationRegistryInfo?,
        val idAndValues: Pair<String, Map<String, String>>,
        val prover: StartedNode<MockNode>
    )

    fun StartedNode<MockNode>.issueRandomDifferentCredentials(
        provers: List<StartedNode<MockNode>>,
        enableRevocation: Boolean,
        count: Int
    ) = provers.map { prover ->
        (0 until count).map {
            val schema = issueRandomSchema()
            val credDef = issueCredentialDefinition(schema.getSchemaIdObject(), enableRevocation)
            val revRegInfo = if (enableRevocation)
                issueRevocationRegistry(credDef.getCredentialDefinitionIdObject(), provers.size + 4)
            else
                null

            val idAndValues = issueRandomCredential(
                prover,
                schema.attributeNames,
                credDef.getCredentialDefinitionIdObject(),
                revRegInfo?.definition?.getRevocationRegistryIdObject()
            )

            CredentialAndMetadata(schema, credDef, revRegInfo, idAndValues, prover)
        }
    }.flatten()

    fun StartedNode<MockNode>.revoke(id: String) {
        val revokeFuture = services.startFlow(
            RevokeCredentialFlow.Issuer(id)
        ).resultFuture

        revokeFuture.getOrThrow(Duration.ofSeconds(30))
    }

    data class ProofState(
        val issuer: StartedNode<MockNode>,
        val verifier: StartedNode<MockNode>,
        val credentialAndMetadata: CredentialAndMetadata,
        val proofRequest: ProofRequest
    )

    /**
     * N issuers, each issues M random credentials to each of K provers, then L verifiers try to verify these credentials randomly
     * revocation is optional, but if it takes place, all credentials should be revoked after all
     * credentials can be similar (issued using single cred def) or different (issued using multiple cred defs)
     */
    fun constructTypicalFlow(
        issuerCount: Int,
        proverCount: Int,
        verifierCount: Int,
        credentialCount: Int,
        similarCredentials: Boolean,
        enableRevocation: Boolean
    ) {
        val issuers = createIssuerNodes(trustee, issuerCount)
        val provers = createNodes("Prover", proverCount)

        val issuerToIssuedCredentials = mutableMapOf<StartedNode<MockNode>, MutableList<CredentialAndMetadata>>()

        issuers.forEach { issuer ->
            val credentials = issuerToIssuedCredentials.getOrPut(issuer) { mutableListOf() }

            if (similarCredentials)
                credentials.addAll(issuer.issueRandomSimilarCredentials(provers, enableRevocation, credentialCount))
            else
                credentials.addAll(issuer.issueRandomDifferentCredentials(provers, enableRevocation, credentialCount))
        }

        val verifiers = createNodes("Verifier", verifierCount)

        val unableToProve = mutableListOf<ProofState>()

        verifiers.forEach { verifier ->
            issuerToIssuedCredentials.entries.forEach { (issuer, credentialAndMetadataList) ->
                credentialAndMetadataList.forEach { credentialAndMetadata ->
                    val pr = createRandomProofRequest(
                        credentialAndMetadata.idAndValues.second,
                        issuer.getPartyDid(),
                        credentialAndMetadata.schema.getSchemaIdObject(),
                        credentialAndMetadata.credentialDefinition.getCredentialDefinitionIdObject(),
                        Interval.now() // TODO: somehow fuzzy this
                    )

                    val (proofId, proofValid) = verifier.verify(credentialAndMetadata.prover, pr)

                    if (!proofValid)
                        unableToProve.add(ProofState(issuer, verifier, credentialAndMetadata, pr))
                }
            }
        }

        if (unableToProve.isNotEmpty()) {
            println("------- Failed proofs -------")
            println(SerializationUtils.anyToJSON(unableToProve))
            println("-----------------------------")
        }

        if (enableRevocation) {
            issuerToIssuedCredentials.entries.forEach { (issuer, credentialAndMetadataList) ->
                credentialAndMetadataList.forEach { credentialAndMetadata ->
                    issuer.revoke(credentialAndMetadata.idAndValues.first)
                }
            }

            val ableToProve = mutableListOf<ProofState>()

            verifiers.forEach { verifier ->
                issuerToIssuedCredentials.entries.forEach { (issuer, credentialAndMetadataList) ->
                    credentialAndMetadataList.forEach { credentialAndMetadata ->
                        val pr = createRandomProofRequest(
                            credentialAndMetadata.idAndValues.second,
                            issuer.getPartyDid(),
                            credentialAndMetadata.schema.getSchemaIdObject(),
                            credentialAndMetadata.credentialDefinition.getCredentialDefinitionIdObject(),
                            Interval.now() // TODO: somehow fuzzy this
                        )

                        val (proofId, proofValid) = verifier.verify(credentialAndMetadata.prover, pr)

                        if (proofValid)
                            ableToProve.add(ProofState(issuer, verifier, credentialAndMetadata, pr))
                    }
                }
            }

            if (ableToProve.isNotEmpty()) {
                println("------- Failed proofs after revocation -------")
                println(SerializationUtils.anyToJSON(ableToProve))
                println("----------------------------------------------")
            }
        }
    }

    @Test
    fun `1 issuer 1 prover 1 verifier 1 credential without revocation`() {
        constructTypicalFlow(1, 1, 1, 1, true, false)
    }

    @Test
    fun `1 issuer 1 prover 1 verifier 1 credential with revocation`() {
        constructTypicalFlow(1, 1, 1, 1, true, true)
    }

    @Test
    fun `1 issuer 1 prover 1 verifier 2 similar credentials without revocation`() {
        constructTypicalFlow(1, 1, 1, 2, true, false)
    }

    @Test
    fun `1 issuer 1 prover 1 verifier 2 different credentials without revocation`() {
        constructTypicalFlow(1, 1, 1, 2, false, false)
    }

    @Test
    fun `1 issuer 1 prover 1 verifier 2 similar credentials with revocation`() {
        constructTypicalFlow(1, 1, 1, 2, true, true)
    }

    @Test
    fun `1 issuer 1 prover 1 verifier 2 different credentials with revocation`() {
        constructTypicalFlow(1, 1, 1, 2, false, true)
    }
}
