package com.luxoft.blockchainlab.hyperledger.indy.wallet

import com.luxoft.blockchainlab.hyperledger.indy.models.*


/**
 * [WalletUser] is an interface that encapsulates [org.hyperledger.indy.sdk.wallet.Wallet] operations in perspective
 *  of doing some cryptographic work that is not related to [com.luxoft.blockchainlab.hyperledger.indy.ledger.LedgerUser]
 */
interface WalletUser : IndyIssuer, IndyProver, IndyVerifier, IndyTrustee


/**
 * This entity is able to create self-signed credentials.
 * Has read/write access to public ledger.
 */
interface IndyIssuer : IndyWalletHolder {

    /**
     * Signs something using wallet and did
     *
     * @param data [String] - data to sign
     * @return [String] - signed data
     */
    fun sign(data: String): String

    /**
     * Creates credential offer
     *
     * @param credentialDefinitionId [CredentialDefinitionId] - credential definition id
     *
     * @return [CredentialOffer] - created credential offer
     */
    fun createCredentialOffer(credentialDefinitionId: CredentialDefinitionId): CredentialOffer

    /**
     * Issues credential by credential request. If revocation is enabled it will hold one of [maxCredentialNumber].
     *
     * @param credentialRequest [CredentialRequestInfo] - credential request and all reliable info
     * @param proposal [String] - credential proposal
     * @param offer [CredentialOffer] - credential offer
     * @param revocationRegistryId [RevocationRegistryDefinitionId] or [null]
     *
     * @return [CredentialInfo] - credential and all reliable info
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
     * @param revocationRegistryId [RevocationRegistryDefinitionId] - revocation registry definition id
     * @param credentialRevocationId [String] - revocation registry credential index
     */
    fun revokeCredential(
        revocationRegistryId: RevocationRegistryDefinitionId,
        credentialRevocationId: String
    ): RevocationRegistryEntry
}

/**
 * This entity is able to receive credentials and create proofs about them.
 * Has read-only access to public ledger.
 */
interface IndyProver : IndyWalletHolder {

    /**
     * Creates credential request
     *
     * @param proverDid [String] - prover's did
     * @param credentialDefinition [CredentialDefinition]
     * @param offer [CredentialOffer] - credential offer
     * @param masterSecretId [String]
     *
     * @return [CredentialRequestInfo] - credential request and all reliable data
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
     * @param credentialInfo [CredentialInfo] - credential and all reliable data
     * @param credentialRequest [CredentialRequestInfo] - credential request and all reliable data
     * @param offer [CredentialOffer] - credential offer
     * @param credentialDefinition [CredentialDefinition]
     * @param revocationRegistryDefinition [RevocationRegistryDefinition] on [null]
     *
     * @return local UUID of the stored credential in the prover's wallet
     */
    fun receiveCredential(
        credentialInfo: CredentialInfo,
        credentialRequest: CredentialRequestInfo,
        offer: CredentialOffer,
        credentialDefinition: CredentialDefinition,
        revocationRegistryDefinition: RevocationRegistryDefinition?
    ): String

    /**
     * Creates proof for provided proof request
     *
     * @param proofRequest [ProofRequest] - proof request created by verifier
     * @param extraQuery Map [String] to Map [String] to [Any] - additional WQL query applied to Wallet's credential search
     *  use [com.luxoft.blockchainlab.hyperledger.indy.utils.wql] to build it nicely (examples in tests)
     * @param provideSchema [SchemaProvider] - provide schema for each credential
     * @param provideCredentialDefinition [CredentialDefinitionProvider] - provide credential definition for each credential
     * @param masterSecretId [String]
     * @param revocationStateProvider [RevocationStateProvider] or [null] - provide [RevocationState] for each credential
     *
     * @return [ProofInfo] - proof and all reliable data
     */
    fun createProof(
        proofRequest: ProofRequest,
        provideSchema: SchemaProvider,
        provideCredentialDefinition: CredentialDefinitionProvider,
        masterSecretId: String,
        extraQuery: Map<String, Map<String, Any>>?,
        revocationStateProvider: RevocationStateProvider?
    ): ProofInfo

    /**
     * Creates [RevocationState]
     *
     * @param revocationRegistryDefinition [RevocationRegistryDefinition]
     * @param revocationRegistryEntry [RevocationRegistryEntry]
     * @param credentialRevocationId [String]
     * @param timestamp [Long]
     *
     * @return [RevocationState]
     */
    fun createRevocationState(
        revocationRegistryDefinition: RevocationRegistryDefinition,
        revocationRegistryEntry: RevocationRegistryEntry,
        credentialRevocationId: String,
        timestamp: Long
    ): RevocationState

    /**
     * Creates master secret by id
     *
     * @param id [String]
     */
    fun createMasterSecret(id: String)
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
     * @param identityDetails [IdentityDetails]
     */
    fun addKnownIdentities(identityDetails: IdentityDetails)

    /**
     * Creates new schema and stores it to ledger if not exists, else restores schema from ledger
     *
     * @param name [String] - new schema name
     * @param version [String] - schema version
     * @param attributes [List] of [String] - schema attributes
     *
     * @return [Schema]
     */
    fun createSchema(name: String, version: String, attributes: List<String>): Schema

    /**
     * Creates credential definition and stores it to ledger if not exists, else restores credential definition from ledger
     *
     * @param schema [Schema] - schema to create credential definition for
     * @param enableRevocation [Boolean] - whether enable or disable revocation for this credential definition
     *
     * @return [CredentialDefinition]
     */
    fun createCredentialDefinition(schema: Schema, enableRevocation: Boolean): CredentialDefinition

    /**
     * Creates revocation registry for credential definition if there's no one in ledger
     * (usable only for those credential definition for which enableRevocation = true)
     *
     * @param credentialDefinitionId [CredentialDefinitionId] - credential definition id
     * @param maxCredentialNumber [Int] - maximum number of credentials which can be issued for this credential definition
     *                                  (example) driver agency can produce only 1000 driver licences per year
     *
     * @return [RevocationRegistryInfo]
     */
    fun createRevocationRegistry(
        credentialDefinitionId: CredentialDefinitionId,
        maxCredentialNumber: Int
    ): RevocationRegistryInfo
}

/**
 * This interface represents verifier - an entity which purpose is to verify someone's credentials.
 * It has a read only access to public ledger.
 */
interface IndyVerifier {
    /**
     * Verifies proof produced by prover
     *
     * @param proofReq [ProofRequest] - proof request used by prover to create proof
     * @param proof [ProofInfo] - proof created by prover
     * @param usedData [DataUsedInProofJson] - some data from ledger needed to verify proof
     *
     * @return [Boolean] - is proof valid?
     */
    fun verifyProof(proofReq: ProofRequest, proof: ProofInfo, usedData: DataUsedInProofJson): Boolean
}

/**
 * Represents basic entity which has indy wallet
 */
interface IndyWalletHolder {
    /**
     * Creates temporary did which can be used by identity to perform some any operations
     *
     * @param identityRecord [IdentityDetails] - identity details
     *
     * @return [String] - newly created did
     */
    fun createSessionDid(identityRecord: IdentityDetails): String

    /**
     * Gets [IdentityDetails] of current user
     *
     * @return [IdentityDetails]
     */
    fun getIdentityDetails(): IdentityDetails

    /**
     * Gets [IdentityDetails] of some did
     *
     * @param did [String]
     *
     * @return [IdentityDetails]
     */
    fun getIdentityDetails(did: String): IdentityDetails

    //TODO: return credentials only for current DID?
    /**
     * Gets Iterator [CredentialReference] in this wallet
     *
     * @return Iterator<[CredentialReference]>
     */
    fun getCredentials(): Iterator<CredentialReference>

    /**
     * Gets TAILS file path
     *
     * @return TAILS file path
     */
    fun getTailsPath(): String
}
