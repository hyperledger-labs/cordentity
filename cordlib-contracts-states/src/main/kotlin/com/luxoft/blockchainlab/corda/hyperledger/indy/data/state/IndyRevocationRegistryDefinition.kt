package com.luxoft.blockchainlab.corda.hyperledger.indy.data.state

import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.IndyRevocationRegistryContract
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema.RevocationRegistryDefinitionSchemaV1
import com.luxoft.blockchainlab.hyperledger.indy.models.CredentialDefinitionId
import com.luxoft.blockchainlab.hyperledger.indy.models.RevocationRegistryDefinitionId
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
 * A corda record of revocation registry
 *
 * @param id [RevocationRegistryDefinitionId]
 * @param credentialDefinitionId [CredentialDefinitionId]
 * @param participants [List] of [AbstractParty]
 * @param credentialsLimit [Int]
 * @param currentCredNumber [Int]
 */
@BelongsToContract(IndyRevocationRegistryContract::class)
data class IndyRevocationRegistryDefinition(
    val id: RevocationRegistryDefinitionId,
    val credentialDefinitionId: CredentialDefinitionId,
    override val participants: List<AbstractParty>,
    val credentialsLimit: Int,
    val currentCredNumber: Int = 0
) : LinearState, QueryableState {

    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when (schema) {
            is RevocationRegistryDefinitionSchemaV1 -> RevocationRegistryDefinitionSchemaV1.PersistentRevocationRegistryDefinition(this)
            else -> throw IllegalArgumentException("Unrecognised schema: $schema")
        }
    }

    override fun supportedSchemas() = listOf(RevocationRegistryDefinitionSchemaV1)

    override val linearId = UniqueIdentifier()

    /**
     * Returns true if this credential definition is able to hold at least 1 more credential
     */
    fun canProduceCredentials() = currentCredNumber < credentialsLimit

    /**
     * Returns new state
     */
    fun requestNewCredential() = copy(currentCredNumber = this.currentCredNumber + 1)
}

/**
 * Gets revocation registry state from vault
 */
fun FlowLogic<Any>.getRevocationRegistryDefinitionById(id: RevocationRegistryDefinitionId): StateAndRef<IndyRevocationRegistryDefinition>? {
    val generalCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
    val id = QueryCriteria.VaultCustomQueryCriteria(
        RevocationRegistryDefinitionSchemaV1.PersistentRevocationRegistryDefinition::id.equal(id.toString())
    )

    val criteria = generalCriteria.and(id)
    val result = serviceHub.vaultService.queryBy<IndyRevocationRegistryDefinition>(criteria)

    return result.states.firstOrNull()
}

fun FlowLogic<Any>.getRevocationRegistryDefinitionByCredentialDefinitionId(id: CredentialDefinitionId): StateAndRef<IndyRevocationRegistryDefinition>? {
    val generalCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
    val id = QueryCriteria.VaultCustomQueryCriteria(
        RevocationRegistryDefinitionSchemaV1.PersistentRevocationRegistryDefinition::credentialDefinitionId.equal(id.toString())
    )

    val criteria = generalCriteria.and(id)
    val result = serviceHub.vaultService.queryBy<IndyRevocationRegistryDefinition>(criteria)

    return result.states.firstOrNull()
}
