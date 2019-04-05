package com.luxoft.blockchainlab.corda.hyperledger.indy.data.state

import com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema.RevocationRegistryDefinitionSchemaV1
import com.luxoft.blockchainlab.hyperledger.indy.models.CredentialDefinitionId
import com.luxoft.blockchainlab.hyperledger.indy.models.RevocationRegistryDefinitionId
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
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
