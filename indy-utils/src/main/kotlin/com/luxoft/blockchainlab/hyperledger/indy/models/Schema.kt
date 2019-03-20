package com.luxoft.blockchainlab.hyperledger.indy.models

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Represents indy schema
 *
 * @param id                identifier of schema
 * @param attributeNames    an array of attribute name strings
 * @param seqNo             ??? Int
 * @param name              Schema's name string
 * @param version           Schema's version string
 * @param ver              Version of the Schema json
 */
data class Schema(
    val ver: String,
    val id: String,
    val name: String,
    val version: String,
    @JsonProperty("attrNames") val attributeNames: List<String>,
    @JsonProperty("seqNo") val seqNo: Int?,
    @JsonIgnore override val schemaIdRaw: String = id
) : ContainsSchemaId {
    @JsonIgnore
    fun isValid() = seqNo != null

    @JsonIgnore
    fun getFilter() = """{name:$name,version:$version,owner:${getSchemaIdObject().did}}"""
}

/**
 * Schema id is the local identifier of some schema. Don't confuse with schema sequence number which represents schema's
 * index on ledger. This class (de)serializes schema id.
 * Indy uses raw string value of schema id, but it's parts are actually very useful. By possessing some schema id one
 * could also know its:
 *
 * @param did: [String] - the DID of the schema issuer
 * @param name: [String] - the name of the schema
 * @param version: [String] - the version of the schema
 */
class SchemaId(val did: String, val name: String, val version: String) {
    override fun toString() = "$did:2:$name:$version"

    companion object : FromString<SchemaId> {
        override fun fromString(str: String): SchemaId {
            val (did, _, name, version) = str.split(":")

            return SchemaId(did, name, version)
        }
    }
}

/**
 * Represents a class which somehow provides schema id
 */
interface ContainsSchemaId {
    val schemaIdRaw: String
    fun getSchemaIdObject() = SchemaId.fromString(schemaIdRaw)
}