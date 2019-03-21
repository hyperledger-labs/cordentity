package com.luxoft.blockchainlab.hyperledger.indy.helpers

import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import com.luxoft.blockchainlab.hyperledger.indy.utils.getRootCause
import org.hyperledger.indy.sdk.wallet.Wallet
import org.hyperledger.indy.sdk.wallet.WalletAlreadyOpenedException
import org.hyperledger.indy.sdk.wallet.WalletExistsException
import java.util.concurrent.ExecutionException

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
    fun openWallet(config: WalletConfig, password: WalletPassword): Wallet {
        val walletConfigJson = SerializationUtils.anyToJSON(config)
        val walletPasswordJson = SerializationUtils.anyToJSON(password)

        try {
            Wallet.createWallet(walletConfigJson, walletPasswordJson).get()
        } catch (ex: Exception) {
            if (getRootCause(ex) !is WalletExistsException) throw ex
        }

        return Wallet.openWallet(walletConfigJson, walletPasswordJson).get()
    }

    /**
     * Shortcut to [openWallet]
     *
     * @param name: [String] - wallet name
     * @param password: [String] - wallet password
     * @return: [Wallet] - target wallet
     */
    fun openWallet(name: String, password: String)
        = WalletHelper.openWallet(WalletConfig(name), WalletPassword(password))
}

/**
 * {
 *     "id": string, Identifier of the wallet. Configured storage uses this identifier to lookup exact wallet data placement.
 *
 *     "storage_type": optional<string>, Type of the wallet storage. Defaults to 'default'.
 *     'Default' storage type allows to store wallet data in the local file.
 *     Custom storage types can be registered with indy_register_wallet_storage call.
 *
 *     "storage_config": optional<object>, Storage configuration json. Storage type defines set of supported keys.
 *     Can be optional if storage supports default configuration.
 *
 *     For 'default' storage type configuration is:
 *     {
 *         "path": optional<string>, Path to the directory with wallet files.
 *         Defaults to $HOME/.indy_client/wallets.
 *         Wallet will be stored in the file {path}/{id}/sqlite.db
 *     }
 * }
 */
data class WalletConfig(
    val id: String,
    val storageType: String = "default",
    val storageConfig: StorageConfig? = null
)

/**
 * Allows to define custom wallet storage path
 */
data class StorageConfig(val path: String)

/**
 * Represents wallet auth key
 */
data class WalletPassword(val key: String)