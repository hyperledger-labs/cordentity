package com.luxoft.blockchainlab.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.helpers.GenesisHelper
import com.luxoft.blockchainlab.hyperledger.indy.helpers.PoolHelper
import com.luxoft.blockchainlab.hyperledger.indy.helpers.WalletHelper
import com.luxoft.blockchainlab.hyperledger.indy.ledger.IndyPoolLedgerService
import com.luxoft.blockchainlab.hyperledger.indy.models.*
import com.luxoft.blockchainlab.hyperledger.indy.utils.*
import com.luxoft.blockchainlab.hyperledger.indy.wallet.IndySDKWalletService
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import org.hyperledger.indy.sdk.did.Did
import org.hyperledger.indy.sdk.did.DidResults
import org.hyperledger.indy.sdk.ledger.Ledger
import org.hyperledger.indy.sdk.pool.Pool
import org.hyperledger.indy.sdk.wallet.Wallet
import org.junit.*
import java.io.File
import java.util.*
import kotlin.math.absoluteValue


class AnoncredsDemoTest : IndyIntegrationTest() {
    private val walletPassword = "password"
    private val issuerWalletName = "issuerWallet"
    private val issuer2WalletName = "issuer2Wallet"
    private val proverWalletName = "proverWallet"

    private lateinit var issuerWallet: Wallet
    private lateinit var issuer1: IndyFacade

    private lateinit var issuer2Wallet: Wallet
    private lateinit var issuer2: IndyFacade

    private lateinit var proverWallet: Wallet
    private lateinit var prover: IndyFacade

    companion object {
        private lateinit var pool: Pool
        private lateinit var poolName: String

        @JvmStatic
        @BeforeClass
        fun setUpTest() {
            System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "TRACE")

            // Create and Open Pool
            poolName = PoolHelper.DEFAULT_POOL_NAME
            val genesisFile = File(TEST_GENESIS_FILE_PATH)
            if (!GenesisHelper.exists(genesisFile))
                throw RuntimeException("Genesis file $TEST_GENESIS_FILE_PATH doesn't exist")

            PoolHelper.createOrTrunc(genesisFile, poolName)
            pool = PoolHelper.openExisting(poolName)
        }

        @JvmStatic
        @AfterClass
        fun tearDownTest() {
            // Close pool
            pool.closePoolLedger().get()
            Pool.deletePoolLedgerConfig(poolName)
        }
    }

    @Before
    @Throws(Exception::class)
    fun setUp() {
        //issuer1.addKnownIdentitiesAndStoreOnLedger(prover.walletService.getIdentityDetails())
    }

    val rng = Random()

    fun createIssuers(count: Int): List<Pair<Wallet, IndyFacade>> {
        WalletHelper.createOrTrunc("Trustee", "123")
        val trusteeWallet = WalletHelper.openExisting("Trustee", "123")
        // create trustee did
        val trusteeDidInfo = createTrusteeDid(trusteeWallet)

        return (0 until count).map {
            WalletHelper.createOrTrunc("Issuer-$it", "123")
            val issuerWallet = WalletHelper.openExisting("Issuer-$it", "123")

            val issuerWalletService = IndySDKWalletService(issuerWallet)
            val issuerLedgerService = IndyPoolLedgerService(pool, issuerWalletService)
            val issuerFacade = IndyUser.with(issuerWalletService).with(issuerLedgerService).build()

            linkIssuerToTrustee(trusteeWallet, trusteeDidInfo, issuerWalletService.getIdentityDetails())

            Pair(issuerWallet, issuerFacade)
        }.also { trusteeWallet.closeWallet().get() }
    }

    fun createEntities(name: String, count: Int) = (0 until count).map {
        WalletHelper.createOrTrunc("$name-$it", "123")
        val issuerWallet = WalletHelper.openExisting("$name-$it", "123")

        val issuerWalletService = IndySDKWalletService(issuerWallet)
        val issuerLedgerService = IndyPoolLedgerService(pool, issuerWalletService)
        val issuerFacade = IndyUser.with(issuerWalletService).with(issuerLedgerService).build()

        Pair(issuerWallet, issuerFacade)
    }

    fun disposeEntities(entities: List<Pair<Wallet, IndyFacade>>) = entities.forEach { it.first.closeWallet().get() }

    private fun linkIssuerToTrustee(
        trusteeWallet: Wallet,
        trusteeDidInfo: DidResults.CreateAndStoreMyDidResult,
        issuerDidInfo: IdentityDetails
    ) {
        val nymRequest = Ledger.buildNymRequest(
            trusteeDidInfo.did,
            issuerDidInfo.did,
            issuerDidInfo.verkey,
            null,
            "TRUSTEE"
        ).get()

        Ledger.signAndSubmitRequest(pool, trusteeWallet, trusteeDidInfo.did, nymRequest).get()
    }

    private fun createTrusteeDid(wallet: Wallet) = Did.createAndStoreMyDid(wallet, """{"seed":"$TRUSTEE_SEED"}""").get()

    private fun IndyFacade.createMetadata(
        schemaName: String,
        schemaVersion: String,
        schemaAttributes: List<String>,
        enableRevocation: Boolean,
        maxCredentialNumber: Int
    ): Triple<Schema, CredentialDefinition, RevocationRegistryInfo?> {
        val schema = createSchemaAndStoreOnLedger(schemaName, schemaVersion, schemaAttributes)
        val credDef = createCredentialDefinitionAndStoreOnLedger(schema.getSchemaIdObject(), enableRevocation)
        val revRegInfo = if (enableRevocation) {
            if (maxCredentialNumber < 2)
                throw RuntimeException("maxCredentialNumber should be at least 2")
            createRevocationRegistryAndStoreOnLedger(credDef.getCredentialDefinitionIdObject(), maxCredentialNumber)
        } else null

        return Triple(schema, credDef, revRegInfo)
    }

    private fun IndyFacade.createRandomMetadata(
        attributeCountRange: IntRange,
        enableRevocation: Boolean,
        maxCredentialNumber: Int
    ): Triple<Schema, CredentialDefinition, RevocationRegistryInfo?> {
        val name = "schema-${rng.nextInt().absoluteValue}"
        val version = "${rng.nextInt().absoluteValue}.${rng.nextInt().absoluteValue}"
        val attributeCount =
            rng.nextInt().absoluteValue % (attributeCountRange.last - attributeCountRange.first) + attributeCountRange.first
        val attributes = (0 until attributeCount).map { "attribute-${rng.nextInt().absoluteValue}" }

        return createMetadata(name, version, attributes, enableRevocation, maxCredentialNumber)
    }

    private fun IndyFacade.issueRandomCredential(
        to: IndyFacade,
        schemaAttributes: List<String>,
        credentialDefId: CredentialDefinitionId,
        revocationRegistryId: RevocationRegistryDefinitionId?
    ): CredentialInfo {
        val rng = Random()
        val attributesToValues = mutableMapOf<String, String>()

        val offer = this.createCredentialOffer(credentialDefId)
        val request = to.createCredentialRequest(to.walletService.getIdentityDetails().did, offer)

        val credentialInfo = this.issueCredentialAndUpdateLedger(request, offer, revocationRegistryId) {
            schemaAttributes.forEach {
                val value = rng.nextInt().absoluteValue.toString()
                attributes[it] = CredentialValue(value)

                // for test purposes
                attributesToValues[it] = value
            }
        }

        to.checkLedgerAndReceiveCredential(credentialInfo, request, offer)

        return credentialInfo
    }

    data class CredentialAndMetadata(
        val proverDid: String,
        val credentialInfo: CredentialInfo
    )

    private fun IndyFacade.issueRandomSimilarCredentials(
        to: List<IndyFacade>,
        attributeCountRange: IntRange,
        enableRevocation: Boolean,
        count: Int,
        maxCredentialsPerRevRegistry: Int
    ): List<CredentialAndMetadata> {
        val (schema, credDef, revRegInfo) = createRandomMetadata(
            attributeCountRange,
            enableRevocation,
            maxCredentialsPerRevRegistry
        )

        return to.map { prover ->
            (0 until count).map {
                val credential = issueRandomCredential(
                    prover,
                    schema.attributeNames,
                    credDef.getCredentialDefinitionIdObject(),
                    revRegInfo?.definition?.getRevocationRegistryIdObject()
                )
                CredentialAndMetadata(prover.walletService.getIdentityDetails().did, credential)
            }
        }.flatten()
    }

    private fun IndyFacade.issueRandomDifferentCredentials(
        to: List<IndyFacade>,
        attributeCountRange: IntRange,
        enableRevocation: Boolean,
        count: Int,
        maxCredentialsPerRevRegistry: Int
    ) = to.map { prover ->
        (0 until count).map {
            val (schema, credDef, revRegInfo) = createRandomMetadata(
                attributeCountRange,
                enableRevocation,
                maxCredentialsPerRevRegistry
            )

            val credential = issueRandomCredential(
                prover,
                schema.attributeNames,
                credDef.getCredentialDefinitionIdObject(),
                revRegInfo?.definition?.getRevocationRegistryIdObject()
            )
            CredentialAndMetadata(prover.walletService.getIdentityDetails().did, credential)
        }
    }.flatten()

    private fun IndyFacade.verifyRandomAttributes(
        of: IndyFacade,
        nonRevoked: Interval?,
        vararg credentials: Credential
    ): Pair<Boolean, ProofRequest> {
        val payloads = credentials.map { ProofRequestPayload.fromCredential(it) }.toTypedArray()
        val proofRequest = createRandomProofRequest(nonRevoked, *payloads)

        val proof = of.createProofFromLedgerData(proofRequest)

        val verifyStatus = verifyProofWithLedgerData(proofRequest, proof)

        return Pair(verifyStatus, proofRequest)
    }

    data class ProofState(
        val proverDid: String,
        val verifierDid: String,
        val credentials: List<CredentialInfo>,
        val proofRequest: ProofRequest
    ) {
        constructor(
            issuer: IndyFacade,
            verifier: IndyFacade,
            credentials: List<CredentialInfo>,
            proofRequest: ProofRequest
        ) : this(
            issuer.walletService.getIdentityDetails().did,
            verifier.walletService.getIdentityDetails().did,
            credentials,
            proofRequest
        )
    }

    private fun constructTypicalFlow(
        issuerCount: Int,
        proverCount: Int,
        verifierCount: Int,
        attributeCountRange: IntRange,
        credentialCount: Int,
        similarCredentials: Boolean,
        enableRevocation: Boolean,
        maxCredentialsPerRevRegistry: Int
    ): Boolean {
        val issuers = createIssuers(issuerCount)
        val provers = createEntities("Prover", proverCount)

        val issuerToCredentials = if (similarCredentials)
            issuers.associate { (issuerWallet, issuer) ->
                issuer to issuer.issueRandomSimilarCredentials(
                    provers.map { it.second },
                    attributeCountRange,
                    enableRevocation,
                    credentialCount,
                    maxCredentialsPerRevRegistry
                )
            }
        else
            issuers.associate { (issuerWallet, issuer) ->
                issuer to issuer.issueRandomDifferentCredentials(
                    provers.map { it.second },
                    attributeCountRange,
                    enableRevocation,
                    credentialCount,
                    maxCredentialsPerRevRegistry
                )
            }

        val credentialsByProver = mutableMapOf<IndyFacade, MutableList<CredentialInfo>>()
        issuerToCredentials.entries.forEach { (issuer, credentialAndMetadataList) ->
            credentialAndMetadataList.forEach { credentialAndMetadata ->
                val prover =
                    provers.first { it.second.walletService.getIdentityDetails().did == credentialAndMetadata.proverDid }
                        .second
                val proverCredentials = credentialsByProver.getOrPut(prover) { mutableListOf() }

                proverCredentials.add(credentialAndMetadata.credentialInfo)
            }
        }

        val verifiers = createEntities("Verifier", verifierCount)

        val unableToProve = mutableListOf<ProofState>()

        val nonRevoked = if (enableRevocation) Interval.now() else null

        verifiers.forEach { (verifierWallet, verifier) ->
            credentialsByProver.entries.forEach { (prover, credentials) ->
                val (proofStatus, proofRequest) = verifier.verifyRandomAttributes(
                    prover,
                    nonRevoked,
                    *(credentials.map { it.credential }.toTypedArray())
                )

                if (!proofStatus)
                    unableToProve.add(
                        ProofState(
                            prover.walletService.getIdentityDetails().did,
                            verifier.walletService.getIdentityDetails().did,
                            credentials,
                            proofRequest
                        )
                    )
            }
        }

        if (unableToProve.isNotEmpty()) {
            println("------- Failed proofs -------")
            println(SerializationUtils.anyToJSON(unableToProve))
            println("-----------------------------")
        }

        val ableToProve = mutableListOf<ProofState>()

        if (enableRevocation) {
            issuerToCredentials.forEach { (issuer, credentialAndMetadataList) ->
                credentialAndMetadataList.map { it.credentialInfo }.forEach { credentialInfo ->
                    issuer.revokeCredentialAndUpdateLedger(
                        credentialInfo.credential.getRevocationRegistryIdObject()!!,
                        credentialInfo.credRevocId!!
                    )
                }
            }

            verifiers.forEach { (verifierWallet, verifier) ->
                credentialsByProver.entries.forEach { (prover, credentials) ->
                    val (proofStatus, proofRequest) = verifier.verifyRandomAttributes(
                        prover,
                        Interval.now(),
                        *(credentials.map { it.credential }.toTypedArray())
                    )

                    if (proofStatus)
                        ableToProve.add(
                            ProofState(
                                prover.walletService.getIdentityDetails().did,
                                verifier.walletService.getIdentityDetails().did,
                                credentials,
                                proofRequest
                            )
                        )
                }
            }

            if (ableToProve.isNotEmpty()) {
                println("------- Failed proofs after revocation -------")
                println(SerializationUtils.anyToJSON(ableToProve))
                println("----------------------------------------------")
            }
        }

        return unableToProve.isEmpty() && ableToProve.isEmpty()
    }

    @Test
    fun `1 issuer 1 prover 1 verifier 1 credential without revocation`() {
        assert(
            constructTypicalFlow(1, 1, 1, (2..4), 1, false, false, 2)
        )
    }

    @Test
    @Throws(Exception::class)
    fun `revocation works fine`() {
        val gvtSchema = issuer1.createSchemaAndStoreOnLedger(GVT_SCHEMA_NAME, SCHEMA_VERSION, GVT_SCHEMA_ATTRIBUTES)
        val credDef = issuer1.createCredentialDefinitionAndStoreOnLedger(gvtSchema.getSchemaIdObject(), true)
        val revocationRegistry =
            issuer1.createRevocationRegistryAndStoreOnLedger(credDef.getCredentialDefinitionIdObject(), 5)

        val credOffer = issuer1.createCredentialOffer(credDef.getCredentialDefinitionIdObject())
        val credReq = prover.createCredentialRequest(prover.walletService.getIdentityDetails().did, credOffer)
        val credentialInfo = issuer1.issueCredentialAndUpdateLedger(
            credReq,
            credOffer,
            revocationRegistry.definition.getRevocationRegistryIdObject()
        ) {
            attributes["sex"] = CredentialValue("male")
            attributes["name"] = CredentialValue("Alex")
            attributes["height"] = CredentialValue("175")
            attributes["age"] = CredentialValue("28")
        }
        prover.checkLedgerAndReceiveCredential(credentialInfo, credReq, credOffer)

        val proofReq = proofRequest("proof_req", "0.1") {
            reveal("name") { FilterProperty.Value shouldBe "Alex" }
            reveal("sex")
            proveGreaterThan("age", 18)
            proveNonRevocation(Interval.now())
        }

        val proof = prover.createProofFromLedgerData(proofReq)

        assertTrue(issuer1.verifyProofWithLedgerData(proofReq, proof))

        issuer1.revokeCredentialAndUpdateLedger(
            credentialInfo.credential.getRevocationRegistryIdObject()!!,
            credentialInfo.credRevocId!!
        )

        val proofReqAfterRevocation = proofRequest("proof_req", "0.2") {
            reveal("name") { FilterProperty.Value shouldBe "Alex" }
            reveal("sex")
            proveGreaterThan("age", 18)
            proveNonRevocation(Interval.now())
        }

        val proofAfterRevocation = prover.createProofFromLedgerData(proofReqAfterRevocation)

        assertFalse(issuer1.verifyProofWithLedgerData(proofReqAfterRevocation, proofAfterRevocation))
    }

    @Test
    fun `issuer issues 1 credential verifier tries to verify it several times`() {
        // init metadata and issue credential
        val schema = issuer1.createSchemaAndStoreOnLedger(GVT_SCHEMA_NAME, SCHEMA_VERSION, GVT_SCHEMA_ATTRIBUTES)
        val credDef = issuer1.createCredentialDefinitionAndStoreOnLedger(schema.getSchemaIdObject(), false)

        val credOffer = issuer1.createCredentialOffer(credDef.getCredentialDefinitionIdObject())
        val credReq = prover.createCredentialRequest(prover.walletService.getIdentityDetails().did, credOffer)
        val credInfo = issuer1.issueCredentialAndUpdateLedger(credReq, credOffer, null) {
            attributes["sex"] = CredentialValue("male")
            attributes["name"] = CredentialValue("Alex")
            attributes["height"] = CredentialValue("175")
            attributes["age"] = CredentialValue("28")
        }
        prover.checkLedgerAndReceiveCredential(credInfo, credReq, credOffer)

        // repeating this stuff for 3 times
        for (i in (0 until 3)) {
            val proofReq = proofRequest("proof_req", "0.$i") {
                reveal("name")
                reveal("sex")
                proveGreaterThan("age", 18)
            }

            val proof = prover.createProofFromLedgerData(proofReq)

            assertTrue(issuer1.verifyProofWithLedgerData(proofReq, proof))
        }
    }

    @Ignore
    @Test
    fun `issuer issues 2 similar credentials verifier tries to verify both`() {
        // init metadata and issue credential
        val schema = issuer1.createSchemaAndStoreOnLedger(GVT_SCHEMA_NAME, SCHEMA_VERSION, GVT_SCHEMA_ATTRIBUTES)
        val credDef = issuer1.createCredentialDefinitionAndStoreOnLedger(schema.getSchemaIdObject(), true)
        val revReg = issuer1.createRevocationRegistryAndStoreOnLedger(credDef.getCredentialDefinitionIdObject(), 5)
        val revRegId = revReg.definition.getRevocationRegistryIdObject()

        // issue first credential
        val credOffer1 = issuer1.createCredentialOffer(credDef.getCredentialDefinitionIdObject())
        val credReq1 = prover.createCredentialRequest(prover.walletService.getIdentityDetails().did, credOffer1)
        val credInfo1 = issuer1.issueCredentialAndUpdateLedger(credReq1, credOffer1, revRegId) {
            attributes["sex"] = CredentialValue("male")
            attributes["name"] = CredentialValue("Alex")
            attributes["height"] = CredentialValue("175")
            attributes["age"] = CredentialValue("28")
        }
        prover.checkLedgerAndReceiveCredential(credInfo1, credReq1, credOffer1)

        // verify first credential
        val proofReq1 = proofRequest("proof_req", "0.1") {
            reveal("name") { FilterProperty.Value shouldBe "Alex" }
            reveal("sex")
            proveGreaterThan("age", 18)
            proveNonRevocation(Interval.now())
        }

        val proof1 = prover.createProofFromLedgerData(proofReq1)

        assert(issuer1.verifyProofWithLedgerData(proofReq1, proof1)) { "Proof is invalid for Alex" }

        // TODO: this line should be optional, but it isn't
        //issuer1.revokeCredentialAndUpdateLedger(revReg.definition.getRevocationRegistryIdObject()!!, credInfo1.credRevocId!!)

        // issue second credential
        val credOffer2 = issuer1.createCredentialOffer(credDef.getCredentialDefinitionIdObject())
        val credReq2 = prover.createCredentialRequest(prover.walletService.getIdentityDetails().did, credOffer2)

        val credInfo2 = issuer1.issueCredentialAndUpdateLedger(credReq2, credOffer2, revRegId) {
            attributes["sex"] = CredentialValue("female")
            attributes["name"] = CredentialValue("Alice")
            attributes["height"] = CredentialValue("158")
            attributes["age"] = CredentialValue("17")
        }

        prover.checkLedgerAndReceiveCredential(credInfo2, credReq2, credOffer2)

        // verify second credential
        val proofReq2 = proofRequest("proof_req", "0.1") {
            reveal("name") { FilterProperty.Value shouldBe "Alice" }
            reveal("age")
            proveNonRevocation(Interval.now())
        }

        val proof2 = prover.createProofFromLedgerData(proofReq2)

        // TODO: is should be without "!" but it isn't
        assert(!issuer1.verifyProofWithLedgerData(proofReq2, proof2)) { "Proof is not valid for Alice" }
    }

    @Test
    @Throws(Exception::class)
    fun `1 issuer 1 prover 1 credential setup works fine`() {
        val gvtSchema = issuer1.createSchemaAndStoreOnLedger(GVT_SCHEMA_NAME, SCHEMA_VERSION, GVT_SCHEMA_ATTRIBUTES)
        val credDef = issuer1.createCredentialDefinitionAndStoreOnLedger(gvtSchema.getSchemaIdObject(), false)
        val credOffer = issuer1.createCredentialOffer(credDef.getCredentialDefinitionIdObject())
        val credReq = prover.createCredentialRequest(prover.walletService.getIdentityDetails().did, credOffer)
        val credentialInfo = issuer1.issueCredentialAndUpdateLedger(credReq, credOffer, null) {
            attributes["sex"] = CredentialValue("male")
            attributes["name"] = CredentialValue("Alex")
            attributes["height"] = CredentialValue("175")
            attributes["age"] = CredentialValue("28")
        }
        prover.checkLedgerAndReceiveCredential(credentialInfo, credReq, credOffer)

        val proofReq = proofRequest("proof_req", "0.1") {
            reveal("name") { FilterProperty.Value shouldBe "Alex" }
            reveal("sex")
            proveGreaterThan("age", 18)
        }

        val proof = prover.createProofFromLedgerData(proofReq)

        assertTrue(issuer1.verifyProofWithLedgerData(proofReq, proof))
    }

    @Test
    @Throws(Exception::class)
    fun `2 issuers 1 prover 2 credentials setup works fine`() {
        val schema1 = issuer1.createSchemaAndStoreOnLedger(GVT_SCHEMA_NAME, SCHEMA_VERSION, GVT_SCHEMA_ATTRIBUTES)
        val credDef1 = issuer1.createCredentialDefinitionAndStoreOnLedger(schema1.getSchemaIdObject(), false)

        val schema2 = issuer2.createSchemaAndStoreOnLedger(XYZ_SCHEMA_NAME, SCHEMA_VERSION, XYZ_SCHEMA_ATTRIBUTES)
        val credDef2 = issuer2.createCredentialDefinitionAndStoreOnLedger(schema2.getSchemaIdObject(), false)
        val gvtCredOffer = issuer1.createCredentialOffer(credDef1.getCredentialDefinitionIdObject())
        val xyzCredOffer = issuer2.createCredentialOffer(credDef2.getCredentialDefinitionIdObject())

        val gvtCredReq = prover.createCredentialRequest(prover.walletService.getIdentityDetails().did, gvtCredOffer)
        val gvtCredential = issuer1.issueCredentialAndUpdateLedger(gvtCredReq, gvtCredOffer, null) {
            attributes["sex"] = CredentialValue("male")
            attributes["name"] = CredentialValue("Alex")
            attributes["height"] = CredentialValue("175")
            attributes["age"] = CredentialValue("28")
        }
        prover.checkLedgerAndReceiveCredential(gvtCredential, gvtCredReq, gvtCredOffer)

        val xyzCredReq = prover.createCredentialRequest(prover.walletService.getIdentityDetails().did, xyzCredOffer)
        val xyzCredential = issuer2.issueCredentialAndUpdateLedger(xyzCredReq, xyzCredOffer, null) {
            attributes["status"] = CredentialValue("partial")
            attributes["period"] = CredentialValue("8")
        }
        prover.checkLedgerAndReceiveCredential(xyzCredential, xyzCredReq, xyzCredOffer)

        val proofReq = proofRequest("proof_req", "0.1") {
            reveal("name") { FilterProperty.Value shouldBe "Alex" }
            reveal("status") { FilterProperty.Value shouldBe "partial" }
            proveGreaterThan("period", 5)
            proveGreaterThan("age", 18)
        }

        val proof = prover.createProofFromLedgerData(proofReq)

        // Verifier verify Proof
        assertTrue(issuer1.verifyProofWithLedgerData(proofReq, proof))
    }

    @Test
    @Throws(Exception::class)
    fun `1 issuer 1 prover 2 credentials setup works fine`() {
        val gvtSchema = issuer1.createSchemaAndStoreOnLedger(GVT_SCHEMA_NAME, SCHEMA_VERSION, GVT_SCHEMA_ATTRIBUTES)
        val gvtCredDef = issuer1.createCredentialDefinitionAndStoreOnLedger(gvtSchema.getSchemaIdObject(), false)

        val xyzSchema = issuer1.createSchemaAndStoreOnLedger(XYZ_SCHEMA_NAME, SCHEMA_VERSION, XYZ_SCHEMA_ATTRIBUTES)
        val xyzCredDef = issuer1.createCredentialDefinitionAndStoreOnLedger(xyzSchema.getSchemaIdObject(), false)
        val gvtCredOffer = issuer1.createCredentialOffer(gvtCredDef.getCredentialDefinitionIdObject())
        val xyzCredOffer = issuer1.createCredentialOffer(xyzCredDef.getCredentialDefinitionIdObject())

        val gvtCredReq = prover.createCredentialRequest(prover.walletService.getIdentityDetails().did, gvtCredOffer)
        val gvtCredential = issuer1.issueCredentialAndUpdateLedger(gvtCredReq, gvtCredOffer, null) {
            attributes["sex"] = CredentialValue("male")
            attributes["name"] = CredentialValue("Alex")
            attributes["height"] = CredentialValue("175")
            attributes["age"] = CredentialValue("28")
        }
        prover.checkLedgerAndReceiveCredential(gvtCredential, gvtCredReq, gvtCredOffer)

        val xyzCredReq = prover.createCredentialRequest(prover.walletService.getIdentityDetails().did, xyzCredOffer)
        val xyzCredential = issuer1.issueCredentialAndUpdateLedger(xyzCredReq, xyzCredOffer, null) {
            attributes["status"] = CredentialValue("partial")
            attributes["period"] = CredentialValue("8")
        }
        prover.checkLedgerAndReceiveCredential(xyzCredential, xyzCredReq, xyzCredOffer)

        val proofReq = proofRequest("proof_req", "0.1") {
            reveal("name") { FilterProperty.Value shouldBe "Alex" }
            reveal("status") { FilterProperty.Value shouldBe "partial" }
            proveGreaterThan("period", 5)
            proveGreaterThan("age", 18)
        }

        val proof = prover.createProofFromLedgerData(proofReq)

        // Verifier verify Proof
        assertTrue(issuer1.verifyProofWithLedgerData(proofReq, proof))
    }
}
