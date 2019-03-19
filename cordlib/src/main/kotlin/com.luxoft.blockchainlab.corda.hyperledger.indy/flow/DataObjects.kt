package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import com.luxoft.blockchainlab.hyperledger.indy.models.CredentialDefinitionId
import com.luxoft.blockchainlab.hyperledger.indy.models.SchemaId
import net.corda.core.serialization.CordaSerializable

/**
 * A proof of a string Attribute with an optional check against [value]
 * The Attribute is contained in a field [field] in a credential definition by [credentialDefinitionId]
 *
 * @param schemaId                      id of schema of this credential TODO: get rid of this when indy rearranges their id-stuff
 * @param value                         an optional value the Attribute is checked against
 * @param field                         the name of the field that provides this Attribute
 * @param credentialDefinitionId        id of the Credential Definition that produced by issuer
 * */
@CordaSerializable
data class ProofAttribute(
        val schemaId: SchemaId,
        val credentialDefinitionId: CredentialDefinitionId,
        val field: String,
        val value: String = ""
)

/**
 * A proof of a logical Predicate on an integer Attribute in the form `Attribute >= [value]`
 * The Attribute is contained in a field [field] in a credential definition by [credentialDefinitionId]
 *
 * @param schemaId                      id of schema of this credential
 * @param value                         value in the predicate to compare the Attribute against
 * @param field                         the name of the field that provides the Attribute
 * @param credentialDefinitionId        id of the Credential Definition that produced by issuer
 * */
@CordaSerializable
data class ProofPredicate(
        val schemaId: SchemaId,
        val credentialDefinitionId: CredentialDefinitionId,
        val field: String,
        val value: Int
)