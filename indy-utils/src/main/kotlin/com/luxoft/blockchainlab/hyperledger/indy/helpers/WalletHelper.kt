package com.luxoft.blockchainlab.hyperledger.indy.helpers

import com.luxoft.blockchainlab.hyperledger.indy.utils.EnvironmentUtils
import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import org.hyperledger.indy.sdk.wallet.Wallet
import org.hyperledger.indy.sdk.wallet.WalletAlreadyOpenedException
import org.hyperledger.indy.sdk.wallet.WalletExistsException
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ExecutionException

/**
 * Helps to manage wallets
 */
object WalletHelper {
    /**
     * Checks if wallet with [walletName] exists
     */
    fun exists(walletName: String): Boolean {
        val walletDir = EnvironmentUtils.getIndyWalletPath(walletName)

        return File(walletDir).exists()
    }

    /**
     * Creates wallet with [config] and [password]
     *
     * @param config: [WalletConfig] - wallet configuration
     * @param password: [WalletPassword] - wallet credentials
     *
     * @throws ExecutionException with cause [WalletExistsException]
     */
    @Throws(ExecutionException::class)
    fun createNonExisting(config: WalletConfig, password: WalletPassword) {
        val walletConfigJson = SerializationUtils.anyToJSON(config)
        val walletPasswordJson = SerializationUtils.anyToJSON(password)

        Wallet.createWallet(walletConfigJson, walletPasswordJson).get()
    }

    /**
     * Shortcut to [createNonExisting]
     *
     * @param walletName: [String]
     * @param walletPassword: [String]
     *
     * @throws ExecutionException with cause [WalletExistsException]
     */
    fun createNonExisting(walletName: String, walletPassword: String) {
        createNonExisting(WalletConfig(walletName), WalletPassword(walletPassword))
    }

    /**
     * Creates new or recreates existing wallet with [config] and [password].
     *
     * @param config: [WalletConfig] - wallet configuration
     * @param password: [WalletPassword] - wallet credentials
     */
    @Throws(ExecutionException::class)
    fun createOrTrunc(config: WalletConfig, password: WalletPassword) {
        if (exists(config.id))
            File(EnvironmentUtils.getIndyWalletPath(config.id)).deleteRecursively()

        createNonExisting(config, password)
    }

    /**
     * Shortcut to [createOrTrunc]
     *
     * @param walletName: [String]
     * @param walletPassword: [String]
     */
    fun createOrTrunc(walletName: String, walletPassword: String) {
        createOrTrunc(WalletConfig(walletName), WalletPassword(walletPassword))
    }

    /**
     * Opens existing wallet with [config] and [password]
     *
     * @param config: [WalletConfig] - wallet configuration
     * @param password: [WalletPassword] - wallet credentials
     *
     * @throws ExecutionException with cause [WalletAlreadyOpenedException]
     */
    @Throws(FileNotFoundException::class, ExecutionException::class)
    fun openExisting(config: WalletConfig, password: WalletPassword): Wallet {
        if (!exists(config.id))
            throw FileNotFoundException("Wallet ${EnvironmentUtils.getIndyWalletPath(config.id)} doesn't exist")

        val walletConfigJson = SerializationUtils.anyToJSON(config)
        val walletPasswordJson = SerializationUtils.anyToJSON(password)

        return Wallet.openWallet(walletConfigJson, walletPasswordJson).get()
    }

    /**
     * Shortcut to [openExisting]
     *
     * @param walletName: [String]
     * @param walletPassword: [String]
     *
     * @throws ExecutionException with cause [WalletAlreadyOpenedException]
     */
    fun openExisting(walletName: String, walletPassword: String): Wallet
            = openExisting(WalletConfig(walletName), WalletPassword(walletPassword))

    /**
     * Opens existing wallet or creates new then opens it using [config] and [password]
     *
     * @param config: [WalletConfig] - wallet configuration
     * @param password: [WalletPassword] - wallet credentials
     *
     * @throws ExecutionException with cause [WalletAlreadyOpenedException]
     */
    @Throws(ExecutionException::class)
    fun openOrCreate(config: WalletConfig, password: WalletPassword): Wallet {
        if (!exists(config.id))
            createNonExisting(config, password)

        return openExisting(config, password)
    }

    /**
     * Shortcut to [openOrCreate]
     *
     * @param walletName: [String]
     * @param walletPassword: [String]
     *
     * @throws ExecutionException with cause [WalletAlreadyOpenedException]
     */
    fun openOrCreate(walletName: String, walletPassword: String): Wallet
        = openOrCreate(WalletConfig(walletName), WalletPassword(walletPassword))
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