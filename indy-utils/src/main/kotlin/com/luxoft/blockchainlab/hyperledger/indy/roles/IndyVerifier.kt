package com.luxoft.blockchainlab.hyperledger.indy.roles

import com.luxoft.blockchainlab.hyperledger.indy.models.*
import org.hyperledger.indy.sdk.pool.Pool


/**
 * This interface represents verifier - an entity which purpose is to verify someone's credentials.
 * It has a read only access to public ledger.
 */
interface IndyVerifier {
    /**
     * Verifies proof produced by prover
     *
     * @param proofReq          proof request used by prover to create proof
     * @param proof             proof created by prover
     * @param usedData          some data from ledger needed to verify proof
     *
     * @return true/false       does proof valid?
     */
    fun verifyProof(proofReq: ProofRequest, proof: ProofInfo, usedData: DataUsedInProofJson): Boolean

    /**
     * Gets from ledger all data needed to verify proof. When prover creates proof he also uses this public data.
     * So prover and verifier are using the same public immutable data to generate cryptographic objects.
     *
     * @param did               verifier did
     * @param pool              ledger pool object
     * @param proofRequest      proof request used by prover to create proof
     * @param proof             proof created by prover
     *
     * @return                  used data in json wrapped in object
     */
    fun getDataUsedInProof(did: String, pool: Pool, proofRequest: ProofRequest, proof: ProofInfo): DataUsedInProofJson

    /**
     * Creates proof request. This function has nothing to do with Indy API, it is used just to produce well-shaped data.
     *
     * @param version           (???)
     * @param name              name of this proof request
     * @param attributes        attributes which prover needs to reveal
     * @param predicates        predicates which prover should answer
     * @param nonRevoked        <optional> time interval of [attributes] and [predicates] non-revocation
     *
     * @return                  proof request
     */
    fun createProofRequest(
        version: String,
        name: String,
        attributes: List<CredentialFieldReference>,
        predicates: List<CredentialPredicate>,
        nonRevoked: Interval? = null
    ): ProofRequest
}