package com.luxoft.blockchainlab.hyperledger.indy.wallet

import com.luxoft.blockchainlab.hyperledger.indy.helpers.TailsHelper
import com.luxoft.blockchainlab.hyperledger.indy.models.*
import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import com.luxoft.blockchainlab.hyperledger.indy.utils.getRootCause
import org.hyperledger.indy.sdk.anoncreds.Anoncreds
import org.hyperledger.indy.sdk.anoncreds.DuplicateMasterSecretNameException
import org.hyperledger.indy.sdk.did.Did
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

    var did: String
    var verkey: String
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
        this.did = didResult.did
        this.verkey = didResult.verkey
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
    ) {
        val credentialJson = SerializationUtils.anyToJSON(credentialInfo.credential)
        val credentialRequestMetadataJson = SerializationUtils.anyToJSON(credentialRequest.metadata)
        val credDefJson = SerializationUtils.anyToJSON(credentialDefinition)
        val revRegDefJson = if (revocationRegistryDefinition == null) null
        else SerializationUtils.anyToJSON(revocationRegistryDefinition)

        Anoncreds.proverStoreCredential(
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

    override fun createProofRequest(
        version: String,
        name: String,
        attributes: List<CredentialFieldReference>,
        predicates: List<CredentialPredicate>,
        nonRevoked: Interval?,
        nonce: String
    ): ProofRequest {
        val requestedAttributes = attributes
            .withIndex()
            .associate { attr ->
                attr.value.fieldName to CredentialAttributeReference(
                    attr.value.fieldName,
                    attr.value.schemaIdRaw
                )
            }

        val requestedPredicates = predicates
            .withIndex()
            .associate { predicate ->
                predicate.value.fieldReference.fieldName to CredentialPredicateReference(
                    predicate.value.fieldReference.fieldName,
                    predicate.value.type,
                    predicate.value.value,
                    predicate.value.fieldReference.schemaIdRaw
                )
            }

        return ProofRequest(version, name, nonce, requestedAttributes, requestedPredicates, nonRevoked)
    }

    private data class ProofDataEntry(
        val schemaId: SchemaId,
        val credDefId: CredentialDefinitionId,
        val referentCredentials: List<ReferentCredential>,
        val revState: RevocationState?
    )

    private fun parseProofData(
        collectionFromRequest: Map<String, AbstractCredentialReference>,
        collectionFromCreds: List<CredentialReferenceInfo>,
        revocationStateProvider: RevocationStateProvider?,
        nonRevoked: Interval?
    ): List<ProofDataEntry> {

        return collectionFromCreds.map { attribute ->
            val credDefId = attribute.credentialInfo.getCredentialDefinitionIdObject()

            val keys = collectionFromRequest.entries
                .filter { it.value.schemaIdRaw == attribute.credentialInfo.schemaIdRaw }
                .map { it.key }
            val reference = attribute.credentialInfo.referent
            val referentCredentials = keys.map { ReferentCredential(it, reference) }

            val credRevId = attribute.credentialInfo.credentialRevocationId
            val revRegId = attribute.credentialInfo.getRevocationRegistryIdObject()
            val schemaId = attribute.credentialInfo.getSchemaIdObject()

            if (nonRevoked == null || credRevId == null || revRegId == null)
                return@map ProofDataEntry(
                    schemaId,
                    credDefId,
                    referentCredentials,
                    null
                )

            if (revocationStateProvider == null)
                throw RuntimeException("You should provide revocation state")

            return@map ProofDataEntry(
                schemaId,
                credDefId,
                referentCredentials,
                revocationStateProvider(revRegId, credRevId, nonRevoked)
            )
        }
    }

    override fun createProof(
        proofRequest: ProofRequest,
        provideSchema: SchemaProvider,
        provideCredentialDefinition: CredentialDefinitionProvider,
        masterSecretId: String,
        revocationStateProvider: RevocationStateProvider?
    ): ProofInfo {
        val proofRequestJson = SerializationUtils.anyToJSON(proofRequest)
        val proverGetCredsForProofReq = Anoncreds.proverGetCredentialsForProofReq(wallet, proofRequestJson).get()
        val requiredCredentialsForProof =
            SerializationUtils.jSONToAny<ProofRequestCredentials>(proverGetCredsForProofReq)

        val requiredAttributes = requiredCredentialsForProof.attributes.values.flatten()
        val proofRequestAttributes = proofRequest.requestedAttributes
        val attrProofData =
            parseProofData(proofRequestAttributes, requiredAttributes, revocationStateProvider, proofRequest.nonRevoked)

        val requiredPredicates = requiredCredentialsForProof.predicates.values.flatten()
        val proofRequestPredicates = proofRequest.requestedPredicates
        val predProofData =
            parseProofData(proofRequestPredicates, requiredPredicates, revocationStateProvider, proofRequest.nonRevoked)

        val requestedAttributes = mutableMapOf<String, RequestedAttributeInfo>()
        attrProofData
            .forEach { proofData ->
                proofData.referentCredentials.forEach { credential ->
                    requestedAttributes[credential.key] =
                        RequestedAttributeInfo(credential.credentialUUID, true, proofData.revState?.timestamp)
                }
            }

        val requestedPredicates = mutableMapOf<String, RequestedPredicateInfo>()
        predProofData
            .forEach { proofData ->
                proofData.referentCredentials.forEach { credential ->
                    requestedPredicates[credential.key] =
                        RequestedPredicateInfo(credential.credentialUUID, proofData.revState?.timestamp)
                }
            }

        val requestedCredentials = RequestedCredentials(requestedAttributes, requestedPredicates)

        val allSchemas = (attrProofData + predProofData)
            .map { it.schemaId }
            .distinct()
            .map { provideSchema(it) }

        val allCredentialDefs = (attrProofData + predProofData)
            .map { it.credDefId }
            .distinct()
            .map { provideCredentialDefinition(it) }

        val allRevStates = (attrProofData + predProofData)
            .map { it.revState }

        val usedSchemas = allSchemas.associate { it.id to it }
        val usedCredentialDefs = allCredentialDefs.associate { it.id to it }
        val usedRevocationStates = allRevStates
            .filter { it != null }
            .associate {
                val stateByTimestamp = hashMapOf<Long, RevocationState>()
                stateByTimestamp[it!!.timestamp] = it

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
}
