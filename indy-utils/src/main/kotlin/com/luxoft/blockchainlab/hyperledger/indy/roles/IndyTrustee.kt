package com.luxoft.blockchainlab.hyperledger.indy.roles

import com.luxoft.blockchainlab.hyperledger.indy.models.IdentityDetails


/**
 * This entity is able to give another entity an ability to issue new credentials.
 * By default, system has only one top-level-trustee entity, which should share it's rights with others.
 * Hash read-write access to public ledger.
 */
interface IndyTrustee : IndyWalletHolder {
    /**
     * Shares rights to write to ledger with provided identity
     *
     * @param identityDetails
     */
    fun setPermissionsFor(identityDetails: IdentityDetails)

    /**
     * Adds provided identity to whitelist
     *
     * @param identityDetails
     */
    fun addKnownIdentities(identityDetails: IdentityDetails)
}