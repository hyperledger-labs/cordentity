package com.luxoft.blockchainlab.hyperledger.indy.wallet

import com.luxoft.blockchainlab.hyperledger.indy.helpers.TailsHelper
import com.luxoft.blockchainlab.hyperledger.indy.models.*
import com.luxoft.blockchainlab.hyperledger.indy.utils.PaginatedIterator
import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import com.luxoft.blockchainlab.hyperledger.indy.utils.getRootCause
import mu.KotlinLogging
import org.hyperledger.indy.sdk.anoncreds.Anoncreds
import org.hyperledger.indy.sdk.anoncreds.CredentialsSearch
import org.hyperledger.indy.sdk.anoncreds.CredentialsSearchForProofReq
import org.hyperledger.indy.sdk.anoncreds.DuplicateMasterSecretNameException
import org.hyperledger.indy.sdk.did.Did
import org.hyperledger.indy.sdk.ledger.Ledger
import org.hyperledger.indy.sdk.pairwise.Pairwise
import org.hyperledger.indy.sdk.wallet.Wallet
import org.hyperledger.indy.sdk.wallet.WalletItemNotFoundException
import org.json.JSONObject
import java.util.concurrent.ExecutionException

/**
 * This is an implementation of [WalletUser] which uses indy sdk standard [org.hyperledger.indy.sdk.wallet.Wallet]
 *  and [org.hyperledger.indy.sdk.anoncreds.Anoncreds] API
 *
 * @param wallet [Wallet] - user's wallet
 * @param existingDid [String] - what existing did we should use to perform operations, if null then new will be created
 * @param newDidConfig [DidConfig] - what new did we should create and use to perform operations, if null then we should not create
 * @param tailsPath [String] - path to the directory with tails files (they will be generated when revocation-stuff is done)
 */
class IndySDKWalletUser private constructor(
        val wallet: Wallet,
        existingDid: String?,
        newDidConfig: DidConfig?,
        private val tailsPath: String
) : WalletUser {
    constructor(wallet: Wallet, existingDid: String, tailsPath: String = DEFAULT_TAILS_PATH) : this(wallet, existingDid, null, tailsPath)
    constructor(wallet: Wallet, newDidConfig: DidConfig = DidConfig(), tailsPath: String = DEFAULT_TAILS_PATH) : this(wallet, null, newDidConfig, tailsPath)

    val did: String
    val verkey: String
    private val logger = KotlinLogging.logger {}

    companion object {
        val DEFAULT_TAILS_PATH = "tails"
        val SIGNATURE_TYPE = "CL"
        val REVOCATION_REGISTRY_TYPE = "CL_ACCUM"
        val TAG = "TAG_1"
        val REVOCATION_TAG = "REV_TAG_1"
        val ISSUANCE_ON_DEMAND = "ISSUANCE_ON_DEMAND"
        val EMPTY_OBJECT = "{}"
    }

    init {
        if (existingDid != null) {
            verkey = Did.keyForLocalDid(wallet, existingDid).get()
            did = existingDid
        } else {
            val didResult = Did.createAndStoreMyDid(wallet, SerializationUtils.anyToJSON(newDidConfig)).get()
            did = didResult.did
            verkey = didResult.verkey
        }
    }

    override fun sign(data: String): String {
        return Ledger.signRequest(wallet, did, data).get()
    }

    override fun createRevocationState(
        revocationRegistryDefinition: RevocationRegistryDefinition,
        revocationRegistryEntry: RevocationRegistryEntry,
        credentialRevocationId: String,
        timestamp: Long
    ): RevocationState {
        val tailsReaderHandle = TailsHelper.getTailsHandler(tailsPath).reader.blobStorageReaderHandle

        val revRegDefJson = SerializationUtils.anyToJSON(revocationRegistryDefinition)
        val revRegDeltaJson = SerializationUtils.anyToJSON(revocationRegistryEntry)

        val revStateJson = Anoncreds.createRevocationState(
            tailsReaderHandle,
            revRegDefJson,
            revRegDeltaJson,
            timestamp,
            credentialRevocationId
        ).get()

        val revocationState = SerializationUtils.jSONToAny<RevocationState>(revStateJson)
        revocationState.revocationRegistryIdRaw = revocationRegistryDefinition.revocationRegistryIdRaw

        return revocationState
    }

    override fun createMasterSecret(id: String) {
        try {
            Anoncreds.proverCreateMasterSecret(wallet, id).get()
        } catch (e: ExecutionException) {
            if (getRootCause(e) !is DuplicateMasterSecretNameException) throw e

            logger.debug("MasterSecret already exists, who cares, continuing")
        }
    }

    override fun createSchema(name: String, version: String, attributes: List<String>): Schema {
        val attributesJson = SerializationUtils.anyToJSON(attributes)
        val schemaInfo = Anoncreds.issuerCreateSchema(did, name, version, attributesJson).get()

        return SerializationUtils.jSONToAny(schemaInfo.schemaJson)
    }

    override fun createCredentialDefinition(schema: Schema, enableRevocation: Boolean): CredentialDefinition {
        val schemaJson = SerializationUtils.anyToJSON(schema)
        val credDefConfigJson = SerializationUtils.anyToJSON(CredentialDefinitionConfig(enableRevocation))

        val credDefInfo = Anoncreds.issuerCreateAndStoreCredentialDef(
            wallet, did, schemaJson, TAG, SIGNATURE_TYPE, credDefConfigJson
        ).get()

        return SerializationUtils.jSONToAny(credDefInfo.credDefJson)
    }

    override fun createRevocationRegistry(
        credentialDefinitionId: CredentialDefinitionId,
        maxCredentialNumber: Int
    ): RevocationRegistryInfo {
        val revRegDefConfig = RevocationRegistryConfig(ISSUANCE_ON_DEMAND, maxCredentialNumber)
        val revRegDefConfigJson = SerializationUtils.anyToJSON(revRegDefConfig)
        val tailsWriter = TailsHelper.getTailsHandler(tailsPath).writer

        val createRevRegResult =
            Anoncreds.issuerCreateAndStoreRevocReg(
                wallet,
                did,
                REVOCATION_REGISTRY_TYPE,
                REVOCATION_TAG,
                credentialDefinitionId.toString(),
                revRegDefConfigJson,
                tailsWriter
            ).get()

        val definition = SerializationUtils.jSONToAny<RevocationRegistryDefinition>(createRevRegResult.revRegDefJson)
        val entry = SerializationUtils.jSONToAny<RevocationRegistryEntry>(createRevRegResult.revRegEntryJson)

        return RevocationRegistryInfo(definition, entry)
    }

    override fun createCredentialOffer(credentialDefinitionId: CredentialDefinitionId): CredentialOffer {
        val credOfferJson = Anoncreds.issuerCreateCredentialOffer(wallet, credentialDefinitionId.toString()).get()

        return SerializationUtils.jSONToAny(credOfferJson)
    }

    override fun createCredentialRequest(
        proverDid: String,
        credentialDefinition: CredentialDefinition,
        offer: CredentialOffer,
        masterSecretId: String
    ): CredentialRequestInfo {
        val credentialOfferJson = SerializationUtils.anyToJSON(offer)
        val credDefJson = SerializationUtils.anyToJSON(credentialDefinition)

        val credReq = Anoncreds.proverCreateCredentialReq(
            wallet, proverDid, credentialOfferJson, credDefJson, masterSecretId
        ).get()

        val credentialRequest = SerializationUtils.jSONToAny<CredentialRequest>(credReq.credentialRequestJson)
        val credentialRequestMetadata =
            SerializationUtils.jSONToAny<CredentialRequestMetadata>(credReq.credentialRequestMetadataJson)

        return CredentialRequestInfo(credentialRequest, credentialRequestMetadata)
    }

    override fun issueCredential(
        credentialRequest: CredentialRequestInfo,
        proposal: String,
        offer: CredentialOffer,
        revocationRegistryId: RevocationRegistryDefinitionId?
    ): CredentialInfo {
        val credentialRequestJson = SerializationUtils.anyToJSON(credentialRequest.request)
        val credentialOfferJson = SerializationUtils.anyToJSON(offer)
        val tailsReaderHandle = TailsHelper.getTailsHandler(tailsPath).reader.blobStorageReaderHandle

        val createCredentialResult = Anoncreds.issuerCreateCredential(
            wallet,
            credentialOfferJson,
            credentialRequestJson,
            proposal,
            revocationRegistryId?.toString(),
            tailsReaderHandle
        ).get()

        val credential = SerializationUtils.jSONToAny<Credential>(createCredentialResult.credentialJson)

        return CredentialInfo(credential, createCredentialResult.revocId, createCredentialResult.revocRegDeltaJson)
    }

    override fun receiveCredential(
        credentialInfo: CredentialInfo,
        credentialRequest: CredentialRequestInfo,
        offer: CredentialOffer,
        credentialDefinition: CredentialDefinition,
        revocationRegistryDefinition: RevocationRegistryDefinition?
    ): String {
        val credentialJson = SerializationUtils.anyToJSON(credentialInfo.credential)
        val credentialRequestMetadataJson = SerializationUtils.anyToJSON(credentialRequest.metadata)
        val credDefJson = SerializationUtils.anyToJSON(credentialDefinition)
        val revRegDefJson = if (revocationRegistryDefinition == null) null
        else SerializationUtils.anyToJSON(revocationRegistryDefinition)

        return Anoncreds.proverStoreCredential(
            wallet, null, credentialRequestMetadataJson, credentialJson, credDefJson, revRegDefJson
        ).get()
    }

    override fun revokeCredential(
        revocationRegistryId: RevocationRegistryDefinitionId,
        credentialRevocationId: String
    ): RevocationRegistryEntry {
        val tailsReaderHandle = TailsHelper.getTailsHandler(tailsPath).reader.blobStorageReaderHandle
        val revRegDeltaJson = Anoncreds.issuerRevokeCredential(
            wallet,
            tailsReaderHandle,
            revocationRegistryId.toString(),
            credentialRevocationId
        ).get()

        return SerializationUtils.jSONToAny(revRegDeltaJson)
    }

    private data class ProofDataEntry(
        val schemaId: SchemaId,
        val credDefId: CredentialDefinitionId,
        val referentCredentials: List<ReferentCredential>,
        val revState: RevocationState?
    )

    override fun createProof(
        proofRequest: ProofRequest,
        provideSchema: SchemaProvider,
        provideCredentialDefinition: CredentialDefinitionProvider,
        masterSecretId: String,
        extraQuery: Map<String, Map<String, Any>>?,
        revocationStateProvider: RevocationStateProvider?
    ): ProofInfo {
        val proofRequestJson = SerializationUtils.anyToJSON(proofRequest)
        val extraQueryJson = if (extraQuery == null) null else SerializationUtils.anyToJSON(extraQuery)

        val searchObj = CredentialsSearchForProofReq.open(wallet, proofRequestJson, extraQueryJson).get()

        val allSchemaIds = mutableListOf<SchemaId>()
        val allCredentialDefinitionIds = mutableListOf<CredentialDefinitionId>()
        val allRevStates = mutableSetOf<RevocationState>()

        val requestedAttributes = proofRequest.requestedAttributes.keys.associate { key ->
            val credential = SerializationUtils.jSONToAny<Array<CredentialForTheRequest>>(
                searchObj.fetchNextCredentials(key, 1).get()
            )
                .firstOrNull()
                ?: throw RuntimeException("Unable to find attribute $key that satisfies proof request: ${proofRequest.requestedAttributes[key]}")

            allSchemaIds.add(SchemaId.fromString(credential.credInfo.schemaId))
            allCredentialDefinitionIds.add(CredentialDefinitionId.fromString(credential.credInfo.credDefId))

            val revStateAlreadyDone = allRevStates.any { it.revocationRegistryIdRaw == credential.credInfo.revRegId }

            if ((credential.credInfo.credRevId == null) xor (proofRequest.nonRevoked == null))
                throw RuntimeException("If credential is issued using some revocation registry, it should be proved to be non-revoked")

            // if everything is ok and rev state is needed - pull it from ledger
            val requestedAttributeInfo = if (
                credential.credInfo.credRevId != null &&
                credential.credInfo.revRegId != null &&
                revocationStateProvider != null &&
                proofRequest.nonRevoked != null
            ) {
                val revocationState = revocationStateProvider(
                    RevocationRegistryDefinitionId.fromString(credential.credInfo.revRegId),
                    credential.credInfo.credRevId,
                    proofRequest.nonRevoked!!,
                    revStateAlreadyDone
                )

                allRevStates.add(revocationState)

                RequestedAttributeInfo(
                    credential.credInfo.referent,
                    timestamp = revocationState.timestamp
                )
            } else { // else just give up
                RequestedAttributeInfo(credential.credInfo.referent)
            }

            key to requestedAttributeInfo
        }

        val requestedPredicates = proofRequest.requestedPredicates.keys.associate { key ->
            val credential = SerializationUtils.jSONToAny<Array<CredentialForTheRequest>>(
                searchObj.fetchNextCredentials(key, 1).get()
            )
                .firstOrNull()
                ?: throw RuntimeException("Unable to find attribute $key that satisfies proof request: ${proofRequest.requestedPredicates[key]}")

            allSchemaIds.add(SchemaId.fromString(credential.credInfo.schemaId))
            allCredentialDefinitionIds.add(CredentialDefinitionId.fromString(credential.credInfo.credDefId))

            val revStateAlreadyDone = allRevStates.any { it.revocationRegistryIdRaw == credential.credInfo.revRegId }

            // if everything is ok and rev state is needed - pull it from ledger
            val requestedPredicateInfo = if (
                credential.credInfo.credRevId != null &&
                credential.credInfo.revRegId != null &&
                revocationStateProvider != null &&
                proofRequest.nonRevoked != null
            ) {
                val revocationState = revocationStateProvider(
                    RevocationRegistryDefinitionId.fromString(credential.credInfo.revRegId),
                    credential.credInfo.credRevId,
                    proofRequest.nonRevoked!!,
                    revStateAlreadyDone
                )

                allRevStates.add(revocationState)

                RequestedPredicateInfo(
                    credential.credInfo.referent,
                    revocationState.timestamp
                )
            } else { // else just give up
                RequestedPredicateInfo(credential.credInfo.referent, null)
            }

            key to requestedPredicateInfo
        }

        searchObj.closeSearch().get()

        val requestedCredentials = RequestedCredentials(requestedAttributes, requestedPredicates)

        val allSchemas = allSchemaIds.distinct().map { provideSchema(it) }
        val allCredentialDefs = allCredentialDefinitionIds.distinct().map { provideCredentialDefinition(it) }

        val usedSchemas = allSchemas.associate { it.id to it }
        val usedCredentialDefs = allCredentialDefs.associate { it.id to it }
        val usedRevocationStates = mutableMapOf<String, MutableMap<Long, RevocationState>>()
        allRevStates.forEach {
            val key = it.revocationRegistryIdRaw!!
            val stateByTimestamp =
                usedRevocationStates.getOrDefault(key, mutableMapOf())
            stateByTimestamp.putIfAbsent(it.timestamp, it)
                ?.also { current ->
                    if (current != it)
                        throw RuntimeException("Collusion of revocation states, this should not happen. At key($key) was:($current), tried to put($it)")
                }

            usedRevocationStates[key] = stateByTimestamp
        }

        val requestedCredentialsJson = SerializationUtils.anyToJSON(requestedCredentials)
        val usedSchemasJson = SerializationUtils.anyToJSON(usedSchemas)
        val usedCredentialDefsJson = SerializationUtils.anyToJSON(usedCredentialDefs)
        val usedRevStatesJson = SerializationUtils.anyToJSON(usedRevocationStates)

        val proverProof = Anoncreds.proverCreateProof(
            wallet,
            proofRequestJson,
            requestedCredentialsJson,
            masterSecretId,
            usedSchemasJson,
            usedCredentialDefsJson,
            usedRevStatesJson
        ).get()

        val proof = SerializationUtils.jSONToAny<ParsedProof>(proverProof)

        return ProofInfo(proof)
    }

    override fun verifyProof(proofReq: ProofRequest, proof: ProofInfo, usedData: DataUsedInProofJson): Boolean {
        val proofRequestJson = SerializationUtils.anyToJSON(proofReq)
        val proofJson = SerializationUtils.anyToJSON(proof.proofData)

        return Anoncreds.verifierVerifyProof(
            proofRequestJson,
            proofJson,
            usedData.schemas,
            usedData.credentialDefinitions,
            usedData.revocationRegistryDefinitions,
            usedData.revocationRegistries
        ).get()
    }

    override fun createSessionDid(identityRecord: IdentityDetails): String {
        if (!Pairwise.isPairwiseExists(wallet, identityRecord.did).get()) {
            Did.storeTheirDid(wallet, SerializationUtils.anyToJSON(identityRecord)).get()
            val sessionDid = Did.createAndStoreMyDid(wallet, EMPTY_OBJECT).get().did
            Pairwise.createPairwise(wallet, identityRecord.did, sessionDid, "").get()
        }

        val pairwiseJson = Pairwise.getPairwise(wallet, identityRecord.did).get()
        val pairwise = SerializationUtils.jSONToAny<ParsedPairwise>(pairwiseJson)

        return pairwise.myDid
    }

    override fun addKnownIdentities(identityDetails: IdentityDetails) {
        //TODO: think of how to get rid of exception handling, may be a problem for android
        val verkey = Did.keyForLocalDid(wallet, identityDetails.did).exceptionally {
            if (it is WalletItemNotFoundException) {
                logger.debug(it) { "Did(${identityDetails.did} was not found in wallet" }
                null
            } else throw it
        }.get()
        if (verkey == null)
            Did.storeTheirDid(wallet, SerializationUtils.anyToJSON(identityDetails)).get()
        else {
            if (verkey != identityDetails.verkey)
                throw RuntimeException("Identity(${identityDetails.did},${identityDetails.verkey}) found but old verkey($verkey) is different")
            logger.info { "Identity(${identityDetails.did},${identityDetails.verkey}) is already known" }
        }
    }

    override fun getIdentityDetails(): IdentityDetails {
        return IdentityDetails(did, verkey, null, null)
    }

    override fun getIdentityDetails(did: String): IdentityDetails {
        return IdentityDetails(did, Did.keyForLocalDid(wallet, did).get(), null, null)
    }

    override fun getTailsPath(): String {
        return tailsPath
    }

    override fun getCredentials(): Iterator<CredentialReference> {
        val credentialsSearch = CredentialsSearch.open(wallet, JSONObject().toString()).get()

        return PaginatedIterator(10) {
            SerializationUtils.jSONToAny<List<RawJsonMap>>(credentialsSearch.fetchNextCredentials(it).get())
                    .map { SerializationUtils.convertValue<CredentialReference>((it)) }
        }
    }
}

fun Wallet.getOwnIdentities(): List<IdentityDetails> = SerializationUtils.jSONToAny<IdentityDetailsList>(Did.getListMyDidsWithMeta(this).get())
