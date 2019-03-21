package com.luxoft.blockchainlab.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.helpers.TailsHelper
import com.luxoft.blockchainlab.hyperledger.indy.models.*
import com.luxoft.blockchainlab.hyperledger.indy.roles.IndyIssuer
import com.luxoft.blockchainlab.hyperledger.indy.roles.IndyProver
import com.luxoft.blockchainlab.hyperledger.indy.roles.IndyTrustee
import com.luxoft.blockchainlab.hyperledger.indy.roles.IndyVerifier
import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import com.luxoft.blockchainlab.hyperledger.indy.utils.getRootCause
import mu.KotlinLogging
import org.hyperledger.indy.sdk.anoncreds.Anoncreds
import org.hyperledger.indy.sdk.anoncreds.DuplicateMasterSecretNameException
import org.hyperledger.indy.sdk.did.Did
import org.hyperledger.indy.sdk.pairwise.Pairwise
import org.hyperledger.indy.sdk.pool.Pool
import org.hyperledger.indy.sdk.wallet.Wallet
import org.hyperledger.indy.sdk.wallet.WalletItemNotFoundException
import java.util.concurrent.ExecutionException


/**
 * The central class that encapsulates Indy SDK calls and keeps the corresponding state.
 *
 * Create one instance per each server node that deals with Indy Ledger.
 */
open class IndyUser(
    val pool: Pool,
    val wallet: Wallet,
    did: String?,
    didConfig: String = EMPTY_OBJECT,
    val tailsPath: String = "tails",
    val masterSecretId: String = "main"
) : IndyIssuer, IndyProver, IndyTrustee {

    private val logger = KotlinLogging.logger {}

    override val did: String
    val verkey: String
    val ledgerService: LedgerService

    init {
        var newDid: String
        var newVerkey: String
        if (did != null) {
            try {
                newDid = did
                newVerkey = Did.keyForLocalDid(wallet, did).get()

            } catch (ex: ExecutionException) {
                if (getRootCause(ex) !is WalletItemNotFoundException) throw ex

                val didResult = Did.createAndStoreMyDid(wallet, didConfig).get()
                newDid = didResult.did
                newVerkey = didResult.verkey
            }
        } else {
            val didResult = Did.createAndStoreMyDid(wallet, didConfig).get()
            newDid = didResult.did
            newVerkey = didResult.verkey
        }
        this.did = newDid
        this.verkey = newVerkey

        ledgerService = LedgerService(this.did, this.wallet, this.pool)
        createMasterSecret(masterSecretId)
    }

    override fun getIdentity(did: String): IdentityDetails {
        return IdentityDetails(did, Did.keyForDid(pool, wallet, did).get(), null, null)
    }

    override fun addKnownIdentities(identityDetails: IdentityDetails) {
        Did.storeTheirDid(wallet, SerializationUtils.anyToJSON(identityDetails)).get()
    }

    override fun setPermissionsFor(identityDetails: IdentityDetails) {
        addKnownIdentities(identityDetails)
        ledgerService.addNym(identityDetails)
    }

    override fun createSessionDid(identityRecord: IdentityDetails): String {
        if (!Pairwise.isPairwiseExists(wallet, identityRecord.did).get()) {
            addKnownIdentities(identityRecord)
            val sessionDid = Did.createAndStoreMyDid(wallet, EMPTY_OBJECT).get().did
            Pairwise.createPairwise(wallet, identityRecord.did, sessionDid, "").get()
        }

        val pairwiseJson = Pairwise.getPairwise(wallet, identityRecord.did).get()
        val pairwise = SerializationUtils.jSONToAny<ParsedPairwise>(pairwiseJson)

        return pairwise.myDid
    }

    override fun createSchema(name: String, version: String, attributes: List<String>): Schema {
        val attributesJson = SerializationUtils.anyToJSON(attributes)

        val schemaId = SchemaId(did, name, version)
        val schemaFromLedger = ledgerService.retrieveSchema(schemaId)

        return if (schemaFromLedger == null) {
            val schemaInfo = Anoncreds.issuerCreateSchema(did, name, version, attributesJson).get()

            val schema = SerializationUtils.jSONToAny<Schema>(schemaInfo.schemaJson)

            ledgerService.storeSchema(schema)

            schema
        } else schemaFromLedger
    }

    override fun createCredentialDefinition(schemaId: SchemaId, enableRevocation: Boolean): CredentialDefinition {
        val schema = ledgerService.retrieveSchema(schemaId)
            ?: throw IndySchemaNotFoundException(schemaId, "Create credential definition has been failed")

        val schemaJson = SerializationUtils.anyToJSON(schema)

        val credDefConfigJson = SerializationUtils.anyToJSON(CredentialDefinitionConfig(enableRevocation))

        val credDefId = CredentialDefinitionId(did, schema.seqNo!!, TAG)
        val credDefFromLedger = ledgerService.retrieveCredentialDefinition(credDefId)

        return if (credDefFromLedger == null) {
            val credDefInfo = Anoncreds.issuerCreateAndStoreCredentialDef(
                wallet, did, schemaJson, TAG, SIGNATURE_TYPE, credDefConfigJson
            ).get()

            val credDef = SerializationUtils.jSONToAny<CredentialDefinition>(credDefInfo.credDefJson)

            ledgerService.storeCredentialDefinition(credDef)

            credDef
        } else credDefFromLedger
    }

    override fun createRevocationRegistry(
        credentialDefinitionId: CredentialDefinitionId,
        maxCredentialNumber: Int
    ): RevocationRegistryInfo {
        val revRegDefConfig = RevocationRegistryConfig(ISSUANCE_ON_DEMAND, maxCredentialNumber)
        val revRegDefConfigJson = SerializationUtils.anyToJSON(revRegDefConfig)
        val tailsWriter = TailsHelper.getTailsHandler(tailsPath).writer

        val revRegId = credentialDefinitionId.getPossibleRevocationRegistryDefinitionId(REVOCATION_TAG)
        val definitionFromLedger = ledgerService.retrieveRevocationRegistryDefinition(revRegId)

        if (definitionFromLedger == null) {
            val createRevRegResult =
                Anoncreds.issuerCreateAndStoreRevocReg(
                    wallet,
                    did,
                    null,
                    REVOCATION_TAG,
                    credentialDefinitionId.toString(),
                    revRegDefConfigJson,
                    tailsWriter
                ).get()

            val definition =
                SerializationUtils.jSONToAny<RevocationRegistryDefinition>(createRevRegResult.revRegDefJson)
            val entry = SerializationUtils.jSONToAny<RevocationRegistryEntry>(createRevRegResult.revRegEntryJson)

            ledgerService.storeRevocationRegistryDefinition(definition)
            ledgerService.storeRevocationRegistryEntry(
                entry,
                definition.id,
                definition.revocationRegistryDefinitionType
            )

            return RevocationRegistryInfo(definition, entry)
        }

        val entryFromLedger = ledgerService.retrieveRevocationRegistryEntry(revRegId, Timestamp.now())
            ?: throw RuntimeException("Unable to get revocation registry entry of existing definition $revRegId from ledger")

        return RevocationRegistryInfo(definitionFromLedger, entryFromLedger.second)
    }

    /**
     * Creates credential offer
     *
     * @param credentialDefinitionId    credential definition id
     *
     * @return                          created credential offer
     */
    override fun createCredentialOffer(credentialDefinitionId: CredentialDefinitionId): CredentialOffer {
        val credOfferJson = Anoncreds.issuerCreateCredentialOffer(wallet, credentialDefinitionId.toString()).get()

        return SerializationUtils.jSONToAny(credOfferJson)
    }

    override fun createCredentialRequest(proverDid: String, offer: CredentialOffer): CredentialRequestInfo {
        val credDef = ledgerService.retrieveCredentialDefinition(offer.getCredentialDefinitionIdObject())
            ?: throw IndyCredentialDefinitionNotFoundException(
                offer.getCredentialDefinitionIdObject(),
                "Create credential request has been failed"
            )

        val credentialOfferJson = SerializationUtils.anyToJSON(offer)
        val credDefJson = SerializationUtils.anyToJSON(credDef)

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
        offer: CredentialOffer
    ): CredentialInfo {
        val credentialRequestJson = SerializationUtils.anyToJSON(credentialRequest.request)
        val credentialOfferJson = SerializationUtils.anyToJSON(offer)
        val tailsReaderHandle = TailsHelper.getTailsHandler(tailsPath).reader.blobStorageReaderHandle

        var revocationRegistryId: RevocationRegistryDefinitionId? =
            credentialRequest.request.getCredentialDefinitionIdObject().getPossibleRevocationRegistryDefinitionId(REVOCATION_TAG)

        if (ledgerService.retrieveRevocationRegistryDefinition(revocationRegistryId!!) == null)
            revocationRegistryId = null

        val createCredentialResult = Anoncreds.issuerCreateCredential(
            wallet,
            credentialOfferJson,
            credentialRequestJson,
            proposal,
            revocationRegistryId?.toString(),
            tailsReaderHandle
        ).get()

        val credential = SerializationUtils.jSONToAny<Credential>(createCredentialResult.credentialJson)

        if (revocationRegistryId != null) {
            val revocationRegistry = ledgerService.retrieveRevocationRegistryDefinition(revocationRegistryId!!)

            if (revocationRegistry != null) {
                val revocationRegistryDefinition =
                    ledgerService.retrieveRevocationRegistryDefinition(revocationRegistryId)
                        ?: throw IndyRevRegNotFoundException(revocationRegistryId, "Issue credential has been failed")

                val revRegDelta =
                    SerializationUtils.jSONToAny<RevocationRegistryEntry>(createCredentialResult.revocRegDeltaJson)

                ledgerService.storeRevocationRegistryEntry(
                    revRegDelta,
                    revocationRegistryId.toString(),
                    revocationRegistryDefinition.revocationRegistryDefinitionType
                )
            }
        }

        return CredentialInfo(credential, createCredentialResult.revocId, createCredentialResult.revocRegDeltaJson)
    }

    override fun revokeCredential(
        revocationRegistryId: RevocationRegistryDefinitionId,
        credentialRevocationId: String
    ) {
        val tailsReaderHandle = TailsHelper.getTailsHandler(tailsPath).reader.blobStorageReaderHandle
        val revRegDeltaJson = Anoncreds.issuerRevokeCredential(
            wallet,
            tailsReaderHandle,
            revocationRegistryId.toString(),
            credentialRevocationId
        ).get()

        val revRegDelta = SerializationUtils.jSONToAny<RevocationRegistryEntry>(revRegDeltaJson)
        val revRegDef = ledgerService.retrieveRevocationRegistryDefinition(revocationRegistryId)
            ?: throw IndyRevRegNotFoundException(revocationRegistryId, "Revoke credential has been failed")

        ledgerService.storeRevocationRegistryEntry(
            revRegDelta,
            revocationRegistryId.toString(),
            revRegDef.revocationRegistryDefinitionType
        )
    }

    override fun receiveCredential(
        credentialInfo: CredentialInfo,
        credentialRequest: CredentialRequestInfo,
        offer: CredentialOffer
    ) {
        val revRegDefJson = if (credentialInfo.credential.getRevocationRegistryIdObject() != null) {
            val revRegDef =
                ledgerService.retrieveRevocationRegistryDefinition(credentialInfo.credential.getRevocationRegistryIdObject()!!)
                    ?: throw IndyRevRegNotFoundException(
                        credentialInfo.credential.getRevocationRegistryIdObject()!!,
                        "Receive credential has been failed"
                    )

            SerializationUtils.anyToJSON(revRegDef)
        } else null

        val credDef = ledgerService.retrieveCredentialDefinition(offer.getCredentialDefinitionIdObject())
            ?: throw IndyCredentialDefinitionNotFoundException(
                offer.getCredentialDefinitionIdObject(),
                "Receive credential has been failed"
            )

        val credentialJson = SerializationUtils.anyToJSON(credentialInfo.credential)
        val credentialRequestMetadataJson = SerializationUtils.anyToJSON(credentialRequest.metadata)
        val credDefJson = SerializationUtils.anyToJSON(credDef)

        Anoncreds.proverStoreCredential(
            wallet, null, credentialRequestMetadataJson, credentialJson, credDefJson, revRegDefJson
        ).get()
    }

    override fun createProof(proofRequest: ProofRequest): ProofInfo {
        val proofRequestJson = SerializationUtils.anyToJSON(proofRequest)
        val proverGetCredsForProofReq = Anoncreds.proverGetCredentialsForProofReq(wallet, proofRequestJson).get()
        val requiredCredentialsForProof =
            SerializationUtils.jSONToAny<ProofRequestCredentials>(proverGetCredsForProofReq)

        val requiredAttributes = requiredCredentialsForProof.attributes.values.flatten()
        val proofRequestAttributes = proofRequest.requestedAttributes
        val attrProofData = parseProofData(proofRequestAttributes, requiredAttributes, proofRequest.nonRevoked)

        val requiredPredicates = requiredCredentialsForProof.predicates.values.flatten()
        val proofRequestPredicates = proofRequest.requestedPredicates
        val predProofData = parseProofData(proofRequestPredicates, requiredPredicates, proofRequest.nonRevoked)

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
            .map {
                ledgerService.retrieveSchema(it)
                    ?: throw IndySchemaNotFoundException(it, "Create proof has been failed")
            }

        val allCredentialDefs = (attrProofData + predProofData)
            .map { it.credDefId }
            .distinct()
            .map {
                ledgerService.retrieveCredentialDefinition(it)
                    ?: throw IndyCredentialDefinitionNotFoundException(it, "Create proof has been failed")
            }

        val allRevStates = (attrProofData + predProofData)
            .map {
                it.revState
            }

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

    /**
     * Shortcut to [IndyUser.getDataUsedInProof]
     */
    fun getDataUsedInProof(proofRequest: ProofRequest, proof: ProofInfo) =
        IndyUser.getDataUsedInProof(did, pool, proofRequest, proof)

    /**
     * Shortcut to [IndyUser.verifyProof]
     */
    fun verifyProof(proofReq: ProofRequest, proof: ProofInfo, usedData: DataUsedInProofJson) =
        IndyUser.verifyProof(proofReq, proof, usedData)

    private data class ProofDataEntry(
        val schemaId: SchemaId,
        val credDefId: CredentialDefinitionId,
        val referentCredentials: List<ReferentCredential>,
        val revState: RevocationState?
    )

    private fun parseProofData(
        collectionFromRequest: Map<String, AbstractCredentialReference>,
        collectionFromCreds: List<CredentialReferenceInfo>,
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

            if (nonRevoked == null || credRevId == null || revRegId == null) {
                return@map ProofDataEntry(schemaId, credDefId, referentCredentials, null)
            }

            val revState = getRevocationState(credRevId, revRegId, nonRevoked)
            return@map ProofDataEntry(schemaId, credDefId, referentCredentials, revState)
        }

    }

    private fun getRevocationState(
        credRevId: String,
        revRegDefId: RevocationRegistryDefinitionId,
        interval: Interval
    ): RevocationState {
        val tailsReaderHandle = TailsHelper.getTailsHandler(tailsPath).reader.blobStorageReaderHandle

        val revRegDef = ledgerService.retrieveRevocationRegistryDefinition(revRegDefId)
            ?: throw IndyRevRegNotFoundException(revRegDefId, "Get revocation state has been failed")
        val revRegDefJson = SerializationUtils.anyToJSON(revRegDef)

        val response = ledgerService.retrieveRevocationRegistryDelta(revRegDefId, interval)
            ?: throw IndyRevDeltaNotFoundException(revRegDefId, "Interval is $interval")
        val (timestamp, revRegDelta) = response
        val revRegDeltaJson = SerializationUtils.anyToJSON(revRegDelta)

        val revStateJson = Anoncreds.createRevocationState(
            tailsReaderHandle, revRegDefJson, revRegDeltaJson, timestamp, credRevId
        ).get()

        val revState = SerializationUtils.jSONToAny<RevocationState>(revStateJson)
        revState.revocationRegistryIdRaw = revRegDefId.toString()

        return revState
    }

    private fun createMasterSecret(masterSecretId: String) {
        try {
            Anoncreds.proverCreateMasterSecret(wallet, masterSecretId).get()
        } catch (e: ExecutionException) {
            if (getRootCause(e) !is DuplicateMasterSecretNameException) throw e

            logger.debug { "MasterSecret already exists, who cares, continuing" }
        }
    }

    companion object : IndyVerifier {
        const val SIGNATURE_TYPE = "CL"
        const val REVOCATION_REGISTRY_TYPE = "CL_ACCUM"
        const val TAG = "TAG_1"
        const val REVOCATION_TAG = "REV_TAG_1"
        private const val ISSUANCE_ON_DEMAND = "ISSUANCE_ON_DEMAND"
        private const val EMPTY_OBJECT = "{}"

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

        override fun getDataUsedInProof(
            did: String,
            pool: Pool,
            proofRequest: ProofRequest,
            proof: ProofInfo
        ): DataUsedInProofJson {
            val usedSchemas = proof.proofData.identifiers
                .map { it.getSchemaIdObject() }
                .distinct()
                .map {
                    LedgerService.retrieveSchema(did, pool, it)
                        ?: throw RuntimeException("Schema $it doesn't exist in ledger")
                }
                .associate { it.id to it }
            val usedSchemasJson = SerializationUtils.anyToJSON(usedSchemas)

            val usedCredentialDefs = proof.proofData.identifiers
                .map { it.getCredentialDefinitionIdObject() }
                .distinct()
                .map {
                    LedgerService.retrieveCredentialDefinition(did, pool, it)
                        ?: throw RuntimeException("Credential definition $it doesn't exist in ledger")
                }
                .associate { it.id to it }
            val usedCredentialDefsJson = SerializationUtils.anyToJSON(usedCredentialDefs)

            val (revRegDefsJson, revRegDeltasJson) = if (proofRequest.nonRevoked != null) {
                val revRegDefs = proof.proofData.identifiers
                    .map { it.getRevocationRegistryIdObject()!! }
                    .distinct()
                    .map {
                        LedgerService.retrieveRevocationRegistryDefinition(did, pool, it)
                            ?: throw RuntimeException("Revocation registry definition $it doesn't exist in ledger")
                    }
                    .associate { it.id to it }

                val revRegDeltas = proof.proofData.identifiers
                    .map { Pair(it.getRevocationRegistryIdObject()!!, it.timestamp!!) }
                    .distinct()
                    .associate { (revRegId, timestamp) ->
                        val response = LedgerService.retrieveRevocationRegistryEntry(did, pool, revRegId, timestamp)
                            ?: throw RuntimeException("Revocation registry for definition $revRegId at timestamp $timestamp doesn't exist in ledger")

                        val (tmstmp, revReg) = response
                        val map = hashMapOf<Long, RevocationRegistryEntry>()
                        map[tmstmp] = revReg

                        revRegId to map
                    }

                Pair(SerializationUtils.anyToJSON(revRegDefs), SerializationUtils.anyToJSON(revRegDeltas))
            } else Pair(EMPTY_OBJECT, EMPTY_OBJECT)

            return DataUsedInProofJson(usedSchemasJson, usedCredentialDefsJson, revRegDefsJson, revRegDeltasJson)
        }

        override fun createProofRequest(
            version: String,
            name: String,
            attributes: List<CredentialFieldReference>,
            predicates: List<CredentialPredicate>,
            nonRevoked: Interval?
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

            val nonce = "123123123123"

            return ProofRequest(version, name, nonce, requestedAttributes, requestedPredicates, nonRevoked)
        }
    }
}