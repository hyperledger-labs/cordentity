package com.luxoft.blockchainlab.hyperledger.indy.utils

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
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
 *  [FilterProperty] shouldBe [String]
 *  "some other attr" shouldBe "value"
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

        requestedAttributes.keys.intersect(requestedPredicates.keys).forEach {
            val keepAttribute = rng.nextBoolean()
            if (keepAttribute)
                requestedPredicates.remove(it)
            else
                requestedAttributes.remove(it)
        }

        if (nonRevoked != null) proveNonRevocation(nonRevoked)
    }
}

/**
 *     filter:
 *     {
 *         "schema_id": string, (Optional)
 *         "schema_issuer_did": string, (Optional)
 *         "schema_name": string, (Optional)
 *         "schema_version": string, (Optional)
 *         "issuer_did": string, (Optional)
 *         "cred_def_id": string, (Optional)
 *     }
 */
data class Filter(
    //TODO: We are loosing meta info after serialization, need to rework. Can`t serialize because of INDY.
    @JsonIgnore val attrName: String = "",
    @JsonProperty("schema_id") var schemaIdRaw: String? = null,
    var schemaIssuerDid: String? = null,
    var schemaName: String? = null,
    var schemaVersion: String? = null,
    var issuerDid: String? = null,
    var credDefId: String? = null,
    @JsonIgnore val attributes: MutableMap<String, String> = mutableMapOf()
) {
    @JsonAnyGetter
    fun getUnknownAttributes() = attributes

    @JsonAnySetter
    fun setUnknownAttribute(key: String, value: String) = attributes.put(key, value)

    @JsonIgnore
    fun isEmpty() = schemaIdRaw == null && schemaIssuerDid == null && schemaName == null && schemaVersion == null
            && issuerDid == null && credDefId == null && attributes.isEmpty()

    infix fun FilterProperty.shouldBe(value: String) {
        when (this) {
            FilterProperty.Value -> attributes["attr::${attrName}::value"] = value
            FilterProperty.SchemaId -> schemaIdRaw = value
            FilterProperty.SchemaIssuerDid -> schemaIssuerDid = value
            FilterProperty.SchemaName -> schemaName = value
            FilterProperty.SchemaVersion -> schemaVersion = value
            FilterProperty.IssuerDid -> issuerDid = value
            FilterProperty.CredentialDefinitionId -> credDefId = value
        }
    }
}

enum class FilterProperty {
    SchemaId, SchemaIssuerDid, SchemaName, SchemaVersion, IssuerDid, CredentialDefinitionId, Value
}
