package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.*
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2b.AssignPermissionsFlowB2B
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2b.IssueCredentialFlowB2B
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2b.VerifyCredentialFlowB2B
import com.luxoft.blockchainlab.corda.hyperledger.indy.service.IndyService
import com.luxoft.blockchainlab.hyperledger.indy.models.*
import com.luxoft.blockchainlab.hyperledger.indy.utils.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.StartedMockNode
import java.time.Duration
import java.util.*
import kotlin.math.absoluteValue


open class CordentityTestBase : CordaTestBase() {

    /**
     * Shares permissions from authority to issuer
     * This action is needed to implement authority chains
     *
     * Call from authority
     *
     * @param to                a node that needs permissions
     */
    protected fun StartedMockNode.setPermissions(to: StartedMockNode) {
        val permissionsFuture = to.runFlow(
            AssignPermissionsFlowB2B.Issuer(authority = info.singleIdentity().name, role = "TRUSTEE")
        )

        permissionsFuture.getOrThrow(Duration.ofSeconds(30))
    }

    fun createIssuerNodes(trustee: StartedMockNode, count: Int) = (0 until count)
        .map { createPartyNode(CordaX500Name("Issuer-$it", "London", "GB")) }
        .apply { forEach { trustee.setPermissions(it) } }

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

        payload.attributeValues.entries.forEach attributeLoop@{ (key, value) ->
            val proveGE = rng.nextBoolean()
            if (proveGE) {
                val valueInt = value.toInt()
                val greaterThan = rng.nextInt().absoluteValue % valueInt

                proveGreaterThan(key, greaterThan) {
                    FilterProperty.values().forEach filterLoop@{
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
                    FilterProperty.values().forEach filterLoop@{
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

    data class CredentialAndMetadata(
        val schema: Schema,
        val credentialDefinition: CredentialDefinition,
        val revocationRegistryInfo: RevocationRegistryInfo?,
        val idAndValues: Pair<String, Map<String, String>>,
        val prover: Pair<CordaX500Name, String>
    ) {
        constructor(
            schema: Schema,
            credentialDefinition: CredentialDefinition,
            revocationRegistryInfo: RevocationRegistryInfo?,
            idAndValues: Pair<String, Map<String, String>>,
            prover: StartedMockNode
        ) : this(
            schema,
            credentialDefinition,
            revocationRegistryInfo,
            idAndValues,
            Pair(prover.info.name(), prover.getPartyDid())
        )
    }

    data class ProofState(
        val issuerNameAndDid: Pair<CordaX500Name, String>,
        val verifierNameAndDid: Pair<CordaX500Name, String>,
        val credentialAndMetadata: CredentialAndMetadata,
        val proofRequest: ProofRequest
    ) {
        constructor(
            issuer: StartedMockNode,
            verifier: StartedMockNode,
            credentialAndMetadata: CredentialAndMetadata,
            proofRequest: ProofRequest
        ) : this(
            Pair(issuer.info.name(), issuer.getPartyDid()),
            Pair(verifier.info.name(), verifier.getPartyDid()),
            credentialAndMetadata,
            proofRequest
        )
    }

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
        enableRevocation: Boolean,
        maxCredentialsPerRevRegistry: Int
    ): Boolean {
        val issuers = createIssuerNodes(trustee, issuerCount)
        val provers = createNodes("Prover", proverCount)

        val issuerToIssuedCredentials = mutableMapOf<StartedMockNode, MutableList<CredentialAndMetadata>>()

        issuers.forEach { issuer ->
            val credentials = issuerToIssuedCredentials.getOrPut(issuer) { mutableListOf() }

            if (similarCredentials)
                credentials.addAll(
                    issuer.issueRandomSimilarCredentials(
                        provers,
                        enableRevocation,
                        credentialCount,
                        maxCredentialsPerRevRegistry
                    )
                )
            else
                credentials.addAll(
                    issuer.issueRandomDifferentCredentials(
                        provers,
                        enableRevocation,
                        credentialCount,
                        maxCredentialsPerRevRegistry
                    )
                )
        }

        val verifiers = createNodes("Verifier", verifierCount)

        val unableToProve = mutableListOf<ProofState>()

        val nonRevoked = if (enableRevocation) Interval.allTime() else null

        verifiers.forEach { verifier ->
            issuerToIssuedCredentials.entries.forEach { (issuer, credentialAndMetadataList) ->
                credentialAndMetadataList.forEach { credentialAndMetadata ->
                    val pr = createRandomProofRequest(
                        credentialAndMetadata.idAndValues.second,
                        issuer.getPartyDid(),
                        credentialAndMetadata.schema.getSchemaIdObject(),
                        credentialAndMetadata.credentialDefinition.getCredentialDefinitionIdObject(),
                        nonRevoked
                    )

                    val prover = provers.first { it.info.name() == credentialAndMetadata.prover.first }
                    val (proofId, proofValid) = verifier.verify(prover, pr)

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

        val ableToProve = mutableListOf<ProofState>()

        if (enableRevocation) {
            issuerToIssuedCredentials.entries.forEach { (issuer, credentialAndMetadataList) ->
                credentialAndMetadataList.forEach { credentialAndMetadata ->
                    issuer.revoke(credentialAndMetadata.idAndValues.first)
                }
            }

            verifiers.forEach { verifier ->
                issuerToIssuedCredentials.entries.forEach { (issuer, credentialAndMetadataList) ->
                    credentialAndMetadataList.forEach { credentialAndMetadata ->
                        val pr = createRandomProofRequest(
                            credentialAndMetadata.idAndValues.second,
                            issuer.getPartyDid(),
                            credentialAndMetadata.schema.getSchemaIdObject(),
                            credentialAndMetadata.credentialDefinition.getCredentialDefinitionIdObject(),
                            Interval.allTime()
                        )

                        val prover = provers.first { it.info.name() == credentialAndMetadata.prover.first }
                        val (proofId, proofValid) = verifier.verify(prover, pr)

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

        return unableToProve.isEmpty() && ableToProve.isEmpty()
    }

    fun StartedMockNode.issueRandomSchema(): Schema {
        val rng = Random()
        val name = "schema-${rng.nextInt().absoluteValue}"
        val version = "${rng.nextInt().absoluteValue}.${rng.nextInt().absoluteValue}"
        val attributeCount = rng.nextInt().absoluteValue % 3 + 2
        val attributes = (0 until attributeCount).map { "attribute-${rng.nextInt().absoluteValue}" }

        val schemaFuture = runFlow(
            CreateSchemaFlow.Authority(name, version, attributes)
        )

        return schemaFuture.getOrThrow(flowTimeout)
    }

    fun StartedMockNode.issueCredentialDefinition(schemaId: SchemaId, enableRevocation: Boolean): CredentialDefinition {
        val credDefFuture = runFlow(
            CreateCredentialDefinitionFlow.Authority(schemaId, enableRevocation)
        )

        return credDefFuture.getOrThrow(flowTimeout)
    }

    fun StartedMockNode.issueRevocationRegistry(
        credentialDefId: CredentialDefinitionId,
        credentialsLimit: Int
    ): RevocationRegistryInfo {
        val revocationRegistryFuture = runFlow(
            CreateRevocationRegistryFlow.Authority(credentialDefId, credentialsLimit)
        )

        return revocationRegistryFuture.getOrThrow(flowTimeout)
    }

    fun StartedMockNode.issueRandomCredential(
        prover: StartedMockNode,
        schemaAttributes: List<String>,
        credentialDefId: CredentialDefinitionId,
        revocationRegistryId: RevocationRegistryDefinitionId?
    ): Pair<String, Map<String, String>> {
        val rng = Random()
        val attributesToValues = mutableMapOf<String, String>()

        val credentialFuture = runFlow(
            IssueCredentialFlowB2B.Issuer(prover.info.name(), credentialDefId, revocationRegistryId) {
                schemaAttributes.forEach {
                    val value = rng.nextInt().absoluteValue.toString()
                    attributes[it] = CredentialValue(value)

                    // for test purposes
                    attributesToValues[it] = value
                }
            }
        )

        val credId = credentialFuture.getOrThrow(flowTimeout)

        return Pair(credId, attributesToValues)
    }

    fun StartedMockNode.verify(
        prover: StartedMockNode,
        proofRequest: ProofRequest
    ): Pair<String?, Boolean> {
        val proofCheckResultFuture = runFlow(
            VerifyCredentialFlowB2B.Verifier(prover.info.name(), proofRequest)
        )

        return proofCheckResultFuture.getOrThrow(flowTimeout)
    }

    fun StartedMockNode.issueRandomSimilarCredentials(
        provers: List<StartedMockNode>,
        enableRevocation: Boolean,
        count: Int,
        maxCredentialsPerRevRegistry: Int
    ): List<CredentialAndMetadata> {
        val schema = issueRandomSchema()
        val credDef = issueCredentialDefinition(schema.getSchemaIdObject(), enableRevocation)
        val revRegInfo = if (enableRevocation)
            issueRevocationRegistry(credDef.getCredentialDefinitionIdObject(), maxCredentialsPerRevRegistry)
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

    fun StartedMockNode.issueRandomDifferentCredentials(
        provers: List<StartedMockNode>,
        enableRevocation: Boolean,
        count: Int,
        maxCredentialsPerRevRegistry: Int
    ) = provers.map { prover ->
        (0 until count).map {
            val schema = issueRandomSchema()
            val credDef = issueCredentialDefinition(schema.getSchemaIdObject(), enableRevocation)
            val revRegInfo = if (enableRevocation)
                issueRevocationRegistry(credDef.getCredentialDefinitionIdObject(), maxCredentialsPerRevRegistry)
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

    fun StartedMockNode.revoke(id: String) {
        val revokeFuture = runFlow(
            RevokeCredentialFlow.Issuer(id)
        )

        revokeFuture.getOrThrow(flowTimeout)
    }
}

fun StartedMockNode.getPartyDid() =
    this.services.cordaService(IndyService::class.java).indyUser.walletUser.getIdentityDetails().did
