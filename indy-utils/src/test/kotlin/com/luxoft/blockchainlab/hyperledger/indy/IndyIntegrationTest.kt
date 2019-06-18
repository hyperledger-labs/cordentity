package com.luxoft.blockchainlab.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.helpers.GenesisHelper
import com.luxoft.blockchainlab.hyperledger.indy.helpers.PoolHelper
import com.luxoft.blockchainlab.hyperledger.indy.ledger.IndyPoolLedgerUser
import com.luxoft.blockchainlab.hyperledger.indy.models.IdentityDetails
import com.luxoft.blockchainlab.hyperledger.indy.wallet.IndySDKWalletUser
import org.hyperledger.indy.sdk.did.Did
import org.hyperledger.indy.sdk.did.DidResults
import org.hyperledger.indy.sdk.pool.Pool
import org.hyperledger.indy.sdk.wallet.Wallet
import org.junit.AfterClass
import org.junit.BeforeClass
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull


open class IndyIntegrationTest {
    protected var GVT_SCHEMA_NAME = "gvt"
    protected var XYZ_SCHEMA_NAME = "xyz"
    protected var SCHEMA_VERSION = "1.0"
    protected var GVT_SCHEMA_ATTRIBUTES = listOf("name", "age", "sex", "height")
    protected var XYZ_SCHEMA_ATTRIBUTES = listOf("status", "period")
    protected var GVT_CRED_VALUES = "{\n" +
            "        \"sex\": {\"raw\": \"male\", \"encoded\": \"5944657099558967239210949258394887428692050081607692519917050\"},\n" +
            "        \"name\": {\"raw\": \"Alex\", \"encoded\": \"1139481716457488690172217916278103335\"},\n" +
            "        \"height\": {\"raw\": \"175\", \"encoded\": \"175\"},\n" +
            "        \"age\": {\"raw\": \"28\", \"encoded\": \"28\"}\n" +
            "    }"
    protected var CREDENTIALS = "{\"key\": \"key\"}"
    protected var PROTOCOL_VERSION = 2

    protected val TRUSTEE_SEED = "000000000000000000000000Trustee1"
    protected val MY1_SEED = "00000000000000000000000000000My1"
    protected val MY2_SEED = "00000000000000000000000000000My2"
    protected val VERKEY_MY1 = "GjZWsBLgZCR18aL468JAT7w9CZRiBnpxUPPgyQxh4voa"
    protected val VERKEY_MY2 = "kqa2HyagzfMAq42H5f9u3UMwnSBPQx2QfrSyXbUPxMn"
    protected val VERKEY_TRUSTEE = "GJ1SzoWzavQYfNL9XkaJdrQejfztN4XqdsiV4ct3LXKL"
    protected val DID_MY1 = "VsKV7grR1BUE29mG2Fm2kX"
    protected val DID_MY2 = "2PRyVHmkXQnQzJQKxHxnXC"
    protected val DID_TRUSTEE = "V4SGRU86Z58d6TV7PBUe6f"
    protected val TYPE = "default"

    companion object {
        lateinit var pool: Pool
        lateinit var poolName: String

        val TEST_GENESIS_FILE_PATH by lazy {
            this::class.java.classLoader.getResource("docker_pool_transactions_genesis.txt").file
        }

        @JvmStatic
        @BeforeClass
        fun setUpTest() {
            System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "OFF")

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

    protected fun linkIssuerToTrustee(
        trusteeWallet: Wallet,
        trusteeDidInfo: DidResults.CreateAndStoreMyDidResult,
        issuerDidInfo: IdentityDetails
    ) {
        IndyPoolLedgerUser(pool, trusteeDidInfo.did) {
            IndySDKWalletUser(trusteeWallet, trusteeDidInfo.did).sign(it)
        }.apply {
            storeNym(issuerDidInfo.copy(role = "TRUSTEE"))
            val nym = getNym(issuerDidInfo)
            val nymData = nym.result.getData()
            assertNotNull(nymData)
            assertEquals(nymData?.identifier, trusteeDidInfo.did)
            assertEquals(nymData?.role, "0")
        }
    }

    protected fun createTrusteeDid(wallet: Wallet) = Did.createAndStoreMyDid(wallet, """{"seed":"$TRUSTEE_SEED"}""").get()
}
