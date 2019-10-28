package com.luxoft.blockchainlab.hyperledger.indy.ledger

import com.luxoft.blockchainlab.hyperledger.indy.models.*


/**
 * [LedgerUser] is an interface that encapsulates all work related to storing and retrieving of public data
 */
interface LedgerUser {
    val did: String
    /**
     * Stores schema on ledger
     *
     * @param schema [Schema]
     */
    fun storeSchema(schema: Schema)

    /**
     * Stores revocation registry definition on ledger
     *
     * @param definition [RevocationRegistryDefinition] - revocation registry definition to store
     */
    fun storeRevocationRegistryDefinition(definition: RevocationRegistryDefinition)

    /**
     * Stores revocation registry entry on ledger (when credential is just created)
     *
     * @param entry [RevocationRegistryEntry] - revocation registry entry to store
     * @param definitionId [String] - id of revocation registry definition coupled with this revocation registry
     * @param definitionType [String] - revocation registry definition type
     */
    fun storeRevocationRegistryEntry(
        entry: RevocationRegistryEntry,
        definitionId: String,
        definitionType: String
    )

    /**
     * Stores credential definition on ledger
     *
     * @param definition [CredentialDefinition] - credential definition to store
     */
    fun storeCredentialDefinition(definition: CredentialDefinition)

    /**
     * Adds NYM record to ledger. E.g. "I trust this person"
     *
     * @param about [IdentityDetails] - identity details about entity that trustee wants to trust
     */
    fun storeNym(about: IdentityDetails)

    /**
     * Gets NYM record from ledger. E.g. "This person is trusted"
     *
     * @param about [IdentityDetails] - identity details about entity
     *
     * @return [String] - NYM details
     */
    fun getNym(about: IdentityDetails): NymResponse

    /**
     * Check if credential definition exist on ledger
     *
     * @param credentialDefinitionId [CredentialDefinitionId] - credential definition id
     *
     * @return [Boolean] - true if exist otherwise false
     */
    fun credentialDefinitionExists(credentialDefinitionId: CredentialDefinitionId): Boolean

    /**
     * Check if schema exist on ledger
     *
     * @param id [SchemaId] - schema id
     *
     * @return [Boolean] - true if exist otherwise false
     */
    fun schemaExists(id: SchemaId): Boolean

    /**
     * Check if revocation registry exists on ledger
     *
     * @param id [RevocationRegistryDefinitionId] - id of this registry
     * @return [Boolean]
     */
    fun revocationRegistryExists(id: RevocationRegistryDefinitionId): Boolean

    /**
     * Retrieves schema from ledger
     *
     * @param id [SchemaId] - id of target schema
     * @param delayMs [Long]
     * @param retryTimes [Int]
     *
     * @return [Schema] or [null] if none exists on ledger
     */
    fun retrieveSchema(
        id: SchemaId,
        delayMs: Long = RETRY_DELAY_MS,
        retryTimes: Int = RETRY_TIMES
    ): Schema?

    /**
     * Retrieves credential definition from ledger
     *
     * @param id [CredentialDefinitionId] - id of target credential definition
     * @param delayMs [Long]
     * @param retryTimes [Int]
     *
     * @return [CredentialDefinition] or [null] if none exists on ledger
     */
    fun retrieveCredentialDefinition(
        id: CredentialDefinitionId,
        delayMs: Long = RETRY_DELAY_MS,
        retryTimes: Int = RETRY_TIMES
    ): CredentialDefinition?

    /**
     * Retrieves credential definition from ledger by schema Id
     *
     * @param id [SchemaId] - schema id
     * @param tag [String]
     * @param delayMs [Long]
     * @param retryTimes [Int]
     *
     * @return [CredentialDefinition] or [null] if it doesn't exist in ledger
     */
    fun retrieveCredentialDefinition(
        id: SchemaId,
        tag: String,
        delayMs: Long = RETRY_DELAY_MS,
        retryTimes: Int = RETRY_TIMES
    ): CredentialDefinition?

    /**
     * Retrieves revocation registry definition from ledger
     *
     * @param id [RevocationRegistryDefinitionId] - target revocation registry definition id
     * @param delayMs [Long]
     * @param retryTimes [Int]
     *
     * @return [RevocationRegistryDefinition] or [null] if none exists on ledger
     */
    fun retrieveRevocationRegistryDefinition(
        id: RevocationRegistryDefinitionId,
        delayMs: Long = RETRY_DELAY_MS,
        retryTimes: Int = RETRY_TIMES
    ): RevocationRegistryDefinition?

    /**
     * Retrieves revocation registry entry from ledger
     *
     * @param id [RevocationRegistryDefinitionId] - revocation registry id
     * @param timestamp [Long] - time from unix epoch in seconds representing time moment you are
     *                              interested in e.g. if you want to know current revocation state,
     *                              you pass 'now' as a timestamp
     * @param delayMs [Long]
     * @param retryTimes [Int]
     *
     * @return              revocation registry entry or null if none exists on ledger
     */
    fun retrieveRevocationRegistryEntry(
        id: RevocationRegistryDefinitionId,
        timestamp: Long,
        delayMs: Long = RETRY_DELAY_MS,
        retryTimes: Int = RETRY_TIMES
    ): Pair<Long, RevocationRegistryEntry>?

    /**
     * Retrieves revocation registry delta from ledger
     *
     * @param id [RevocationRegistryDefinitionId] - revocation registry definition id
     * @param interval [Interval] - time interval you are interested in
     * @param delayMs [Long]
     * @param retryTimes [Int]
     * TODO: Why do we need timestamp?
     * @return ([Pair] of [Long] (timestamp) and [RevocationRegistryEntry]) or [null] if none exists on ledger
     */
    fun retrieveRevocationRegistryDelta(
        id: RevocationRegistryDefinitionId,
        interval: Interval,
        delayMs: Long = RETRY_DELAY_MS,
        retryTimes: Int = RETRY_TIMES
    ): Pair<Long, RevocationRegistryEntry>?

    /**
     * Gets from ledger all data needed to verify proof. When prover creates proof he also uses this public data.
     * So prover and verifier are using the same public immutable data to generate cryptographic objects.
     *
     * @param proofRequest [ProofRequest] - proof request used by prover to create proof
     * @param proof [ProofInfo] - proof created by prover
     * @param delayMs [Long]
     * @param retryTimes [Int]
     *
     * @return [DataUsedInProofJson] - used data in json wrapped in object
     */
    fun retrieveDataUsedInProof(
        proofRequest: ProofRequest,
        proof: ProofInfo,
        delayMs: Long = RETRY_DELAY_MS,
        retryTimes: Int = RETRY_TIMES
    ): DataUsedInProofJson

}
