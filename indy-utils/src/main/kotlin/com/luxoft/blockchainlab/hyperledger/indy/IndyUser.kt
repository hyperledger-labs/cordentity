package com.luxoft.blockchainlab.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.roles.IndyIssuer
import com.luxoft.blockchainlab.hyperledger.indy.roles.IndyProver
import com.luxoft.blockchainlab.hyperledger.indy.roles.IndyTrustee
import com.luxoft.blockchainlab.hyperledger.indy.roles.IndyVerifier
import com.luxoft.blockchainlab.hyperledger.indy.utils.EnvironmentUtils.getIndyHomePath
import com.luxoft.blockchainlab.hyperledger.indy.utils.LedgerService
import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import com.luxoft.blockchainlab.hyperledger.indy.utils.getRootCause
import org.hyperledger.indy.sdk.anoncreds.Anoncreds
import org.hyperledger.indy.sdk.anoncreds.DuplicateMasterSecretNameException
import org.hyperledger.indy.sdk.blob_storage.BlobStorageReader
import org.hyperledger.indy.sdk.blob_storage.BlobStorageWriter
import org.hyperledger.indy.sdk.did.Did
import org.hyperledger.indy.sdk.pairwise.Pairwise
import org.hyperledger.indy.sdk.pool.Pool
import org.hyperledger.indy.sdk.wallet.Wallet
import org.hyperledger.indy.sdk.wallet.WalletItemNotFoundException
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutionException


/**
 * The central class that encapsulates Indy SDK calls and keeps the corresponding state.
 *
 * Create one instance per each server node that deals with Indy Ledger.
 */
open class IndyUser : IndyIssuer, IndyProver, IndyTrustee {

    companion object : IndyVerifier {
        const val SIGNATURE_TYPE = "CL"
        const val REVOCATION_REGISTRY_TYPE = "CL_ACCUM"
        const val TAG = "TAG_1"
        const val REVOCATION_TAG = "REV_TAG_1"
        private const val ISSUANCE_ON_DEMAND = "ISSUANCE_ON_DEMAND"
        private const val EMPTY_OBJECT = "{}"

        fun getTailsConfig(tailsPath: String) = """{"base_dir":"$tailsPath","uri_pattern":""}"""
            .replace('\\', '/')

        fun getCredentialDefinitionConfig() = """{"support_revocation":true}"""

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
                .map { it.schemaId }
                .distinct()
                .map {
                    LedgerService.retrieveSchema(did, pool, it)
                        ?: throw RuntimeException("Schema $it doesn't exist in ledger")
                }
                .associate { it.id to it }
            val usedSchemasJson = SerializationUtils.anyToJSON(usedSchemas)

            val usedCredentialDefs = proof.proofData.identifiers
                .map { it.credentialDefinitionId }
                .distinct()
                .map {
                    LedgerService.retrieveCredentialDefinition(did, pool, it)
                        ?: throw RuntimeException("Credential definition $it doesn't exist in ledger")
                }
                .associate { it.id to it }
            val usedCredentialDefsJson = SerializationUtils.anyToJSON(usedCredentialDefs)

            val (revRegDefsJson, revRegDeltasJson) = if (proofRequest.nonRevoked != null) {
                val revRegDefs = proof.proofData.identifiers
                    .map { it.revocationRegistryId!! }
                    .distinct()
                    .map {
                        LedgerService.retrieveRevocationRegistryDefinition(did, pool, it)
                            ?: throw RuntimeException("Revocation registry definition $it doesn't exist in ledger")
                    }
                    .associate { it.id to it }

                val revRegDeltas = proof.proofData.identifiers
                    .map { Pair(it.revocationRegistryId!!, it.timestamp!!) }
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
                        attr.value.schemaId
                    )
                }

            val requestedPredicates = predicates
                .withIndex()
                .associate { predicate ->
                    predicate.value.fieldReference.fieldName to CredentialPredicateReference(
                        predicate.value.fieldReference.fieldName,
                        predicate.value.type,
                        predicate.value.value,
                        predicate.value.fieldReference.schemaId
                    )
                }

            val nonce = "123123123123"

            return ProofRequest(version, name, nonce, requestedAttributes, requestedPredicates, nonRevoked)
        }
    }

    private val logger = LoggerFactory.getLogger(IndyUser::class.java.name)

    @Deprecated("Was used in development purpose")
    val defaultMasterSecretId = "master"
    override val did: String

    val verkey: String

    protected val wallet: Wallet
    protected val pool: Pool
    val tailsPath: String

    private var ledgerService: LedgerService

    constructor(pool: Pool, wallet: Wallet, did: String?, didConfig: String = EMPTY_OBJECT, tailsPath: String) {

        this.tailsPath = tailsPath
        this.pool = pool
        this.wallet = wallet

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
    }

    override fun close() {
        wallet.closeWallet().get()
    }

    override fun getIdentity(did: String): IdentityDetails {
        return IdentityDetails(did, Did.keyForDid(pool, wallet, did).get(), null, null)
    }

    override fun addKnownIdentities(identityDetails: IdentityDetails) {
        Did.storeTheirDid(wallet, identityDetails.getIdentityRecord()).get()
    }

    override fun setPermissionsFor(identityDetails: IdentityDetails) {
        addKnownIdentities(identityDetails)
        ledgerService.addNym(identityDetails)
    }

    override fun createMasterSecret(masterSecretId: String) {
        try {
            Anoncreds.proverCreateMasterSecret(wallet, masterSecretId).get()
        } catch (e: ExecutionException) {
            if (getRootCause(e) !is DuplicateMasterSecretNameException) throw e

            logger.debug("MasterSecret already exists, who cares, continuing")
        }
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
        val attrStr = attributes.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

        val schemaId = SchemaId(did, name, version)
        val schemaFromLedger = ledgerService.retrieveSchema(schemaId.toString())

        return if (schemaFromLedger == null) {
            val schemaInfo = Anoncreds.issuerCreateSchema(did, name, version, attrStr).get()

            val schema = SerializationUtils.jSONToAny<Schema>(schemaInfo.schemaJson)

            ledgerService.storeSchema(schema)

            schema
        } else schemaFromLedger
    }

    override fun createCredentialDefinition(schemaId: SchemaId, enableRevocation: Boolean): CredentialDefinition {
        val schema = ledgerService.retrieveSchema(schemaId.toString())
            ?: throw IndySchemaNotFoundException(schemaId.toString(), "Create credential definition has been failed")
        val schemaJson = SerializationUtils.anyToJSON(schema)

        val credDefConfigJson = if (enableRevocation) getCredentialDefinitionConfig() else EMPTY_OBJECT

        val credDefId = CredentialDefinitionId(did, schema.seqNo!!, TAG)
        val credDefFromLedger = ledgerService.retrieveCredentialDefinition(credDefId.toString())

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
        val tailsWriter = getTailsHandler().writer

        val revRegId = credentialDefinitionId.getRevocationRegistryDefinitionId(REVOCATION_TAG)
        val definitionFromLedger = ledgerService.retrieveRevocationRegistryDefinition(revRegId.toString())

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

        val entryFromLedger = ledgerService.retrieveRevocationRegistryEntry(revRegId.toString(), Timestamp.now())
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

    override fun createCredentialRequest(
        proverDid: String,
        offer: CredentialOffer,
        masterSecretId: String
    ): CredentialRequestInfo {
        val credDef = ledgerService.retrieveCredentialDefinition(offer.credentialDefinitionId)
            ?: throw IndyCredentialDefinitionNotFoundException(
                offer.credentialDefinitionId,
                "Create credential request has been failed"
            )

        val credentialOfferJson = SerializationUtils.anyToJSON(offer)
        val credDefJson = SerializationUtils.anyToJSON(credDef)

        createMasterSecret(masterSecretId)

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
        val tailsReaderHandle = getTailsHandler().reader.blobStorageReaderHandle

        var revocationRegistryId: RevocationRegistryDefinitionId? =
            credentialRequest.request.getCredentialDefinitionId().getRevocationRegistryDefinitionId(REVOCATION_TAG)

        if (ledgerService.retrieveRevocationRegistryDefinition(revocationRegistryId.toString()) == null)
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

        val revocationRegistry = ledgerService.retrieveRevocationRegistryDefinition(revocationRegistryId.toString())

        if (revocationRegistry != null) {
            val revocationRegistryDefinition =
                ledgerService.retrieveRevocationRegistryDefinition(revocationRegistryId.toString())
                    ?: throw IndyRevRegNotFoundException(
                        revocationRegistryId.toString(),
                        "Issue credential has been failed"
                    )

            val revRegDelta =
                SerializationUtils.jSONToAny<RevocationRegistryEntry>(createCredentialResult.revocRegDeltaJson)

            ledgerService.storeRevocationRegistryEntry(
                revRegDelta,
                revocationRegistryId.toString(),
                revocationRegistryDefinition.revocationRegistryDefinitionType
            )
        }

        return CredentialInfo(credential, createCredentialResult.revocId, createCredentialResult.revocRegDeltaJson)
    }

    override fun revokeCredential(
        revocationRegistryId: RevocationRegistryDefinitionId,
        credentialRevocationId: String
    ) {
        val tailsReaderHandle = getTailsHandler().reader.blobStorageReaderHandle
        val revRegDeltaJson =
            Anoncreds.issuerRevokeCredential(
                wallet,
                tailsReaderHandle,
                revocationRegistryId.toString(),
                credentialRevocationId
            )
                .get()
        val revRegDelta = SerializationUtils.jSONToAny<RevocationRegistryEntry>(revRegDeltaJson)
        val revRegDef = ledgerService.retrieveRevocationRegistryDefinition(revocationRegistryId.toString())
            ?: throw IndyRevRegNotFoundException(revocationRegistryId.toString(), "Revoke credential has been failed")

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
        val revRegDefJson = if (credentialInfo.credential.revocationRegistryId != null) {
            val revRegDef =
                ledgerService.retrieveRevocationRegistryDefinition(credentialInfo.credential.revocationRegistryId)
                    ?: throw IndyRevRegNotFoundException(
                        credentialInfo.credential.revocationRegistryId,
                        "Receive credential has been failed"
                    )

            SerializationUtils.anyToJSON(revRegDef)
        } else null

        val credDef = ledgerService.retrieveCredentialDefinition(offer.credentialDefinitionId)
            ?: throw IndyCredentialDefinitionNotFoundException(
                offer.credentialDefinitionId,
                "Receive credential has been failed"
            )

        val credentialJson = SerializationUtils.anyToJSON(credentialInfo.credential)
        val credentialRequestMetadataJson = SerializationUtils.anyToJSON(credentialRequest.metadata)
        val credDefJson = SerializationUtils.anyToJSON(credDef)

        Anoncreds.proverStoreCredential(
            wallet, null, credentialRequestMetadataJson, credentialJson, credDefJson, revRegDefJson
        ).get()
    }

    override fun createProof(proofRequest: ProofRequest, masterSecretId: String): ProofInfo {
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
            .map { it.schemaId.toString() }
            .distinct()
            .map {
                ledgerService.retrieveSchema(it)
                    ?: throw IndySchemaNotFoundException(it, "Create proof has been failed")
            }

        val allCredentialDefs = (attrProofData + predProofData)
            .map { it.credDefId.toString() }
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

                it.revocationRegistryId!! to stateByTimestamp
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

    /**
     * Retrieves schema from ledger
     *
     * @param id                schema id
     *
     * @return                  schema or null if schema doesn't exist in ledger
     */
    fun retrieveSchemaById(id: SchemaId) = ledgerService.retrieveSchema(id.toString())

    /**
     * Retrieves schema from ledger
     *
     * @param name              schema name
     * @param version           schema version
     *
     * @return                  schema or null if schema doesn't exist in ledger
     */
    fun retrieveSchema(name: String, version: String): Schema? {
        val schemaId = SchemaId(did, name, version)
        return retrieveSchemaById(schemaId)
    }

    /**
     * Check if schema exist on ledger
     *
     * @param name              schema name
     * @param version           schema version
     *
     * @return                  true if exist otherwise false
     */
    fun isSchemaExist(name: String, version: String): Boolean {
        return (null != retrieveSchema(name, version))
    }

    /**
     * Retrieves credential definition from ledger by schema Id
     *
     * @param id                schema id
     *
     * @return                  credential definition or null if it doesn't exist in ledger
     */
    fun retrieveCredentialDefinitionBySchemaId(id: SchemaId): CredentialDefinition? {
        val schema = retrieveSchemaById(id)
            ?: throw RuntimeException("Schema is not found in ledger")

        val credentialDefinitionId = CredentialDefinitionId(did, schema.seqNo!!, TAG)
        return ledgerService.retrieveCredentialDefinition(credentialDefinitionId.toString())
    }

    /**
     * Retrieves credential definition from ledger
     *
     * @param id                credential definition id
     *
     * @return                  credential definition or null if it doesn't exist in ledger
     */
    fun retrieveCredentialDefinitionById(id: CredentialDefinitionId) =
        ledgerService.retrieveCredentialDefinition(id.toString())

    /**
     * Check if credential definition exist on ledger
     *
     * @param schemaId          schema id
     *
     * @return                  true if exist otherwise false
     */
    fun isCredentialDefinitionExist(schemaId: SchemaId): Boolean {
        return (retrieveCredentialDefinitionBySchemaId(schemaId) != null)
    }

    /**
     * Retrieves revocation registry entry from ledger
     *
     * @param id                revocation registry definition id
     * @param timestamp         time moment of revocation registry state
     *
     * @return                  revocation registry entry or null if it doesn't exist in ledger
     */
    fun retrieveRevocationRegistryEntry(id: RevocationRegistryDefinitionId, timestamp: Long) =
        ledgerService.retrieveRevocationRegistryEntry(id.toString(), timestamp)

    /**
     * Same as [retrieveRevocationRegistryEntry] but finds any non-revoked state in [interval]
     *
     * @param id                revocation registry definition id
     * @param interval          time interval of credential non-revocation
     *
     * @return                  revocation registry delta or null if it doesn't exist in ledger
     */
    fun retrieveRevocationRegistryDelta(id: RevocationRegistryDefinitionId, interval: Interval) =
        ledgerService.retrieveRevocationRegistryDelta(id.toString(), interval)

    /**
     * Retrieves revocation registry definition from ledger
     *
     * @param id                revocation registry definition id
     *
     * @return                  revocation registry definition or null if it doesn't exist in ledger
     */
    fun retrieveRevocationRegistryDefinition(id: RevocationRegistryDefinitionId) =
        ledgerService.retrieveRevocationRegistryDefinition(id.toString())

    private data class ProofDataEntry(
        val schemaId: SchemaId,
        val credDefId: CredentialDefinitionId,
        val referentCredentials: List<ReferentCredential>,
        val revState: RevocationState?
    )

    private var cachedTailsHandler: BlobStorageHandler? = null
    private fun getTailsHandler(): BlobStorageHandler {
        if (cachedTailsHandler == null) {
            val tailsConfig = getTailsConfig(tailsPath)

            val reader = BlobStorageReader.openReader("default", tailsConfig).get()
            val writer = BlobStorageWriter.openWriter("default", tailsConfig).get()

            cachedTailsHandler = BlobStorageHandler(reader, writer)
        }

        return cachedTailsHandler!!
    }

    private fun parseProofData(
        collectionFromRequest: Map<String, AbstractCredentialReference>,
        collectionFromCreds: List<CredentialReferenceInfo>,
        nonRevoked: Interval?
    ): List<ProofDataEntry> {

        return collectionFromCreds.map { attribute ->
            val credDefId = attribute.credentialInfo.getCredentialDefinitionId()

            val keys = collectionFromRequest.entries
                .filter { it.value.schemaId == attribute.credentialInfo.schemaId }
                .map { it.key }
            val reference = attribute.credentialInfo.referent
            val referentCredentials = keys.map { ReferentCredential(it, reference) }

            val credRevId = attribute.credentialInfo.credentialRevocationId
            val revRegId = attribute.credentialInfo.revocationRegistryId
            val schemaId = attribute.credentialInfo.getSchemaId()

            if (nonRevoked == null || credRevId == null || revRegId == null) {
                return@map ProofDataEntry(schemaId, credDefId, referentCredentials, null)
            }

            val revState = getRevocationState(credRevId, revRegId, nonRevoked)
            return@map ProofDataEntry(schemaId, credDefId, referentCredentials, revState)
        }

    }

    private fun getRevocationState(credRevId: String, revRegDefId: String, interval: Interval): RevocationState {
        val tailsReaderHandle = getTailsHandler().reader.blobStorageReaderHandle

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
        revState.revocationRegistryId = revRegDefId

        return revState
    }
}