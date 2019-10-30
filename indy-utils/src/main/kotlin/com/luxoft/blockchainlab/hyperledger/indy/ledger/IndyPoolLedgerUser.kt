package com.luxoft.blockchainlab.hyperledger.indy.ledger

import com.luxoft.blockchainlab.hyperledger.indy.models.*
import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import mu.KotlinLogging
import org.hyperledger.indy.sdk.ledger.Ledger
import org.hyperledger.indy.sdk.pool.Pool
import java.lang.Thread.sleep
import java.util.*


const val RETRY_DELAY_MS: Long = 100L
const val RETRY_TIMES: Int = 10

/**
 * This is an implementation of [LedgerUser] which uses standard indy pool as ledger.
 *
 * @param pool [Pool] - indy pool handle
 * @param did [String] - indy user did
 * @param signProvider ([String]) -> [String] - lambda that is used to sign ledger requests
 */
class IndyPoolLedgerUser(val pool: Pool, override val did: String, val signProvider: (data: String) -> String) : LedgerUser {

    private val logger = KotlinLogging.logger {}

    private fun store(request: String) {
        val attemptId = Random().nextLong()
        logger.debug { "Trying to store data on ledger [attempt id = $attemptId]: $request" }

        val signedRequest = signProvider(request)

        val response = Ledger.submitRequest(pool, signedRequest).get()
        logger.debug { "Ledger responded [attempt id = $attemptId]: $response" }
    }

    override fun storeSchema(schema: Schema) {
        val schemaJson = SerializationUtils.anyToJSON(schema)
        val schemaRequest = Ledger.buildSchemaRequest(did, schemaJson).get()
        store(schemaRequest)
    }

    override fun storeRevocationRegistryDefinition(definition: RevocationRegistryDefinition) {
        val defJson = SerializationUtils.anyToJSON(definition)
        val defRequest = Ledger.buildRevocRegDefRequest(did, defJson).get()
        store(defRequest)
    }

    override fun storeRevocationRegistryEntry(
        entry: RevocationRegistryEntry,
        definitionId: String,
        definitionType: String
    ) {
        val entryJson = SerializationUtils.anyToJSON(entry)
        val entryRequest = Ledger.buildRevocRegEntryRequest(did, definitionId, definitionType, entryJson).get()
        store(entryRequest)
    }

    override fun storeCredentialDefinition(definition: CredentialDefinition) {
        val credDefJson = SerializationUtils.anyToJSON(definition)
        val request = Ledger.buildCredDefRequest(did, credDefJson).get()
        store(request)
    }

    override fun storeNym(about: IdentityDetails) {
        val nymRequest = Ledger.buildNymRequest(
            did,
            about.did,
            about.verkey,
            about.alias,
            about.role
        ).get()

        store(nymRequest)
    }

    override fun getNym(about: IdentityDetails): NymResponse {
        val request = Ledger.buildGetNymRequest(
                did,
                about.did
        ).get()

        val json = Ledger.submitRequest(pool, request).get()

        return SerializationUtils.jSONToAny(json)
    }

    override fun schemaExists(id: SchemaId): Boolean {
        val schemaReq = Ledger.buildGetSchemaRequest(did, id.toString()).get()

        return try {
            val schemaRes = Ledger.submitRequest(pool, schemaReq).get()
            val parsedRes = Ledger.parseGetSchemaResponse(schemaRes).get()
            SerializationUtils.jSONToAny<Schema>(parsedRes.objectJson)

            true
        } catch (e: Exception) {
            logger.debug { e }
            false
        }
    }

    override fun credentialDefinitionExists(credentialDefinitionId: CredentialDefinitionId): Boolean {
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

    override fun revocationRegistryExists(id: RevocationRegistryDefinitionId): Boolean {
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

    override fun retrieveSchema(id: SchemaId, delayMs: Long, retryTimes: Int): Schema? {
        repeat(retryTimes) {
            try {
                val schemaReq = Ledger.buildGetSchemaRequest(did, id.toString()).get()
                val schemaRes = Ledger.submitRequest(pool, schemaReq).get()
                val parsedRes = Ledger.parseGetSchemaResponse(schemaRes).get()

                return SerializationUtils.jSONToAny<Schema>(parsedRes.objectJson)
            } catch (e: Exception) {
                logger.debug { "Schema retrieving failed (id: $id). Retry attempt $it" }
                sleep(delayMs * it)
            }
        }

        return null
    }

    override fun retrieveCredentialDefinition(
        id: CredentialDefinitionId,
        delayMs: Long,
        retryTimes: Int
    ): CredentialDefinition? {
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

        return null
    }

    override fun retrieveCredentialDefinition(
        id: SchemaId,
        tag: String,
        delayMs: Long,
        retryTimes: Int
    ): CredentialDefinition? {
        val schema = retrieveSchema(id, delayMs, retryTimes)
            ?: throw RuntimeException("Schema is not found in ledger")

        val credentialDefinitionId = CredentialDefinitionId(did, schema.seqNo!!, tag)

        return retrieveCredentialDefinition(credentialDefinitionId, delayMs, retryTimes)
    }

    override fun retrieveRevocationRegistryDefinition(
        id: RevocationRegistryDefinitionId,
        delayMs: Long,
        retryTimes: Int
    ): RevocationRegistryDefinition? {
        repeat(retryTimes) {
            try {
                val request = Ledger.buildGetRevocRegDefRequest(did, id.toString()).get()
                val response = Ledger.submitRequest(pool, request).get()
                val revRegDefJson = Ledger.parseGetRevocRegDefResponse(response).get().objectJson

                return SerializationUtils.jSONToAny<RevocationRegistryDefinition>(revRegDefJson)
            } catch (e: Exception) {
                logger.debug { "Revocation registry definition retrieving failed (id: $id). Retry attempt $it" }
                sleep(delayMs * it)
            }
        }

        return null
    }

    override fun retrieveRevocationRegistryEntry(
        id: RevocationRegistryDefinitionId,
        timestamp: Long,
        delayMs: Long,
        retryTimes: Int
    ): Pair<Long, RevocationRegistryEntry>? {
        repeat(retryTimes) {
            try {
                val request = Ledger.buildGetRevocRegRequest(did, id.toString(), timestamp).get()
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

        return null
    }

    override fun retrieveRevocationRegistryDelta(
        id: RevocationRegistryDefinitionId,
        interval: Interval,
        delayMs: Long,
        retryTimes: Int
    ): Pair<Long, RevocationRegistryEntry>? {
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

        return null
    }

    override fun retrieveDataUsedInProof(
        proofRequest: ProofRequest,
        proof: ProofInfo,
        delayMs: Long,
        retryTimes: Int
    ): DataUsedInProofJson {
        val usedSchemas = proof.proofData.identifiers
            .map { it.getSchemaIdObject() }
            .distinct()
            .map {
                retrieveSchema(it, delayMs, retryTimes)
                    ?: throw RuntimeException("Schema $it doesn't exist in ledger")
            }
            .associate { it.id to it }
        val usedSchemasJson = SerializationUtils.anyToJSON(usedSchemas)

        val usedCredentialDefs = proof.proofData.identifiers
            .map { it.getCredentialDefinitionIdObject() }
            .distinct()
            .map {
                retrieveCredentialDefinition(it, delayMs, retryTimes)
                    ?: throw RuntimeException("Credential definition $it doesn't exist in ledger")
            }
            .associate { it.id to it }
        val usedCredentialDefsJson = SerializationUtils.anyToJSON(usedCredentialDefs)

        val (revRegDefsJson, revRegDeltasJson) = if (proofRequest.nonRevoked != null) {
            val revRegDefs = proof.proofData.identifiers
                .map { it.getRevocationRegistryIdObject()!! }
                .distinct()
                .map {
                    retrieveRevocationRegistryDefinition(it, delayMs, retryTimes)
                        ?: throw RuntimeException("Revocation registry definition $it doesn't exist in ledger")
                }
                .associate { it.id to it }

            val revRegDeltas = mutableMapOf<RevocationRegistryDefinitionId, MutableMap<Long, RevocationRegistryEntry>>()
            proof.proofData.identifiers
                .map { Pair(it.getRevocationRegistryIdObject()!!, it.timestamp!!) }
                .distinct()
                .forEach { (revRegId, timestamp) ->
                    val response =
                        retrieveRevocationRegistryDelta(revRegId, proofRequest.nonRevoked!!, delayMs, retryTimes)
                        ?: throw RuntimeException("Revocation registry for definition $revRegId at timestamp $timestamp doesn't exist in ledger")

                    val (_, revReg) = response
                    val map = revRegDeltas.getOrDefault(revRegId, mutableMapOf())
                    map.putIfAbsent(timestamp, revReg)?.also { current ->
                        if (current != revReg)
                            throw RuntimeException("Collusion of revocation states, this should not happen. At key($revReg) was:($current), tried to put($revReg)")
                    }
                    revRegDeltas[revRegId] = map
                }

            Pair(SerializationUtils.anyToJSON(revRegDefs), SerializationUtils.anyToJSON(revRegDeltas))
        } else Pair("{}", "{}")

        return DataUsedInProofJson(usedSchemasJson, usedCredentialDefsJson, revRegDefsJson, revRegDeltasJson)
    }
}
