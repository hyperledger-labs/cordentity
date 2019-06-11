package com.luxoft.blockchainlab.hyperledger.indy.wallet

import com.luxoft.blockchainlab.hyperledger.indy.helpers.TailsHelper
import com.luxoft.blockchainlab.hyperledger.indy.models.*
import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import com.luxoft.blockchainlab.hyperledger.indy.utils.getRootCause
import org.hyperledger.indy.sdk.anoncreds.Anoncreds
import org.hyperledger.indy.sdk.anoncreds.CredentialsSearchForProofReq
import org.hyperledger.indy.sdk.anoncreds.DuplicateMasterSecretNameException
import org.hyperledger.indy.sdk.did.Did
import org.hyperledger.indy.sdk.ledger.Ledger
import org.hyperledger.indy.sdk.pairwise.Pairwise
import org.hyperledger.indy.sdk.wallet.Wallet
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutionException

/**
 * This is an implementation of [WalletUser] which uses indy sdk standard [org.hyperledger.indy.sdk.wallet.Wallet]
 *  and [org.hyperledger.indy.sdk.anoncreds.Anoncreds] API
 *
 * @param wallet [Wallet] - user's wallet
 * @param didConfig [DidConfig] - what did we should use to perform operations
 * @param tailsPath [String] - path to the directory with tails files (they will be generated when revocation-stuff is done)
 */
class IndySDKWalletUser(
    val wallet: Wallet,
    didConfig: DidConfig = DidConfig(),
    val tailsPath: String = "tails"
) : WalletUser {

    override val did: String
    override val verkey: String
    private val logger = LoggerFactory.getLogger(IndySDKWalletUser::class.java)

    companion object {
        val SIGNATURE_TYPE = "CL"
        val REVOCATION_REGISTRY_TYPE = "CL_ACCUM"
        val TAG = "TAG_1"
        val REVOCATION_TAG = "REV_TAG_1"
        val ISSUANCE_ON_DEMAND = "ISSUANCE_ON_DEMAND"
        val EMPTY_OBJECT = "{}"
    }

    init {
        val didResult = Did.createAndStoreMyDid(wallet, SerializationUtils.anyToJSON(didConfig)).get()
        did = didResult.did
        verkey = didResult.verkey
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
        extraQuery: String?,
        provideSchema: SchemaProvider,
        provideCredentialDefinition: CredentialDefinitionProvider,
        masterSecretId: String,
        revocationStateProvider: RevocationStateProvider?
    ): ProofInfo {
        val proofRequestJson = SerializationUtils.anyToJSON(proofRequest)
        val searchObj = CredentialsSearchForProofReq.open(wallet, proofRequestJson, extraQuery).get()

        val allSchemaIds = mutableListOf<SchemaId>()
        val allCredentialDefinitionIds = mutableListOf<CredentialDefinitionId>()
        val allRevStates = mutableListOf<RevocationState>()

        val requestedAttributes = proofRequest.requestedAttributes.keys.associate { key ->
            val credential = SerializationUtils.jSONToAny<Array<CredentialForTheRequest>>(
                searchObj.fetchNextCredentials(key, 1).get()
            )
                .firstOrNull()
                ?: throw RuntimeException("Unable to find attribute $key that satisfies proof request: ${proofRequest.requestedAttributes[key]}")

            allSchemaIds.add(SchemaId.fromString(credential.credInfo.schemaId))
            allCredentialDefinitionIds.add(CredentialDefinitionId.fromString(credential.credInfo.credDefId))

            val revStateAlreadyDone = allRevStates.find { it.revocationRegistryIdRaw == credential.credInfo.revRegId }

            // if we already pulled this rev state from ledger - just use it
            if (revStateAlreadyDone != null)
                return@associate key to RequestedAttributeInfo(
                    credential.credInfo.referent,
                    timestamp = revStateAlreadyDone.timestamp
                )

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
                    proofRequest.nonRevoked!!
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

            val revStateAlreadyDone = allRevStates.find { it.revocationRegistryIdRaw == credential.credInfo.revRegId }

            // if we already pulled this rev state from ledger - just use it
            if (revStateAlreadyDone != null)
                return@associate key to RequestedPredicateInfo(
                    credential.credInfo.referent,
                    revStateAlreadyDone.timestamp
                )

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
                    proofRequest.nonRevoked!!
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
        val usedRevocationStates = allRevStates
            .associate {
                val stateByTimestamp = hashMapOf<Long, RevocationState>()
                stateByTimestamp[it.timestamp] = it

                it.revocationRegistryIdRaw!! to stateByTimestamp
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
        Did.storeTheirDid(wallet, SerializationUtils.anyToJSON(identityDetails)).get()
    }

    override fun getIdentityDetails(): IdentityDetails {
        return IdentityDetails(did, verkey, null, null)
    }

    override fun getIdentityDetails(did: String): IdentityDetails {
        return IdentityDetails(did, Did.keyForLocalDid(wallet, did).get(), null, null)
    }
}
