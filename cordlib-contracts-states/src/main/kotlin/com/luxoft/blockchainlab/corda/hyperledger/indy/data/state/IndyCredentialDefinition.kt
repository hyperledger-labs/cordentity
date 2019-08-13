package com.luxoft.blockchainlab.corda.hyperledger.indy.data.state

import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.IndyCredentialDefinitionContract
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema.CredentialDefinitionSchemaV1
import com.luxoft.blockchainlab.hyperledger.indy.models.CredentialDefinitionId
import com.luxoft.blockchainlab.hyperledger.indy.models.SchemaId
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.AbstractParty
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.Builder.equal
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState


/**
 * A Corda record of an indy credential definition.
 *
 * @param schemaId                          id of schema associated with this credential definition
 * @param id                                id of this credential definition
 * @param participants                      corda participants
 */
@BelongsToContract(IndyCredentialDefinitionContract::class)
data class IndyCredentialDefinition(
    val id: CredentialDefinitionId,
    val schemaId: SchemaId,
    val enableRevocation: Boolean,
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

/**
 * Gets credential definition state from vault
 */
private fun FlowLogic<Any>.getUnconsumedCredentialDefinitionByCriteria(
    criteria: QueryCriteria.VaultCustomQueryCriteria<CredentialDefinitionSchemaV1.PersistentCredentialDefinition>
): StateAndRef<IndyCredentialDefinition>? {
    val generalCriteria =
        QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)

    val criteria = generalCriteria.and(criteria)
    val result = serviceHub.vaultService.queryBy<IndyCredentialDefinition>(criteria)

    return result.states.firstOrNull()
}

fun FlowLogic<Any>.getCredentialDefinitionById(credentialDefinitionId: CredentialDefinitionId): StateAndRef<IndyCredentialDefinition>? {
    return getUnconsumedCredentialDefinitionByCriteria(
        QueryCriteria.VaultCustomQueryCriteria(
            CredentialDefinitionSchemaV1.PersistentCredentialDefinition::credentialDefId.equal(credentialDefinitionId.toString())
        )
    )
}

fun FlowLogic<Any>.getCredentialDefinitionBySchemaId(schemaId: SchemaId): StateAndRef<IndyCredentialDefinition>? {
    return getUnconsumedCredentialDefinitionByCriteria(
        QueryCriteria.VaultCustomQueryCriteria(
            CredentialDefinitionSchemaV1.PersistentCredentialDefinition::schemaId.equal(schemaId.toString())
        )
    )
}
