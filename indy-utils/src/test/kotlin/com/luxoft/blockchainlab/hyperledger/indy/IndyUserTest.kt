package com.luxoft.blockchainlab.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.IndyIntegrationTest.Companion.TEST_GENESIS_FILE_PATH
import com.luxoft.blockchainlab.hyperledger.indy.helpers.GenesisHelper
import com.luxoft.blockchainlab.hyperledger.indy.helpers.PoolHelper
import com.luxoft.blockchainlab.hyperledger.indy.helpers.WalletHelper
import com.luxoft.blockchainlab.hyperledger.indy.ledger.IndyPoolLedgerUser
import com.luxoft.blockchainlab.hyperledger.indy.models.CredentialDefinitionId
import com.luxoft.blockchainlab.hyperledger.indy.models.SchemaId
import com.luxoft.blockchainlab.hyperledger.indy.wallet.IndySDKWalletUser
import org.hyperledger.indy.sdk.anoncreds.Anoncreds
import org.hyperledger.indy.sdk.wallet.Wallet
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.io.File

class IndyUserTest {

    lateinit var indyUser: SsiUser
    lateinit var wallet: Wallet

    @Before
    fun setup() {
        val walletName = "default-wallet"
        val poolName = "default-pool"
        val walletPassword = "password"

        WalletHelper.createOrTrunc(walletName, walletPassword)
        wallet = WalletHelper.openExisting(walletName, walletPassword)

        val genesisFile = File(TEST_GENESIS_FILE_PATH)
        if (!GenesisHelper.exists(genesisFile))
            throw RuntimeException("Genesis file $TEST_GENESIS_FILE_PATH doesn't exist")

        PoolHelper.createOrTrunc(genesisFile, poolName)
        val pool = PoolHelper.openExisting(poolName)

        val walletUser = IndySDKWalletUser(wallet)
        indyUser = IndyUser
            .with(walletUser)
            .with(IndyPoolLedgerUser(pool, walletUser.did) { walletUser.sign(it) })
            .build()
    }

    @After
    fun down() {
        wallet.closeWallet()
    }

    @Test
    fun `check schema id format wasnt changed`() {
        val name = "unitTestSchema"
        val version = "1.0"
        val utilsId = SchemaId(indyUser.walletUser.getIdentityDetails().did, name, version)

        val schemaInfo = Anoncreds.issuerCreateSchema(
            indyUser.walletUser.getIdentityDetails().did, name, version, """["attr1"]"""
        ).get()
        assert(utilsId.toString() == schemaInfo.schemaId) { "Generated schema ID doesn't match SDK' ID anymore" }
    }

    @Test
    @Ignore // this test is inconsistent, it should create schema and credential definition before check for something
    fun `check definition id format wasnt changed`() {
        val schemaSeqNo = 14
        val schemaId = SchemaId.fromString("V4SGRU86Z58d6TV7PBUe6f:2:schema_education:1.0")
        val utilsId =
            CredentialDefinitionId(indyUser.walletUser.getIdentityDetails().did, 123, IndySDKWalletUser.TAG)

        val schemaJson = """{
            "ver":"1.0",
            "id":"V4SGRU86Z58d6TV7PBUe6f:2:schema_education:1.0",
            "name":"schema_education",
            "version":"1.0","attrNames":["attrY","attrX"],
            "seqNo":${schemaSeqNo}
        }"""

        val credDefInfo = Anoncreds.issuerCreateAndStoreCredentialDef(
            wallet,
            indyUser.walletUser.getIdentityDetails().did,
            schemaJson,
            IndySDKWalletUser.TAG,
            IndySDKWalletUser.SIGNATURE_TYPE,
            null
        ).get()
        assert(utilsId.toString() == credDefInfo.credDefId) { "Generated credDef ID doesn't match SDK' ID anymore" }
    }
}
