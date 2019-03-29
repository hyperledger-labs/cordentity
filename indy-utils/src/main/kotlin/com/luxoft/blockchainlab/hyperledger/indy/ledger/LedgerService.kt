package com.luxoft.blockchainlab.hyperledger.indy.ledger

import com.luxoft.blockchainlab.hyperledger.indy.models.*
import org.hyperledger.indy.sdk.wallet.Wallet
import java.lang.Thread.sleep


interface LedgerService {
    /**
     * Stores schema on ledger
     *
     * @param schema            schema to store
     */
    fun storeSchema(schema: Schema, did: String, wallet: Wallet)

    /**
     * Stores revocation registry definition on ledger
     *
     * @param definition        revocation registry definition to store
     */
    fun storeRevocationRegistryDefinition(definition: RevocationRegistryDefinition, did: String, wallet: Wallet)

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
        definitionType: String,
        did: String,
        wallet: Wallet
    )

    /**
     * Stores credential definition on ledger
     *
     * @param definition        credential definition to store
     */
    fun storeCredentialDefinition(definition: CredentialDefinition, did: String, wallet: Wallet)

    /**
     * Adds NYM record to ledger. E.g. "I trust this person"
     *
     * @param did           trustee did
     * @param wallet        trustee wallet handle
     * @param about         identity details about entity that trustee wants to trust
     */
    fun storeNym(about: IdentityDetails, did: String, wallet: Wallet)

    /**
     * Check if credential definition exist on ledger
     *
     * @param credentialDefinitionId    credential definition id
     *
     * @return                          true if exist otherwise false
     */
    fun credentialDefinitionExists(credentialDefinitionId: CredentialDefinitionId, did: String): Boolean

    /**
     * Check if schema exist on ledger
     *
     * @param id                schema id
     *
     * @return                  true if exist otherwise false
     */
    fun schemaExists(id: SchemaId, did: String): Boolean

    /**
     * Check if revocation registry exists on ledger
     *
     * @param id: [RevocationRegistryDefinitionId] - id of this registry
     * @return: [Boolean]
     */
    fun revocationRegistryExists(id: RevocationRegistryDefinitionId, did: String): Boolean

    /**
     * Retrieves schema from ledger
     *
     * @param did           indy user did
     * @param id            id of target schema
     *
     * @return              schema or null if none exists on ledger
     */
    fun retrieveSchema(
        id: SchemaId,
        did: String,
        delayMs: Long = RETRY_DELAY_MS,
        retryTimes: Int = RETRY_TIMES
    ): Schema?

    /**
     * Retrieves credential definition from ledger
     *
     * @param did           indy user did
     * @param id            id of target credential definition
     *
     * @return              credential definition or null if none exists on ledger
     */
    fun retrieveCredentialDefinition(
        id: CredentialDefinitionId,
        did: String,
        delayMs: Long = RETRY_DELAY_MS,
        retryTimes: Int = RETRY_TIMES
    ): CredentialDefinition?

    /**
     * Retrieves credential definition from ledger by schema Id
     *
     * @param did           indy user did
     * @param id            schema id
     *
     * @return              credential definition or null if it doesn't exist in ledger
     */
    fun retrieveCredentialDefinition(
        id: SchemaId,
        did: String,
        delayMs: Long = RETRY_DELAY_MS,
        retryTimes: Int = RETRY_TIMES
    ): CredentialDefinition?

    /**
     * Retrieves revocation registry definition from ledger
     *
     * @param did           indy user did
     * @param id            target revocation registry definition id
     *
     * @return              revocation registry definition or null if none exists on ledger
     */
    fun retrieveRevocationRegistryDefinition(
        id: RevocationRegistryDefinitionId,
        did: String,
        delayMs: Long = RETRY_DELAY_MS,
        retryTimes: Int = RETRY_TIMES
    ): RevocationRegistryDefinition?

    /**
     * Retrieves revocation registry entry from ledger
     *
     * @param did           indy user did
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
        did: String,
        delayMs: Long = RETRY_DELAY_MS,
        retryTimes: Int = RETRY_TIMES
    ): Pair<Long, RevocationRegistryEntry>?

    /**
     * Retrieves revocation registry delta from ledger
     *
     * @param did           indy user did
     * @param id            revocation registry definition id
     * @param interval      time interval you are interested in
     *
     * @return              revocation registry delta or null if none exists on ledger
     */
    fun retrieveRevocationRegistryDelta(
        id: RevocationRegistryDefinitionId,
        interval: Interval,
        did: String,
        delayMs: Long = RETRY_DELAY_MS,
        retryTimes: Int = RETRY_TIMES
    ): Pair<Long, RevocationRegistryEntry>?

    /**
     * Gets from ledger all data needed to verify proof. When prover creates proof he also uses this public data.
     * So prover and verifier are using the same public immutable data to generate cryptographic objects.
     *
     * @param did               verifier did
     * @param proofRequest      proof request used by prover to create proof
     * @param proof             proof created by prover
     *
     * @return                  used data in json wrapped in object
     */
    fun retrieveDataUsedInProof(
        proofRequest: ProofRequest,
        proof: ProofInfo,
        did: String,
        delayMs: Long = RETRY_DELAY_MS,
        retryTimes: Int = RETRY_TIMES
    ): DataUsedInProofJson
}