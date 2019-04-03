package com.luxoft.blockchainlab.corda.hyperledger.indy.data.state

import com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema.CredentialDefinitionSchemaV1
import com.luxoft.blockchainlab.hyperledger.indy.models.CredentialDefinitionId
import com.luxoft.blockchainlab.hyperledger.indy.models.SchemaId
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState


/**
 * A Corda record of an indy credential definition.
 *
 * @param schemaId                          id of schema associated with this credential definition
 * @param id                                id of this credential definition
 * @param credentialsLimit                  maximum number of credential which can be issued using this credential definition
 * @param participants                      corda participants
 * @param currentCredNumber                 current number of credentials issued using this credential definition
 */
data class IndyCredentialDefinition(
    val id: CredentialDefinitionId,
    val schemaId: SchemaId,
    override val participants: List<AbstractParty>
) : LinearState, QueryableState {

    override val linearId: UniqueIdentifier = UniqueIdentifier()

    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when (schema) {
            is CredentialDefinitionSchemaV1 -> CredentialDefinitionSchemaV1.PersistentCredentialDefinition(this)
            else -> throw IllegalArgumentException("Unrecognised schema: $schema")
        }
    }

    override fun supportedSchemas() = listOf(CredentialDefinitionSchemaV1)
}
