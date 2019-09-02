package com.luxoft.blockchainlab.corda.hyperledger.indy.data.state

import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.IndyCredentialContract
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema.CredentialSchemaV1
import com.luxoft.blockchainlab.hyperledger.indy.models.CredentialInfo
import com.luxoft.blockchainlab.hyperledger.indy.models.CredentialRequestInfo
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
 * A Corda record of an Indy Credential [credential] issued on request [credentialRequest]
 *
 * @param id                        credential persistent id
 * @param credentialRequestInfo     indy credential request
 * @param credentialInfo            indy credential
 * @param issuerDid                 did of an entity issued credential
 * @param participants              corda participants
 */
@BelongsToContract(IndyCredentialContract::class)
open class IndyCredential(
    val id: String,
    val credentialRequestInfo: CredentialRequestInfo,
    val credentialInfo: CredentialInfo,
    val issuerDid: String,
    override val participants: List<AbstractParty>
) : LinearState, QueryableState {

    override val linearId: UniqueIdentifier = UniqueIdentifier()

    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when (schema) {
            is CredentialSchemaV1 -> CredentialSchemaV1.PersistentCredential(this)
            else -> throw IllegalArgumentException("Unrecognised schema $schema")
        }
    }

    override fun supportedSchemas(): Iterable<MappedSchema> = listOf(CredentialSchemaV1)
}

/**
 * This method is used to get indy credential state from vault
 *
 * @param id                id of credential
 *
 * @return                  corda state of indy credential or null if none exists
 */
fun FlowLogic<Any>.getIndyCredentialState(id: String): StateAndRef<IndyCredential>? {
    val generalCriteria =
        QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
    val existingId = QueryCriteria.VaultCustomQueryCriteria(
        CredentialSchemaV1.PersistentCredential::id.equal(id)
    )

    val criteria = generalCriteria.and(existingId)
    val result = serviceHub.vaultService.queryBy<IndyCredential>(criteria)

    return result.states.firstOrNull()
}
