package com.luxoft.blockchainlab.hyperledger.indy.helpers

import com.luxoft.blockchainlab.hyperledger.indy.WalletConfig
import com.luxoft.blockchainlab.hyperledger.indy.WalletPassword
import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import com.luxoft.blockchainlab.hyperledger.indy.utils.getRootCause
import org.hyperledger.indy.sdk.wallet.Wallet
import org.hyperledger.indy.sdk.wallet.WalletExistsException
import java.util.concurrent.ConcurrentHashMap

/**
 * Helps to manage wallets
 */
object WalletHelper {
    /**
     * Creates (or not if exists) all needed wallet files and returns [Wallet]
     *
     * @param config: [WalletConfig] - wallet configuration
     * @param password: [WalletPassword] - wallet credentials
     * @return: [Wallet] - target wallet
     */
    fun getWallet(config: WalletConfig, password: WalletPassword): Wallet {
        val walletConfigJson = SerializationUtils.anyToJSON(config)
        val walletPasswordJson = SerializationUtils.anyToJSON(password)

        try {
            Wallet.createWallet(walletConfigJson, walletPasswordJson).get()
        } catch (ex: Exception) {
            if (getRootCause(ex) !is WalletExistsException) throw ex
        }

        return Wallet.openWallet(walletConfigJson, walletPasswordJson).get()
    }
}