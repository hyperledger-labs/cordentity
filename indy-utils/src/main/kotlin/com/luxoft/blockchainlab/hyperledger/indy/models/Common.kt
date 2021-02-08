package com.luxoft.blockchainlab.hyperledger.indy.models

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import org.hyperledger.indy.sdk.blob_storage.BlobStorageReader
import org.hyperledger.indy.sdk.blob_storage.BlobStorageWriter


/**
 * Represents a JSON object with data we don't care about
 */
typealias RawJsonMap = Map<String, Any?>

/**
 * Timestamps in indy-world are represented as seconds from current unix epoch and are passed as ints.
 */
object Timestamp {
    fun now() = (System.currentTimeMillis() / 1000)
}

/**
 * Represents time interval used for non-revocation proof request creation
 */
data class Interval(val from: Long?, val to: Long) {
    companion object {
        fun allTime() = Interval(null, Timestamp.now())
    }
}

/**
 * Represents pairwise connection
 */
data class ParsedPairwise(@JsonProperty("my_did") val myDid: String, val metadata: String)

/**
 * Represents some details of a particular identity
 *
 * @param did: [String]             did of this identity
 * @param verkey: [String]          verification key of this identity
 * @param alias: [String]           <optional> additional alias of this identity
 * @param role: [String]            <optional> role of this identity (e.g. TRUSTEE)
 */
data class IdentityDetails(
    val did: String,
    val verkey: String,
    @JsonIgnore val alias: String?,
    @JsonIgnore val role: String?
)

class IdentityDetailsList : ArrayList<IdentityDetails>()

/**
 * Interface for class that can be constructed from some string data
 */
interface FromString<T : Any> {
    fun fromString(str: String): T
}

/**
 * Allows to configure tails file creation and retrieving
 */
data class TailsConfig(val baseDir: String, val uriPattern: String = "")

/**
 * Abstracts blob storage reader and writer which are used for tails file management
 */
data class BlobStorageHandler(val reader: BlobStorageReader, val writer: BlobStorageWriter)

/**
 * {
 *    "did": string, (optional;
 *            if not provided and cid param is false then the first 16 bit of the verkey will be used as a new DID;
 *            if not provided and cid is true then the full verkey will be used as a new DID;
 *            if provided, then keys will be replaced - key rotation use case)
 *    "seed": string, (optional) Seed that allows deterministic did creation (if not set random one will be created).
 *                               Can be UTF-8, base64 or hex string.
 *    "crypto_type": string, (optional; if not set then ed25519 curve is used;
 *              currently only 'ed25519' value is supported for this field)
 *    "cid": bool, (optional; if not set then false is used;)
 * }
 */
data class DidConfig(
    val did: String? = null,
    val seed: String? = null,
    val cryptoType: String? = null,
    val cid: Boolean? = null
)

/**
 * Lambda to provide revocation state for some credential
 *
 * @param revRegId [RevocationRegistryDefinitionId]
 * @param credRevId [String]
 * @param interval [Interval]
 *
 * @return [RevocationState]
 */
typealias RevocationStateProvider = (revRegId: RevocationRegistryDefinitionId, credRevId: String, interval: Interval, revRegDuplicate: Boolean) -> RevocationState

/**
 * Lambda to provide schema for some id
 *
 * @param id [SchemaId]
 *
 * @return [Schema]
 */
typealias SchemaProvider = (id: SchemaId) -> Schema

/**
 * Lambda to provide credential definition for some id
 *
 * @param id [CredentialDefinitionId]
 *
 * @return [CredentialDefinition]
 */
typealias CredentialDefinitionProvider = (id: CredentialDefinitionId) -> CredentialDefinition

/**
 * Credential proposal - [Map] of [String] (attribute name) to [CredentialValue] (attribute value)
 */
data class CredentialProposal(val attributes: MutableMap<String, CredentialValue> = mutableMapOf())
