package com.luxoft.blockchainlab.hyperledger.indy.utils

import com.luxoft.blockchainlab.hyperledger.indy.models.*
import java.util.*
import kotlin.math.absoluteValue


/**
 * Proof request DSL entry point
 * Example:
 * [proofRequest] ("example", "1.0") {
 *  [ProofRequest.reveal]
 *  [ProofRequest.proveGreaterThan]
 *  [ProofRequest.proveNonRevocation]
 *  [ProofRequest.extraQuery] - additional WQL query applied to Wallet's credential search
 * }
 *
 * @param name [String] - proof request name
 * @param version [String] - proof request version
 * @param nonce [String] - proof request nonce (default = random)
 * @param init - init lambda with new [ProofRequest] obj as a receiver
 */
fun proofRequest(
    name: String,
    version: String,
    nonce: String = Random().nextLong().toString(),
    init: ProofRequest.() -> Unit
): ProofRequest {
    val pr = ProofRequest(name, version, nonce)
    pr.init()

    return pr
}

/**
 * DSL entry to reveal some attribute using some filters
 * Example:
 * [reveal] ("someAttr") {
 *  [FilterProperty] [FilterProperty.shouldBe] [String]
 * }
 *
 * @param attrName [String] - attribute name to reveal
 * @param filterFiller - lambda with new [Filter] obj as a receiver (default = null)
 */
fun ProofRequest.reveal(attrName: String, filterFiller: (Filter.() -> Unit)? = null) {
    val attributeReference = if (filterFiller == null) {
        CredentialAttributeReference(attrName)
    } else {
        var filter: Filter? = Filter(attrName)
        filter!!.filterFiller()
        if (filter.isEmpty())
            filter = null

        CredentialAttributeReference(attrName, filter)
    }

    requestedAttributes[attrName] = attributeReference
}

/**
 * DSL entry to prove GE condition on some attribute
 * Example:
 * [proveGreaterThan] ("someAttr", 10) {
 *  [FilterProperty] [FilterProperty.shouldBe] [String]
 * }
 *
 * @param attrName [String] - attribute name to reveal
 * @param greaterThan [Int] - value which should be less than [attrName]'s value
 * @param filterFiller - lambda with new [Filter] obj as a receiver (default = null)
 */
fun ProofRequest.proveGreaterThan(attrName: String, greaterThan: Int, filterFiller: (Filter.() -> Unit)? = null) {
    val predicateReference = if (filterFiller == null) {
        CredentialPredicateReference(attrName, greaterThan)
    } else {
        var filter: Filter? = Filter(attrName)
        filter!!.filterFiller()
        if (filter.isEmpty())
            filter = null

        CredentialPredicateReference(attrName, greaterThan, restrictions = filter)
    }

    requestedPredicates[attrName] = predicateReference
}

/**
 * DSL entry to prove non-revocation of the whole proof request
 * Example:
 * [proveNonRevocation] ([Interval.now])
 *
 * @param interval [Interval] - non revocation interval
 */
fun ProofRequest.proveNonRevocation(interval: Interval) {
    nonRevoked = interval
}

class ExtraQueryBuilder(val attributes: MutableMap<String, WqlQuery> = mutableMapOf())

/**
 * Specifies all possible filter parameters related to some credential
 */
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
        fun create(attributeValues: Map<String, String>, schemaId: SchemaId, credentialDefId: CredentialDefinitionId) =
            ProofRequestPayload(
                attributeValues,
                schemaId,
                credentialDefId,
                schemaId.did,
                schemaId.name,
                schemaId.version,
                credentialDefId.did
            )

        fun fromCredential(credential: Credential): ProofRequestPayload {
            val schemaId = credential.getSchemaIdObject()
            val attributeValues = credential.values.entries.associate { it.key to it.value.raw }
            val credentialDefinitionId = credential.getCredentialDefinitionIdObject()

            return ProofRequestPayload(
                attributeValues,
                schemaId,
                credentialDefinitionId,
                schemaId.did,
                schemaId.name,
                schemaId.version,
                credentialDefinitionId.did
            )
        }
    }
}

/**
 * Helper function to generate random proof request out of [ProofRequestPayload]
 */
fun ProofRequest.applyPayloadRandomly(payload: ProofRequestPayload) {
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
    }
}

/**
 * Creates random proof request for each proof request payload you submit
 *
 * @param payloads vararg [Array] of [ProofRequestPayload] - for each credential you want to create random proof request
 *  specify [ProofRequestPayload]
 * @param nonRevoked [Interval] or null - non-revocation interval
 */
fun createRandomProofRequest(nonRevoked: Interval?, vararg payloads: ProofRequestPayload): ProofRequest {
    val rng = Random()
    val name = "proof-request-${rng.nextInt().absoluteValue}"
    val version = "${rng.nextInt().absoluteValue}.${rng.nextInt().absoluteValue}"

    return proofRequest(name, version) {
        payloads.forEach {
            applyPayloadRandomly(it)
        }

        if (nonRevoked != null) proveNonRevocation(nonRevoked)
    }
}