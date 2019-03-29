package com.luxoft.blockchainlab.hyperledger.indy.ledger

import com.luxoft.blockchainlab.hyperledger.indy.IndyUser
import com.luxoft.blockchainlab.hyperledger.indy.models.*
import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import mu.KotlinLogging
import org.hyperledger.indy.sdk.ledger.Ledger
import org.hyperledger.indy.sdk.pool.Pool
import org.hyperledger.indy.sdk.wallet.Wallet
import java.lang.Thread.sleep
import java.util.*


const val RETRY_DELAY_MS: Long = 100L
const val RETRY_TIMES: Int = 10

/**
 * This class abstracts operations on ledger
 *
 * @param pool                  indy pool handle
 */
class IndyPoolLedgerService(private val pool: Pool) : LedgerService {

    val logger = KotlinLogging.logger {}

    private fun store(data: String, did: String, wallet: Wallet) {
        val attemptId = Random().nextLong()
        logger.debug { "Trying to store data on ledger [attempt id = $attemptId]: $data" }
        val response = Ledger.signAndSubmitRequest(pool, wallet, did, data).get()
        logger.debug { "Ledger responded [attempt id = $attemptId]: $response" }
    }

    override fun storeSchema(schema: Schema, did: String, wallet: Wallet) {
        val schemaJson = SerializationUtils.anyToJSON(schema)
        val schemaRequest = Ledger.buildSchemaRequest(did, schemaJson).get()
        store(schemaRequest, did, wallet)
    }

    override fun storeRevocationRegistryDefinition(
        definition: RevocationRegistryDefinition,
        did: String,
        wallet: Wallet
    ) {
        val defJson = SerializationUtils.anyToJSON(definition)
        val defRequest = Ledger.buildRevocRegDefRequest(did, defJson).get()
        store(defRequest, did, wallet)
    }

    override fun storeRevocationRegistryEntry(
        entry: RevocationRegistryEntry,
        definitionId: String,
        definitionType: String,
        did: String,
        wallet: Wallet
    ) {
        val entryJson = SerializationUtils.anyToJSON(entry)
        val entryRequest = Ledger.buildRevocRegEntryRequest(did, definitionId, definitionType, entryJson).get()
        store(entryRequest, did, wallet)
    }

    override fun storeCredentialDefinition(definition: CredentialDefinition, did: String, wallet: Wallet) {
        val credDefJson = SerializationUtils.anyToJSON(definition)
        val request = Ledger.buildCredDefRequest(did, credDefJson).get()
        store(request, did, wallet)
    }

    override fun storeNym(about: IdentityDetails, did: String, wallet: Wallet) {
        val nymRequest = Ledger.buildNymRequest(
            did,
            about.did,
            about.verkey,
            about.alias,
            about.role
        ).get()

        Ledger.signAndSubmitRequest(pool, wallet, did, nymRequest).get()
    }

    override fun schemaExists(id: SchemaId, did: String): Boolean {
        val schemaReq = Ledger.buildGetSchemaRequest(did, id.toString()).get()

        return try {
            val schemaRes = Ledger.submitRequest(pool, schemaReq).get()
            val parsedRes = Ledger.parseGetSchemaResponse(schemaRes).get()
            SerializationUtils.jSONToAny<Schema>(parsedRes.objectJson)

            true
        } catch (e: Exception) {
            logger.error { e }
            false
        }
    }

    override fun credentialDefinitionExists(credentialDefinitionId: CredentialDefinitionId, did: String): Boolean {
        return try {
            val getCredDefRequest = Ledger.buildGetCredDefRequest(did, credentialDefinitionId.toString()).get()
            val getCredDefResponse = Ledger.submitRequest(pool, getCredDefRequest).get()
            val credDefIdInfo = Ledger.parseGetCredDefResponse(getCredDefResponse).get()
            SerializationUtils.jSONToAny<CredentialDefinition>(credDefIdInfo.objectJson)

            true
        } catch (e: Exception) {
            logger.error { e }
            false
        }
    }

    override fun revocationRegistryExists(id: RevocationRegistryDefinitionId, did: String): Boolean {
        return try {
            val request = Ledger.buildGetRevocRegDefRequest(did, id.toString()).get()
            val response = Ledger.submitRequest(pool, request).get()
            val revRegDefJson = Ledger.parseGetRevocRegDefResponse(response).get().objectJson
            SerializationUtils.jSONToAny<RevocationRegistryDefinition>(revRegDefJson)

            true
        } catch (e: Exception) {
            logger.error { e }
            false
        }
    }

    override fun retrieveSchema(id: SchemaId, did: String, delayMs: Long, retryTimes: Int): Schema? {
        val result: Schema? = null
        val schemaReq = Ledger.buildGetSchemaRequest(did, id.toString()).get()

        repeat(retryTimes) {
            try {
                val schemaRes = Ledger.submitRequest(pool, schemaReq).get()
                val parsedRes = Ledger.parseGetSchemaResponse(schemaRes).get()

                return SerializationUtils.jSONToAny<Schema>(parsedRes.objectJson)
            } catch (e: Exception) {
                logger.debug { "Schema retrieving failed (id: $id). Retry attempt $it" }
                sleep(delayMs * it)
            }
        }

        return result
    }

    override fun retrieveCredentialDefinition(
        id: CredentialDefinitionId,
        did: String,
        delayMs: Long,
        retryTimes: Int
    ): CredentialDefinition? {
        val result: CredentialDefinition? = null

        repeat(retryTimes) {
            try {
                val getCredDefRequest = Ledger.buildGetCredDefRequest(did, id.toString()).get()
                val getCredDefResponse = Ledger.submitRequest(pool, getCredDefRequest).get()
                val credDefIdInfo = Ledger.parseGetCredDefResponse(getCredDefResponse).get()

                return SerializationUtils.jSONToAny<CredentialDefinition>(
                    credDefIdInfo.objectJson
                )
            } catch (e: Exception) {
                logger.debug { "Credential definition retrieving failed (id: $id). Retry attempt $it" }
                sleep(delayMs * it)
            }
        }

        return result
    }

    override fun retrieveCredentialDefinition(
        id: SchemaId,
        did: String,
        delayMs: Long,
        retryTimes: Int
    ): CredentialDefinition? {
        val schema = retrieveSchema(id, did, delayMs, retryTimes)
            ?: throw RuntimeException("Schema is not found in ledger")

        val credentialDefinitionId = CredentialDefinitionId(
            did, schema.seqNo!!,
            IndyUser.TAG
        )

        return retrieveCredentialDefinition(credentialDefinitionId, did, delayMs, retryTimes)
    }

    override fun retrieveRevocationRegistryDefinition(
        id: RevocationRegistryDefinitionId,
        did: String,
        delayMs: Long,
        retryTimes: Int
    ): RevocationRegistryDefinition? {
        val result: RevocationRegistryDefinition? = null
        val request = Ledger.buildGetRevocRegDefRequest(did, id.toString()).get()

        repeat(retryTimes) {
            try {
                val response = Ledger.submitRequest(pool, request).get()
                val revRegDefJson = Ledger.parseGetRevocRegDefResponse(response).get().objectJson

                return SerializationUtils.jSONToAny<RevocationRegistryDefinition>(revRegDefJson)
            } catch (e: Exception) {
                logger.debug { "Revocation registry definition retrieving failed (id: $id). Retry attempt $it" }
                sleep(delayMs * it)
            }
        }

        return result
    }

    override fun retrieveRevocationRegistryEntry(
        id: RevocationRegistryDefinitionId,
        timestamp: Long,
        did: String,
        delayMs: Long,
        retryTimes: Int
    ): Pair<Long, RevocationRegistryEntry>? {
        val result: Pair<Long, RevocationRegistryEntry>? = null
        val request = Ledger.buildGetRevocRegRequest(did, id.toString(), timestamp).get()

        repeat(retryTimes) {
            try {
                val response = Ledger.submitRequest(pool, request).get()
                val revReg = Ledger.parseGetRevocRegResponse(response).get()

                val tmsp = revReg.timestamp
                val revRegEntry =
                    SerializationUtils.jSONToAny<RevocationRegistryEntry>(
                        revReg.objectJson
                    )

                return Pair(tmsp, revRegEntry)
            } catch (e: Exception) {
                logger.debug { "Revocation registry entry retrieving failed (id: $id, timestamp: $timestamp). Retry attempt $it" }
                sleep(delayMs * it)
            }
        }

        return result
    }

    override fun retrieveRevocationRegistryDelta(
        id: RevocationRegistryDefinitionId,
        interval: Interval,
        did: String,
        delayMs: Long,
        retryTimes: Int
    ): Pair<Long, RevocationRegistryEntry>? {
        val result: Pair<Long, RevocationRegistryEntry>? = null

        repeat(retryTimes) {
            try {
                val from = interval.from
                    ?: -1 // according to https://github.com/hyperledger/indy-sdk/blob/master/libindy/src/api/ledger.rs:1623

                val request = Ledger.buildGetRevocRegDeltaRequest(did, id.toString(), from, interval.to).get()
                val response = Ledger.submitRequest(pool, request).get()
                val revRegDeltaJson = Ledger.parseGetRevocRegDeltaResponse(response).get()

                val timestamp = revRegDeltaJson.timestamp
                val revRegDelta =
                    SerializationUtils.jSONToAny<RevocationRegistryEntry>(
                        revRegDeltaJson.objectJson
                    )

                return Pair(timestamp, revRegDelta)
            } catch (e: Exception) {
                logger.debug { "Revocation registry delta retrieving failed (id: $id, interval: $interval). Retry attempt $it" }
                sleep(delayMs * it)
            }
        }

        return result
    }

    override fun retrieveDataUsedInProof(
        proofRequest: ProofRequest,
        proof: ProofInfo,
        did: String,
        delayMs: Long,
        retryTimes: Int
    ): DataUsedInProofJson {
        val usedSchemas = proof.proofData.identifiers
            .map { it.getSchemaIdObject() }
            .distinct()
            .map {
                retrieveSchema(it, did, delayMs, retryTimes)
                    ?: throw RuntimeException("Schema $it doesn't exist in ledger")
            }
            .associate { it.id to it }
        val usedSchemasJson = SerializationUtils.anyToJSON(usedSchemas)

        val usedCredentialDefs = proof.proofData.identifiers
            .map { it.getCredentialDefinitionIdObject() }
            .distinct()
            .map {
                retrieveCredentialDefinition(it, did, delayMs, retryTimes)
                    ?: throw RuntimeException("Credential definition $it doesn't exist in ledger")
            }
            .associate { it.id to it }
        val usedCredentialDefsJson = SerializationUtils.anyToJSON(usedCredentialDefs)

        val (revRegDefsJson, revRegDeltasJson) = if (proofRequest.nonRevoked != null) {
            val revRegDefs = proof.proofData.identifiers
                .map { it.getRevocationRegistryIdObject()!! }
                .distinct()
                .map {
                    retrieveRevocationRegistryDefinition(it, did, delayMs, retryTimes)
                        ?: throw RuntimeException("Revocation registry definition $it doesn't exist in ledger")
                }
                .associate { it.id to it }

            val revRegDeltas = proof.proofData.identifiers
                .map { Pair(it.getRevocationRegistryIdObject()!!, it.timestamp!!) }
                .distinct()
                .associate { (revRegId, timestamp) ->
                    val response = retrieveRevocationRegistryEntry(revRegId, timestamp, did, delayMs, retryTimes)
                        ?: throw RuntimeException("Revocation registry for definition $revRegId at timestamp $timestamp doesn't exist in ledger")

                    val (tmstmp, revReg) = response
                    val map = hashMapOf<Long, RevocationRegistryEntry>()
                    map[tmstmp] = revReg

                    revRegId to map
                }

            Pair(SerializationUtils.anyToJSON(revRegDefs), SerializationUtils.anyToJSON(revRegDeltas))
        } else Pair("{}", "{}")

        return DataUsedInProofJson(usedSchemasJson, usedCredentialDefsJson, revRegDefsJson, revRegDeltasJson)
    }
}