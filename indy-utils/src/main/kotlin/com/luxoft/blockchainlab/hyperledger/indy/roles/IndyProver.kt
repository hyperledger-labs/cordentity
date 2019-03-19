package com.luxoft.blockchainlab.hyperledger.indy.roles

import com.luxoft.blockchainlab.hyperledger.indy.*
import com.luxoft.blockchainlab.hyperledger.indy.models.*


/**
 * This entity is able to receive credentials and create proofs about them.
 * Has read-only access to public ledger.
 */
interface IndyProver : IndyWalletHolder {

    /**
     * Creates credential request
     *
     * @param proverDid                 prover's did
     * @param offer                     credential offer
     *
     * @return                          credential request and all reliable data
     */
    fun createCredentialRequest(proverDid: String, offer: CredentialOffer): CredentialRequestInfo

    /**
     * Stores credential in prover's wallet
     *
     * @param credentialInfo            credential and all reliable data
     * @param credentialRequest         credential request and all reliable data
     * @param offer                     credential offer
     */
    fun receiveCredential(
        credentialInfo: CredentialInfo,
        credentialRequest: CredentialRequestInfo,
        offer: CredentialOffer
    )

    /**
     * Creates proof for provided proof request
     *
     * @param proofRequest              proof request created by verifier
     *
     * @return                          proof and all reliable data
     */
    fun createProof(proofRequest: ProofRequest): ProofInfo
}