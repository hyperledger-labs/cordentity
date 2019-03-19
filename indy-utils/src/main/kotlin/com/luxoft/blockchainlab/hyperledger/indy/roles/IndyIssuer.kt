package com.luxoft.blockchainlab.hyperledger.indy.roles

import com.luxoft.blockchainlab.hyperledger.indy.*
import com.luxoft.blockchainlab.hyperledger.indy.models.*


/**
 * This entity is able to create self-signed credentials.
 * Has read/write access to public ledger.
 */
interface IndyIssuer : IndyWalletHolder {
    /**
     * Creates new schema and stores it to ledger if not exists, else restores schema from ledger
     *
     * @param name                      new schema name
     * @param version                   schema version (???)
     * @param attributes                schema attributes
     *
     * @return                          created schema
     */
    fun createSchema(name: String, version: String, attributes: List<String>): Schema

    /**
     * Creates credential definition and stores it to ledger if not exists, else restores credential definition from ledger
     *
     * @param schemaId                  id of schema to create credential definition for
     * @param enableRevocation          whether enable or disable revocation for this credential definition
     *                                  (hint) turn this on by default, but just don't revoke credentials
     *
     * @return                          created credential definition
     */
    fun createCredentialDefinition(schemaId: SchemaId, enableRevocation: Boolean): CredentialDefinition

    /**
     * Creates revocation registry for credential definition if there's no one in ledger
     * (usable only for those credential definition for which enableRevocation = true)
     *
     * @param credentialDefinitionId    credential definition id
     * @param maxCredentialNumber       maximum number of credentials which can be issued for this credential definition
     *                                  (example) driver agency can produce only 1000 driver licences per year
     *
     * @return                          created
     */
    fun createRevocationRegistry(
        credentialDefinitionId: CredentialDefinitionId,
        maxCredentialNumber: Int = 5
    ): RevocationRegistryInfo

    /**
     * Creates credential offer
     *
     * @param credentialDefinitionId    credential definition id
     *
     * @return                          created credential offer
     */
    fun createCredentialOffer(credentialDefinitionId: CredentialDefinitionId): CredentialOffer

    /**
     * Issues credential by credential request. If revocation is enabled it will hold one of [maxCredentialNumber].
     *
     * @param credentialRequest         credential request and all reliable info
     * @param proposal                  credential proposal
     * @param offer                     credential offer
     *
     * @return                          credential and all reliable info
     */
    fun issueCredential(
        credentialRequest: CredentialRequestInfo,
        proposal: String,
        offer: CredentialOffer
    ): CredentialInfo

    /**
     * Revokes previously issued credential
     *
     * @param revocationRegistryId      revocation registry definition id
     * @param credentialRevocationId    revocation registry credential index
     */
    fun revokeCredential(revocationRegistryId: RevocationRegistryDefinitionId, credentialRevocationId: String)
}