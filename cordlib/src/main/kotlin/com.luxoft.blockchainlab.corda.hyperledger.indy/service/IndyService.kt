package com.luxoft.blockchainlab.corda.hyperledger.indy.service


import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.name
import com.luxoft.blockchainlab.hyperledger.indy.SsiUser
import com.luxoft.blockchainlab.hyperledger.indy.IndyUser
import com.luxoft.blockchainlab.hyperledger.indy.helpers.*
import com.luxoft.blockchainlab.hyperledger.indy.ledger.IndyPoolLedgerUser
import com.luxoft.blockchainlab.hyperledger.indy.models.DidConfig
import com.luxoft.blockchainlab.hyperledger.indy.wallet.IndySDKWalletUser
import mu.KotlinLogging
import net.corda.core.node.AppServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.SingletonSerializeAsToken
import java.io.File
import java.lang.RuntimeException

/**
 * A Corda service for dealing with Indy Ledger infrastructure such as pools, credentials, wallets.
 *
 * The current implementation is a POC and lacks any configurability.
 * It is planed to be extended in the future version.
 */
@CordaService
class IndyService(services: AppServiceHub) : SingletonSerializeAsToken() {
    private val logger = KotlinLogging.logger {}

    /**
     * These next private values should be initialized here despite the fact they are used only in [indyUser] initialization.
     * This is so because we're unable to mock Kotlin-object in our case properly (several times with context save) in
     * tests, but in production we also should have lazy initialization.
     *
     * So basically we need to access config at static init time, but use config values at lazy init time
     */
    private val walletName = ConfigHelper.getWalletName()
    private val walletPassword = ConfigHelper.getWalletPassword()
    private val genesisFilePath = ConfigHelper.getGenesisPath()
    private val poolName = ConfigHelper.getPoolName() ?: PoolHelper.DEFAULT_POOL_NAME
    private val userRole = ConfigHelper.getRole() ?: "" // TODO: why do we need this in config?
    private val did = ConfigHelper.getDid()
    private val seed = ConfigHelper.getSeed()

    val indyUser: SsiUser by lazy {
        val nodeName = services.myInfo.name().organisation

        walletName ?: throw RuntimeException("Wallet name should be specified in config")
        walletPassword ?: throw RuntimeException("Wallet password should be specified in config")

        val wallet = WalletHelper.openOrCreate(walletName, walletPassword)
        logger.debug { "Wallet created for $nodeName" }

        val tailsPath = "tails"
        val didConfig = DidConfig(did, seed, null, null)

        val walletUser = IndySDKWalletUser(wallet, didConfig, tailsPath)
        logger.debug { "IndyUser object created for $nodeName" }

        genesisFilePath ?: throw RuntimeException("Genesis file path should be specified in config")
        val genesisFile = File(genesisFilePath)
        if (!GenesisHelper.exists(genesisFile))
            throw RuntimeException("Genesis file doesn't exist")

        val pool = PoolHelper.openOrCreate(genesisFile, poolName)
        logger.debug { "Pool $poolName opened for $nodeName" }

        val ledgerUser = IndyPoolLedgerUser(pool, walletUser)

        IndyUser.with(walletUser).with(ledgerUser).build()
    }

}
