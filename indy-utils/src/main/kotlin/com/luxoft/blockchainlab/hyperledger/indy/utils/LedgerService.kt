package com.luxoft.blockchainlab.hyperledger.indy.utils

import com.luxoft.blockchainlab.hyperledger.indy.*
import mu.KotlinLogging
import org.hyperledger.indy.sdk.ledger.Ledger
import org.hyperledger.indy.sdk.pool.Pool
import org.hyperledger.indy.sdk.wallet.Wallet
import java.lang.Thread.sleep
import java.util.*

/**
 * This class abstracts operations on ledger
 *
 * @param did                   indy user's did
 * @param wallet                indy user's wallet handle
 * @param pool                  indy pool handle
 */
class LedgerService(private val did: String, private val wallet: Wallet, private val pool: Pool) {

    private fun store(data: String) {
        val attemptId = Random().nextLong()
        logger.debug("Trying to store data on ledger [attempt id = $attemptId]: $data")
        val response = Ledger.signAndSubmitRequest(pool, wallet, did, data).get()
        logger.debug("Ledger responded [attempt id = $attemptId]: $response")
    }

    /**
     * Stores schema on ledger
     *
     * @param schema            schema to store
     */
    fun storeSchema(schema: Schema) {
        val schemaJson = SerializationUtils.anyToJSON(schema)
        val schemaRequest = Ledger.buildSchemaRequest(did, schemaJson).get()
        store(schemaRequest)
    }

    /**
     * Stores revocation registry definition on ledger
     *
     * @param definition        revocation registry definition to store
     */
    fun storeRevocationRegistryDefinition(definition: RevocationRegistryDefinition) {
        val defJson = SerializationUtils.anyToJSON(definition)
        val defRequest = Ledger.buildRevocRegDefRequest(did, defJson).get()
        store(defRequest)
    }

    /**
     * Stores revocation registry entry on ledger (when credential is just created)
     *
     * @param entry             revocation registry entry to store
     * @param definitionId      id of revocation registry definition coupled with this revocation registry
     * @param definitionType    revocation registry definition type
     */
    fun storeRevocationRegistryEntry(entry: RevocationRegistryEntry, definitionId: String, definitionType: String) {
        val entryJson = SerializationUtils.anyToJSON(entry)
        val entryRequest = Ledger.buildRevocRegEntryRequest(did, definitionId, definitionType, entryJson).get()
        store(entryRequest)
    }

    /**
     * Stores credential definition on ledger
     *
     * @param definition        credential definition to store
     */
    fun storeCredentialDefinition(definition: CredentialDefinition) {
        val credDefJson = SerializationUtils.anyToJSON(definition)
        val request = Ledger.buildCredDefRequest(did, credDefJson).get()
        store(request)
    }

    /**
     * Shortcut to [LedgerService.retrieveSchema]
     */
    fun retrieveSchema(id: String) = LedgerService.retrieveSchema(did, pool, id)

    /**
     * Shortcut to [LedgerService.retrieveCredentialDefinition]
     */
    fun retrieveCredentialDefinition(id: String) = LedgerService.retrieveCredentialDefinition(did, pool, id)

    /**
     * Shortcut to [LedgerService.retrieveRevocationRegistryDefinition]
     */
    fun retrieveRevocationRegistryDefinition(id: String) =
        LedgerService.retrieveRevocationRegistryDefinition(did, pool, id)

    /**
     * Shortcut to [LedgerService.retrieveRevocationRegistryEntry]
     */
    fun retrieveRevocationRegistryEntry(id: String, timestamp: Long) =
        LedgerService.retrieveRevocationRegistryEntry(did, pool, id, timestamp)

    /**
     * Shortcut to [LedgerService.retrieveRevocationRegistryDelta]
     */
    fun retrieveRevocationRegistryDelta(id: String, interval: Interval) =
        LedgerService.retrieveRevocationRegistryDelta(did, pool, id, interval)

    /**
     * Shortcut to [LedgerService.addNym]
     */
    fun addNym(about: IdentityDetails) = LedgerService.addNym(did, pool, wallet, about)

    companion object {
        val logger = KotlinLogging.logger {}

        private val delayMs = 100L
        private val retryTimes = 10

        /**
         * Adds NYM record to ledger. E.g. "I trust this person"
         *
         * @param did           trustee did
         * @param pool          indy pool handle
         * @param wallet        trustee wallet handle
         * @param about         identity details about entity that trustee wants to trust
         */
        fun addNym(did: String, pool: Pool, wallet: Wallet, about: IdentityDetails) {
            val nymRequest = Ledger.buildNymRequest(
                did,
                about.did,
                about.verkey,
                about.alias,
                about.role
            ).get()

            Ledger.signAndSubmitRequest(pool, wallet, did, nymRequest).get()
        }

        /**
         * Retrieves schema from ledger
         *
         * @param did           indy user did
         * @param pool          indy pool handle
         * @param id            id of target schema
         *
         * @return              schema or null if none exists on ledger
         */
        fun retrieveSchema(did: String, pool: Pool, id: String): Schema? = run {
            val result: Schema? = null

            repeat(retryTimes) {
                try {
                    val schemaReq = Ledger.buildGetSchemaRequest(did, id).get()
                    val schemaRes = Ledger.submitRequest(pool, schemaReq).get()
                    val parsedRes = Ledger.parseGetSchemaResponse(schemaRes).get()

                    return@run SerializationUtils.jSONToAny<Schema>(parsedRes.objectJson)
                } catch (e: Exception) {
                    logger.debug("Schema retrieving failed (id: $id). Retry attempt $it")
                    sleep(delayMs * it)
                }
            }

            result
        }

        /**
         * Retrieves credential definition from ledger
         *
         * @param did           indy user did
         * @param pool          indy pool handle
         * @param id            id of target credential definition
         *
         * @return              credential definition or null if none exists on ledger
         */
        fun retrieveCredentialDefinition(did: String, pool: Pool, id: String): CredentialDefinition? = run {
            val result: CredentialDefinition? = null

            repeat(retryTimes) {
                try {
                    val getCredDefRequest = Ledger.buildGetCredDefRequest(did, id).get()
                    val getCredDefResponse = Ledger.submitRequest(pool, getCredDefRequest).get()
                    val credDefIdInfo = Ledger.parseGetCredDefResponse(getCredDefResponse).get()

                    return@run SerializationUtils.jSONToAny<CredentialDefinition>(credDefIdInfo.objectJson)
                } catch (e: Exception) {
                    logger.debug("Credential definition retrieving failed (id: $id). Retry attempt $it")
                    sleep(delayMs * it)
                }
            }

            result
        }

        /**
         * Retrieves revocation registry definition from ledger
         *
         * @param did           indy user did
         * @param pool          indy pool handle
         * @param id            target revocation registry definition id
         *
         * @return              revocation registry definition or null if none exists on ledger
         */
        fun retrieveRevocationRegistryDefinition(did: String, pool: Pool, id: String): RevocationRegistryDefinition? =
            run {
                val result: RevocationRegistryDefinition? = null

                repeat(retryTimes) {
                    try {
                        val request = Ledger.buildGetRevocRegDefRequest(did, id).get()
                        val response = Ledger.submitRequest(pool, request).get()
                        val revRegDefJson = Ledger.parseGetRevocRegDefResponse(response).get().objectJson

                        return@run SerializationUtils.jSONToAny<RevocationRegistryDefinition>(revRegDefJson)
                    } catch (e: Exception) {
                        logger.debug("Revocation registry definition retrieving failed (id: $id). Retry attempt $it")
                        sleep(delayMs * it)
                    }
                }

                result
            }

        /**
         * Retrieves revocation registry entry from ledger
         *
         * @param did           indy user did
         * @param pool          indy pool handle
         * @param id            revocation registry id
         * @param timestamp     time from unix epoch in seconds representing time moment you are
         *                      interested in e.g. if you want to know current revocation state,
         *                      you pass 'now' as a timestamp
         *
         * @return              revocation registry entry or null if none exists on ledger
         */
        fun retrieveRevocationRegistryEntry(
            did: String,
            pool: Pool,
            id: String,
            timestamp: Long
        ): Pair<Long, RevocationRegistryEntry>? = run {
            val result: Pair<Long, RevocationRegistryEntry>? = null

            repeat(retryTimes) {
                try {
                    val request = Ledger.buildGetRevocRegRequest(did, id, timestamp).get()
                    val response = Ledger.submitRequest(pool, request).get()
                    val revReg = Ledger.parseGetRevocRegResponse(response).get()

                    val tmsp = revReg.timestamp
                    val revRegEntry = SerializationUtils.jSONToAny<RevocationRegistryEntry>(revReg.objectJson)

                    return@run Pair(tmsp, revRegEntry)
                } catch (e: Exception) {
                    logger.debug("Revocation registry entry retrieving failed (id: $id, timestamp: $timestamp). Retry attempt $it")
                    sleep(delayMs * it)
                }
            }

            result
        }

        /**
         * Retrieves revocation registry delta from ledger
         *
         * @param did           indy user did
         * @param pool          indy pool handle
         * @param id            revocation registry definition id
         * @param interval      time interval you are interested in
         *
         * @return              revocation registry delta or null if none exists on ledger
         */
        fun retrieveRevocationRegistryDelta(
            did: String,
            pool: Pool,
            id: String,
            interval: Interval
        ): Pair<Long, RevocationRegistryEntry>? = run {
            val result: Pair<Long, RevocationRegistryEntry>? = null

            repeat(retryTimes) {
                try {
                    val from = interval.from
                        ?: -1 // according to https://github.com/hyperledger/indy-sdk/blob/master/libindy/src/api/ledger.rs:1623

                    val request = Ledger.buildGetRevocRegDeltaRequest(did, id, from, interval.to).get()
                    val response = Ledger.submitRequest(pool, request).get()
                    val revRegDeltaJson = Ledger.parseGetRevocRegDeltaResponse(response).get()

                    val timestamp = revRegDeltaJson.timestamp
                    val revRegDelta = SerializationUtils.jSONToAny<RevocationRegistryEntry>(revRegDeltaJson.objectJson)

                    return@run Pair(timestamp, revRegDelta)
                } catch (e: Exception) {
                    logger.debug("Revocation registry delta retrieving failed (id: $id, interval: $interval). Retry attempt $it")
                    sleep(delayMs * it)
                }
            }

            result
        }
    }
}