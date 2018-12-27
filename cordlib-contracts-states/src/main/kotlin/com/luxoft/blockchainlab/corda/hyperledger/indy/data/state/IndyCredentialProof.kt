package com.luxoft.blockchainlab.corda.hyperledger.indy.data.state

import com.luxoft.blockchainlab.corda.hyperledger.indy.data.schema.CredentialProofSchemaV1
import com.luxoft.blockchainlab.hyperledger.indy.DataUsedInProofJson
import com.luxoft.blockchainlab.hyperledger.indy.ProofInfo
import com.luxoft.blockchainlab.hyperledger.indy.ProofRequest
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState

/**
 * A Corda record of an Indy proof [proof] issued on request [proofReq]
 *
 * @param id                entity persistent id
 * @param proofReq          indy proof request
 * @param proof             indy proof
 * @param usedData          data required by verifier to verify proof
 * @param participants      list of corda participants
 * @param linearId          corda id
 */
open class IndyCredentialProof(
    val id: String,
    val proofReq: ProofRequest,
    val proof: ProofInfo,
    val usedData: DataUsedInProofJson,
    override val participants: List<AbstractParty>,
    override val linearId: UniqueIdentifier = UniqueIdentifier()
) : QueryableState, LinearState {

    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when (schema) {
            is CredentialProofSchemaV1 -> CredentialProofSchemaV1.PersistentProof(this)
            else -> throw IllegalArgumentException("Unrecognised schema $schema")
        }
    }

    override fun supportedSchemas(): Iterable<MappedSchema> = listOf(CredentialProofSchemaV1)

}