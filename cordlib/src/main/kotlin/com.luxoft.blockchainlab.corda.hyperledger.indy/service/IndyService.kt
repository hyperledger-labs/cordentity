package com.luxoft.blockchainlab.corda.hyperledger.indy.service

import com.luxoft.blockchainlab.hyperledger.indy.GenesisPathNotSpecifiedException
import com.luxoft.blockchainlab.hyperledger.indy.IndyUser
import com.luxoft.blockchainlab.hyperledger.indy.WalletConfig
import com.luxoft.blockchainlab.hyperledger.indy.WalletPassword
import com.luxoft.blockchainlab.hyperledger.indy.helpers.*
import mu.KotlinLogging
import net.corda.core.node.AppServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.SingletonSerializeAsToken
import org.hyperledger.indy.sdk.did.DidJSONParameters

/**
 * A Corda service for dealing with Indy Ledger infrastructure such as pools, credentials, wallets.
 *
 * The current implementation is a POC and lacks any configurability.
 * It is planed to be extended in the future version.
 */
@CordaService
class IndyService(services: AppServiceHub) : SingletonSerializeAsToken() {
    private val logger = KotlinLogging.logger {}

    val indyUser: IndyUser

    init {
        val nodeName = services.myInfo.legalIdentities.first().name.organisation

        val walletName =  ConfigHelper.getWalletName()
        val walletPassword = ConfigHelper.getWalletPassword()

        val wallet = WalletHelper.getWallet(WalletConfig(walletName), WalletPassword(walletPassword))

        logger.debug { "Wallet created for $nodeName" }

        val genesisFilePath = ConfigHelper.getGenesisPath()
        val genesisFile = GenesisHelper.getGenesis(genesisFilePath)
        val pool = PoolHelper.getPool(genesisFile)

        logger.debug { "Pool opened for $nodeName" }

        val tailsPath = "tails"

        val userRole = ConfigHelper.getRole()

        indyUser = when (userRole) {
            "trustee" -> {
                val didConfig = DidJSONParameters.CreateAndStoreMyDidJSONParameter(
                    ConfigHelper.getDid(), ConfigHelper.getSeed(), null, null
                ).toJson()

                IndyUser(pool, wallet, ConfigHelper.getDid(), didConfig, tailsPath)
            }
            else -> IndyUser(pool, wallet, null, tailsPath = tailsPath)
        }

        logger.debug { "IndyUser object created for $nodeName" }
    }
}

