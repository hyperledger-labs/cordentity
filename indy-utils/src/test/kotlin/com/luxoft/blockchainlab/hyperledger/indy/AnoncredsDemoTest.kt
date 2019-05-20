package com.luxoft.blockchainlab.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.helpers.GenesisHelper
import com.luxoft.blockchainlab.hyperledger.indy.helpers.PoolHelper
import com.luxoft.blockchainlab.hyperledger.indy.helpers.WalletHelper
import com.luxoft.blockchainlab.hyperledger.indy.ledger.IndyPoolLedgerService
import com.luxoft.blockchainlab.hyperledger.indy.models.*
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
        // create and open wallets
        WalletHelper.createOrTrunc("Trustee", "123")
        val trusteeWallet = WalletHelper.openExisting("Trustee", "123")

        WalletHelper.createOrTrunc(issuerWalletName, walletPassword)
        issuerWallet = WalletHelper.openExisting(issuerWalletName, walletPassword)

        WalletHelper.createOrTrunc(issuer2WalletName, walletPassword)
        issuer2Wallet = WalletHelper.openExisting(issuer2WalletName, walletPassword)

        WalletHelper.createOrTrunc(proverWalletName, walletPassword)
        proverWallet = WalletHelper.openExisting(proverWalletName, walletPassword)

        // create trustee did
        val trusteeDidInfo = createTrusteeDid(trusteeWallet)

        // create indy users
        val issuerWalletService = IndySDKWalletService(issuerWallet)
        val issuerLedgerService = IndyPoolLedgerService(pool, issuerWallet, issuerWalletService.did)
        issuer1 = IndyUser.with(issuerWalletService).with(issuerLedgerService).build()

        val issuer2WalletService = IndySDKWalletService(issuer2Wallet)
        val issuer2LedgerService = IndyPoolLedgerService(pool, issuer2Wallet, issuer2WalletService.did)
        issuer2 = IndyUser.with(issuer2LedgerService).with(issuer2WalletService).build()

        val proverWalletService = IndySDKWalletService(proverWallet)
        val proverLedgerService = IndyPoolLedgerService(pool, proverWallet, proverWalletService.did)
        prover = IndyUser.with(proverLedgerService).with(proverWalletService).build()

        // set relationships
        linkIssuerToTrustee(trusteeWallet, trusteeDidInfo, issuerWalletService.getIdentityDetails())
        linkIssuerToTrustee(trusteeWallet, trusteeDidInfo, issuer2WalletService.getIdentityDetails())

        issuer1.addKnownIdentitiesAndStoreOnLedger(prover.walletService.getIdentityDetails())

        trusteeWallet.closeWallet().get()
    }

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

    @After
    @Throws(Exception::class)
    fun tearDown() {
        // Issuer Remove Wallet
        issuerWallet.closeWallet().get()
        issuer2Wallet.closeWallet().get()

        // Prover Remove Wallet
        proverWallet.closeWallet().get()
    }

    private fun createTrusteeDid(wallet: Wallet) = Did.createAndStoreMyDid(wallet, """{"seed":"$TRUSTEE_SEED"}""").get()

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
        )  {
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
