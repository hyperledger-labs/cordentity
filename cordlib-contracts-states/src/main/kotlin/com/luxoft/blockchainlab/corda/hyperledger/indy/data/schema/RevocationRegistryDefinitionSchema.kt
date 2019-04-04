package com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema

import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyRevocationRegistryDefinition
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import javax.persistence.Entity


object RevocationRegistryDefinitionSchema

object RevocationRegistryDefinitionSchemaV1 : MappedSchema(
    version = 1,
    schemaFamily = RevocationRegistryDefinitionSchema.javaClass,
    mappedTypes = listOf(PersistentRevocationRegistryDefinition::class.java)
) {
    @Entity
    data class PersistentRevocationRegistryDefinition(
        val id: String = "",
        val credentialDefinitionId: String,
        val currentCredNumber: Int
    ) : PersistentState() {
        constructor(revocationRegistryDefinition: IndyRevocationRegistryDefinition) : this(
            revocationRegistryDefinition.id.toString(),
            revocationRegistryDefinition.credentialDefinitionId.toString(),
            revocationRegistryDefinition.currentCredNumber
        )

        constructor() : this("", "", 0)
    }
}
