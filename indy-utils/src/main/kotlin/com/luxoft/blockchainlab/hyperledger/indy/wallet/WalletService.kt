package com.luxoft.blockchainlab.hyperledger.indy.wallet

import com.luxoft.blockchainlab.hyperledger.indy.models.*


interface WalletService : IndyIssuer, IndyProver, IndyVerifier, IndyTrustee


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
     * @param schema                    schema to create credential definition for
     * @param enableRevocation          whether enable or disable revocation for this credential definition
     *                                  (hint) turn this on by default, but just don't revoke credentials
     *
     * @return                          created credential definition
     */
    fun createCredentialDefinition(schema: Schema, enableRevocation: Boolean): CredentialDefinition

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
        maxCredentialNumber: Int
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
        offer: CredentialOffer,
        revocationRegistryId: RevocationRegistryDefinitionId?
    ): CredentialInfo

    /**
     * Revokes previously issued credential
     *
     * @param revocationRegistryId      revocation registry definition id
     * @param credentialRevocationId    revocation registry credential index
     */
    fun revokeCredential(revocationRegistryId: RevocationRegistryDefinitionId, credentialRevocationId: String): RevocationRegistryEntry
}

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
    fun createCredentialRequest(
        proverDid: String,
        credentialDefinition: CredentialDefinition,
        offer: CredentialOffer,
        masterSecretId: String
    ): CredentialRequestInfo

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
        offer: CredentialOffer,
        credentialDefinition: CredentialDefinition,
        revocationRegistryDefinition: RevocationRegistryDefinition?
    )

    /**
     * Creates proof for provided proof request
     *
     * @param proofRequest              proof request created by verifier
     *
     * @return                          proof and all reliable data
     */
    fun createProof(
        proofRequest: ProofRequest,
        provideSchema: SchemaProvider,
        provideCredentialDefinition: CredentialDefinitionProvider,
        masterSecretId: String,
        revocationStateProvider: RevocationStateProvider?
    ): ProofInfo
}

/**
 * This entity is able to give another entity an ability to issue new credentials.
 * By default, system has only one top-level-trustee entity, which should share it's rights with others.
 * Hash read-write access to public ledger.
 */
interface IndyTrustee : IndyWalletHolder {
    /**
     * Adds provided identity to whitelist
     *
     * @param identityDetails
     */
    fun addKnownIdentities(identityDetails: IdentityDetails)
}

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
     * Creates proof request. This function has nothing to do with Indy API, it is used just to produce well-shaped data.
     *
     * @param version           (???)
     * @param name              name of this proof request
     * @param attributes        attributes which prover needs to reveal
     * @param predicates        predicates which prover should answer
     * @param nonRevoked        time interval of [attributes] and [predicates] non-revocation
     *
     * @return                  proof request
     */
    fun createProofRequest(
        version: String,
        name: String,
        attributes: List<CredentialFieldReference>,
        predicates: List<CredentialPredicate>,
        nonRevoked: Interval?,
        nonce: String = "123123"
    ): ProofRequest
}

/**
 * Represents basic entity which has indy wallet
 */
interface IndyWalletHolder {
    var did: String
    var verkey: String

    /**
     * Creates temporary did which can be used by identity to perform some any operations
     *
     * @param identityRecord            identity details
     *
     * @return                          newly created did
     */
    fun createSessionDid(identityRecord: IdentityDetails): String
}
