package com.luxoft.blockchainlab.corda.hyperledger.indy.data.state

import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.IndySchemaContract
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema.IndySchemaSchemaV1
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
 * A Corda record representing indy schema
 *
 * @param id                id of this schema
 * @param participants      corda participants
 */
@BelongsToContract(IndySchemaContract::class)
class IndySchema(
    val id: SchemaId,
    override val participants: List<AbstractParty>
) : LinearState, QueryableState {

    override val linearId: UniqueIdentifier = UniqueIdentifier()

    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when (schema) {
            is IndySchemaSchemaV1 -> IndySchemaSchemaV1.PersistentSchema(this)
            else -> throw IllegalArgumentException("Unrecognised schema: $schema")
        }
    }

    override fun supportedSchemas() = listOf(IndySchemaSchemaV1)
}

/**
 * Gets schema state from vault
 */
fun FlowLogic<Any>.getSchemaById(schemaId: SchemaId): StateAndRef<IndySchema>? {
    val generalCriteria =
        QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
    val id = QueryCriteria.VaultCustomQueryCriteria(
        IndySchemaSchemaV1.PersistentSchema::id.equal(
            schemaId.toString()
        )
    )

    val criteria = generalCriteria.and(id)
    val result = serviceHub.vaultService.queryBy<IndySchema>(criteria)

    return result.states.firstOrNull()
}
