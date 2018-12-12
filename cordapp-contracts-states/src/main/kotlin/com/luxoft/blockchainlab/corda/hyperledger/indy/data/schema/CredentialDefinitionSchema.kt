package com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema

import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyCredentialDefinition
import com.luxoft.blockchainlab.hyperledger.indy.IndyUser
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import javax.persistence.Column
import javax.persistence.Entity


object CredentialDefinitionSchema

object CredentialDefinitionSchemaV1 : MappedSchema(
    version = 1,
    schemaFamily = CredentialDefinitionSchema.javaClass,
    mappedTypes = listOf(PersistentCredentialDefinition::class.java)
) {
    @Entity
    data class PersistentCredentialDefinition(
        @Column(name = "id") val credentialDefId: String = "",
        val schemaId: String = "",
        val revRegId: String = "",
        val currentCredNumber: Int = 0

    ) : PersistentState() {
        constructor(credentialDef: IndyCredentialDefinition) : this(
            credentialDef.credentialDefinitionId.toString(),
            credentialDef.schemaId.toString(),
            credentialDef.credentialDefinitionId.getRevocationRegistryDefinitionId(IndyUser.REVOCATION_TAG).toString(),
            credentialDef.currentCredNumber
        )
    }
}