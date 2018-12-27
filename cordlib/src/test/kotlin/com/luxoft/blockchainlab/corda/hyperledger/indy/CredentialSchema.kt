package com.luxoft.blockchainlab.corda.hyperledger.indy

interface Schema {
    val schemaName: String
    val schemaVersion: String
    val schemaAttrs: List<String>

    fun formatProposal(vararg attrValues: String): String
}

abstract class TwoAttrSchema(
    override val schemaName: String,
    override val schemaVersion: String,
    val schemaAttr1: String,
    val schemaAttr2: String
) : Schema {

    override val schemaAttrs = listOf(schemaAttr1, schemaAttr2)

    override fun formatProposal(vararg attrValues: String): String =
        formatProposal(attrValues[0], attrValues[1], attrValues[2], attrValues[3])

    fun formatProposal(value1: String, encoded1: String, value2: String, encoded2: String): String =
        """ {"$schemaAttr1":{"raw":"$value1", "encoded":"$encoded1"}, "$schemaAttr2":{"raw":"$value2", "encoded":"$encoded2"} }"""
}

class SchemaPerson : TwoAttrSchema("schema_name", "1.0", "attr1", "attr2")

class SchemaEducation : TwoAttrSchema("schema_education", "1.0", "attrX", "attrY")
