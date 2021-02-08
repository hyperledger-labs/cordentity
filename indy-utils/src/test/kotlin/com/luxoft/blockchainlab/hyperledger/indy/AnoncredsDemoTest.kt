package com.luxoft.blockchainlab.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.helpers.WalletHelper
import com.luxoft.blockchainlab.hyperledger.indy.ledger.IndyPoolLedgerUser
import com.luxoft.blockchainlab.hyperledger.indy.models.CredentialValue
import com.luxoft.blockchainlab.hyperledger.indy.models.Interval
import com.luxoft.blockchainlab.hyperledger.indy.models.PredicateTypes
import com.luxoft.blockchainlab.hyperledger.indy.utils.*
import com.luxoft.blockchainlab.hyperledger.indy.wallet.IndySDKWalletUser
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import org.hyperledger.indy.sdk.wallet.Wallet
import org.junit.After
import org.junit.Before
import org.junit.Test


class AnoncredsDemoTest : IndyIntegrationTest() {
    private val walletPassword = "password"
    private val issuerWalletName = "issuerWallet"
    private val issuer2WalletName = "issuer2Wallet"
    private val proverWalletName = "proverWallet"

    private lateinit var issuerWallet: Wallet
    private lateinit var issuer1: SsiUser

    private lateinit var issuer2Wallet: Wallet
    private lateinit var issuer2: SsiUser

    private lateinit var proverWallet: Wallet
    private lateinit var prover: SsiUser

    @Before
    @Throws(Exception::class)
    fun setUp() {
        WalletHelper.createOrTrunc(issuerWalletName, walletPassword)
        issuerWallet = WalletHelper.openExisting(issuerWalletName, walletPassword)

        WalletHelper.createOrTrunc(issuer2WalletName, walletPassword)
        issuer2Wallet = WalletHelper.openExisting(issuer2WalletName, walletPassword)

        WalletHelper.createOrTrunc(proverWalletName, walletPassword)
        proverWallet = WalletHelper.openExisting(proverWalletName, walletPassword)

        // create indy users
        val issuerWalletUser = IndySDKWalletUser(issuerWallet)
        val issuerLedgerUser = IndyPoolLedgerUser(pool, issuerWalletUser.did) { issuerWalletUser.sign(it) }
        issuer1 = IndyUser.with(issuerWalletUser).with(issuerLedgerUser).build()

        val issuer2WalletUser = IndySDKWalletUser(issuer2Wallet)
        val issuer2LedgerUser = IndyPoolLedgerUser(pool, issuer2WalletUser.did) { issuer2WalletUser.sign(it) }
        issuer2 = IndyUser.with(issuer2LedgerUser).with(issuer2WalletUser).build()

        val proverWalletUser = IndySDKWalletUser(proverWallet)
        val proverLedgerUser = IndyPoolLedgerUser(pool, proverWalletUser.did) { proverWalletUser.sign(it) }
        prover = IndyUser.with(proverLedgerUser).with(proverWalletUser).build()

        // set relationships
        WalletUtils.grantLedgerRights(pool, issuerWalletUser.getIdentityDetails())
        WalletUtils.grantLedgerRights(pool, issuer2WalletUser.getIdentityDetails())
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

    @Test
    @Throws(Exception::class)
    fun `revocation works fine`() {
        val gvtSchema = issuer1.createSchemaAndStoreOnLedger(GVT_SCHEMA_NAME, SCHEMA_VERSION, GVT_SCHEMA_ATTRIBUTES)
        val credDef = issuer1.createCredentialDefinitionAndStoreOnLedger(gvtSchema.getSchemaIdObject(), true)
        val revocationRegistry =
            issuer1.createRevocationRegistryAndStoreOnLedger(credDef.getCredentialDefinitionIdObject(), 5)

        val credOffer = issuer1.createCredentialOffer(credDef.getCredentialDefinitionIdObject())
        val credReq = prover.createCredentialRequest(prover.walletUser.getIdentityDetails().did, credOffer)
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
            provePredicateThan("age", PredicateTypes.GE, 18)
            proveNonRevocation(Interval.allTime())
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
            provePredicateThan("age", PredicateTypes.GE, 18)
            proveNonRevocation(Interval.allTime())
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
        val credReq = prover.createCredentialRequest(prover.walletUser.getIdentityDetails().did, credOffer)
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
                reveal("name") { FilterProperty.Value shouldBe "Alex" }
                reveal("sex")
                provePredicateThan("age", PredicateTypes.GE, 18)
            }

            val proof = prover.createProofFromLedgerData(proofReq)

            assertTrue(issuer1.verifyProofWithLedgerData(proofReq, proof))
        }
    }

    @Test
    fun `issuer issues 5 similar credentials verifier tries to verify all`() {
        System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "TRACE")

        // init metadata and issue credential
        val schema = issuer1.createSchemaAndStoreOnLedger(GVT_SCHEMA_NAME, SCHEMA_VERSION, GVT_SCHEMA_ATTRIBUTES)
        val credDef = issuer1.createCredentialDefinitionAndStoreOnLedger(schema.getSchemaIdObject(), true)
        val revReg = issuer1.createRevocationRegistryAndStoreOnLedger(credDef.getCredentialDefinitionIdObject(), 5)
        val revRegId = revReg.definition.getRevocationRegistryIdObject()

        // issue first credential
        for (i in 0..3) {
            val credOffer1 = issuer1.createCredentialOffer(credDef.getCredentialDefinitionIdObject())
            val credReq1 = prover.createCredentialRequest(prover.walletUser.getIdentityDetails().did, credOffer1)
            val credInfo1 = issuer1.issueCredentialAndUpdateLedger(credReq1, credOffer1, revRegId) {
                attributes["sex"] = CredentialValue("male")
                attributes["name"] = CredentialValue("Alex$i")
                attributes["height"] = CredentialValue("${i+175}")
                attributes["age"] = CredentialValue("${i+28}")
            }
            val storedCredentialId = prover.checkLedgerAndReceiveCredential(credInfo1, credReq1, credOffer1)

            // verify first credential
            val proofReq1 = proofRequest("proof_req", "0.1") {
                reveal("name") {
                    FilterProperty.Value shouldBe "Alex$i"
                }
                reveal("sex")
                provePredicateThan("age", PredicateTypes.GE, 18)
                proveNonRevocation(Interval.allTime())
            }

            val proof1 = prover.createProofFromLedgerData(proofReq1)
            var proof1Valid = issuer1.verifyProofWithLedgerData(proofReq1, proof1)
            assert(proof1Valid) {"Proof1 is invalid for Alex$i"}
        }
        //issuer1.revokeCredentialAndUpdateLedger(revReg.definition.getRevocationRegistryIdObject()!!, credInfo1.credRevocId!!)

        // issue second credential
        val credOffer2 = issuer1.createCredentialOffer(credDef.getCredentialDefinitionIdObject())
        val credReq2 = prover.createCredentialRequest(prover.walletUser.getIdentityDetails().did, credOffer2)

        val credInfo2 = issuer1.issueCredentialAndUpdateLedger(credReq2, credOffer2, revRegId) {
            attributes["sex"] = CredentialValue("female")
            attributes["name"] = CredentialValue("Alice")
            attributes["height"] = CredentialValue("158")
            attributes["age"] = CredentialValue("17")
        }

        prover.checkLedgerAndReceiveCredential(credInfo2, credReq2, credOffer2)

        // verify second credential
        val proofReq2 = proofRequest("proof_req", "0.1") {
            reveal("sex") { FilterProperty.Value shouldBe "female" }
            reveal("name")
            reveal("age")
            proveNonRevocation(Interval.allTime())
        }

        val proof2 = prover.createProofFromLedgerData(proofReq2)

        val proofWithLedgerData = issuer1.verifyProofWithLedgerData(proofReq2, proof2)
        assert(proofWithLedgerData) { "Proof is not valid for Alice" }
    }

    @Test
    @Throws(Exception::class)
    fun `1 issuer 1 prover 1 credential setup works fine`() {
        val gvtSchema = issuer1.createSchemaAndStoreOnLedger(GVT_SCHEMA_NAME, SCHEMA_VERSION, GVT_SCHEMA_ATTRIBUTES)
        val credDef = issuer1.createCredentialDefinitionAndStoreOnLedger(gvtSchema.getSchemaIdObject(), false)
        val credOffer = issuer1.createCredentialOffer(credDef.getCredentialDefinitionIdObject())
        val credReq = prover.createCredentialRequest(prover.walletUser.getIdentityDetails().did, credOffer)
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
            provePredicateThan("age", PredicateTypes.GE, 18)
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

        val gvtCredReq = prover.createCredentialRequest(prover.walletUser.getIdentityDetails().did, gvtCredOffer)
        val gvtCredential = issuer1.issueCredentialAndUpdateLedger(gvtCredReq, gvtCredOffer, null) {
            attributes["sex"] = CredentialValue("male")
            attributes["name"] = CredentialValue("Alex")
            attributes["height"] = CredentialValue("175")
            attributes["age"] = CredentialValue("28")
        }
        prover.checkLedgerAndReceiveCredential(gvtCredential, gvtCredReq, gvtCredOffer)

        val xyzCredReq = prover.createCredentialRequest(prover.walletUser.getIdentityDetails().did, xyzCredOffer)
        val xyzCredential = issuer2.issueCredentialAndUpdateLedger(xyzCredReq, xyzCredOffer, null) {
            attributes["status"] = CredentialValue("partial")
            attributes["period"] = CredentialValue("8")
        }
        prover.checkLedgerAndReceiveCredential(xyzCredential, xyzCredReq, xyzCredOffer)

        val proofReq = proofRequest("proof_req", "0.1") {
            reveal("name")
            reveal("status")
            provePredicateThan("period", PredicateTypes.GE, 5)
            provePredicateThan("age", PredicateTypes.GE, 18)
        }

        val proof = prover.createProofFromLedgerData(proofReq) {
            attributes["name"] = wql {
                "name" eq "Alex"
            }

            attributes["status"] = wql {
                "status" eq "partial"
            }
        }

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

        val gvtCredReq = prover.createCredentialRequest(prover.walletUser.getIdentityDetails().did, gvtCredOffer)
        val gvtCredential = issuer1.issueCredentialAndUpdateLedger(gvtCredReq, gvtCredOffer, null) {
            attributes["sex"] = CredentialValue("male")
            attributes["name"] = CredentialValue("Alex")
            attributes["height"] = CredentialValue("175")
            attributes["age"] = CredentialValue("28")
        }
        prover.checkLedgerAndReceiveCredential(gvtCredential, gvtCredReq, gvtCredOffer)

        val xyzCredReq = prover.createCredentialRequest(prover.walletUser.getIdentityDetails().did, xyzCredOffer)
        val xyzCredential = issuer1.issueCredentialAndUpdateLedger(xyzCredReq, xyzCredOffer, null) {
            attributes["status"] = CredentialValue("partial")
            attributes["period"] = CredentialValue("8")
        }
        prover.checkLedgerAndReceiveCredential(xyzCredential, xyzCredReq, xyzCredOffer)

        val proofReq = proofRequest("proof_req", "0.1") {
            reveal("name")
            reveal("status")
            provePredicateThan("period", PredicateTypes.GE, 5)
            provePredicateThan("age", PredicateTypes.GE, 18)
        }

        val proof = prover.createProofFromLedgerData(proofReq) {
            attributes["name"] = wql {
                "name" eq "Alex"
            }

            attributes["status"] = wql {
                "status" eq "partial"
            }
        }

        // Verifier verify Proof
        assertTrue(issuer1.verifyProofWithLedgerData(proofReq, proof))
    }
}
