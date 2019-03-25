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
        fun now() = Interval(Timestamp.now(), Timestamp.now())
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
