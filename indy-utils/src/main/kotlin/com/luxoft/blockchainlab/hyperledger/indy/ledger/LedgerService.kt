package com.luxoft.blockchainlab.hyperledger.indy.ledger

import com.luxoft.blockchainlab.hyperledger.indy.models.*


interface LedgerService {
    /**
     * Stores schema on ledger
     *
     * @param schema            schema to store
     */
    fun storeSchema(schema: Schema)

    /**
     * Stores revocation registry definition on ledger
     *
     * @param definition        revocation registry definition to store
     */
    fun storeRevocationRegistryDefinition(definition: RevocationRegistryDefinition)

    /**
     * Stores revocation registry entry on ledger (when credential is just created)
     *
     * @param entry             revocation registry entry to store
     * @param definitionId      id of revocation registry definition coupled with this revocation registry
     * @param definitionType    revocation registry definition type
     */
    fun storeRevocationRegistryEntry(
        entry: RevocationRegistryEntry,
        definitionId: String,
        definitionType: String
    )

    /**
     * Stores credential definition on ledger
     *
     * @param definition        credential definition to store
     */
    fun storeCredentialDefinition(definition: CredentialDefinition)

    /**
     * Adds NYM record to ledger. E.g. "I trust this person"
     *
     * @param about         identity details about entity that trustee wants to trust
     */
    fun storeNym(about: IdentityDetails)

    /**
     * Check if credential definition exist on ledger
     *
     * @param credentialDefinitionId    credential definition id
     *
     * @return                          true if exist otherwise false
     */
    fun credentialDefinitionExists(credentialDefinitionId: CredentialDefinitionId): Boolean

    /**
     * Check if schema exist on ledger
     *
     * @param id                schema id
     *
     * @return                  true if exist otherwise false
     */
    fun schemaExists(id: SchemaId): Boolean

    /**
     * Check if revocation registry exists on ledger
     *
     * @param id: [RevocationRegistryDefinitionId] - id of this registry
     * @return: [Boolean]
     */
    fun revocationRegistryExists(id: RevocationRegistryDefinitionId): Boolean

    /**
     * Retrieves schema from ledger
     *
     * @param id            id of target schema
     *
     * @return              schema or null if none exists on ledger
     */
    fun retrieveSchema(
        id: SchemaId,
        delayMs: Long = RETRY_DELAY_MS,
        retryTimes: Int = RETRY_TIMES
    ): Schema?

    /**
     * Retrieves credential definition from ledger
     *
     * @param id            id of target credential definition
     *
     * @return              credential definition or null if none exists on ledger
     */
    fun retrieveCredentialDefinition(
        id: CredentialDefinitionId,
        delayMs: Long = RETRY_DELAY_MS,
        retryTimes: Int = RETRY_TIMES
    ): CredentialDefinition?

    /**
     * Retrieves credential definition from ledger by schema Id
     *
     * @param id            schema id
     *
     * @return              credential definition or null if it doesn't exist in ledger
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
     * @param id            target revocation registry definition id
     *
     * @return              revocation registry definition or null if none exists on ledger
     */
    fun retrieveRevocationRegistryDefinition(
        id: RevocationRegistryDefinitionId,
        delayMs: Long = RETRY_DELAY_MS,
        retryTimes: Int = RETRY_TIMES
    ): RevocationRegistryDefinition?

    /**
     * Retrieves revocation registry entry from ledger
     *
     * @param id            revocation registry id
     * @param timestamp     time from unix epoch in seconds representing time moment you are
     *                      interested in e.g. if you want to know current revocation state,
     *                      you pass 'now' as a timestamp
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
     * @param id            revocation registry definition id
     * @param interval      time interval you are interested in
     *
     * @return              revocation registry delta or null if none exists on ledger
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
     * @param proofRequest      proof request used by prover to create proof
     * @param proof             proof created by prover
     *
     * @return                  used data in json wrapped in object
     */
    fun retrieveDataUsedInProof(
        proofRequest: ProofRequest,
        proof: ProofInfo,
        delayMs: Long = RETRY_DELAY_MS,
        retryTimes: Int = RETRY_TIMES
    ): DataUsedInProofJson
}