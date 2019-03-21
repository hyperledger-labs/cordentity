package com.luxoft.blockchainlab.hyperledger.indy.roles

import com.luxoft.blockchainlab.hyperledger.indy.models.IdentityDetails


/**
 * Represents basic entity which has indy wallet
 */
interface IndyWalletHolder {
    val did: String

    /**
     * Gets identity details by did
     *
     * @param did           target did
     *
     * @return              identity details
     */
    fun getIdentity(did: String): IdentityDetails

    /**
     * Creates temporary did which can be used by identity to perform some any operations
     *
     * @param identityRecord            identity details
     *
     * @return                          newly created did
     */
    fun createSessionDid(identityRecord: IdentityDetails): String

    /**
     * Gets identity details of this indy user
     */
    fun getIdentity() = getIdentity(did)
}