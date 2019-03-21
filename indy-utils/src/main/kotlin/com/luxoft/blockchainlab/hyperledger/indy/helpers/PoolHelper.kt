package com.luxoft.blockchainlab.hyperledger.indy.helpers

import com.luxoft.blockchainlab.hyperledger.indy.utils.EnvironmentUtils
import org.hyperledger.indy.sdk.pool.Pool
import org.hyperledger.indy.sdk.pool.PoolJSONParameters
import org.hyperledger.indy.sdk.pool.PoolJSONParameters.OpenPoolLedgerJSONParameter
import org.hyperledger.indy.sdk.pool.PoolLedgerConfigExistsException
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ExecutionException


/**
 * Helps to manage ledger pool
 */
object PoolHelper {

    const val DEFAULT_POOL_NAME = "default_pool"

    init {
        Pool.setProtocolVersion(2).get()
    }

    /**
     * Checks if pool ledger with [poolName] exists
     */
    fun exists(poolName: String): Boolean {
        val poolDir = EnvironmentUtils.getIndyPoolPath(poolName)

        return File(poolDir).exists()
    }

    /**
     * Creates pool ledger with [genesisFile] and [poolName]
     *
     * @param genesisFile: [File] - file with genesis transaction
     * @param poolName: [String] - name of the pool
     *
     * @throws ExecutionException with cause [PoolLedgerConfigExistsException]
     */
    @Throws(ExecutionException::class)
    fun createNonExisting(genesisFile: File, poolName: String = DEFAULT_POOL_NAME) {
        val ledgerConfig = PoolJSONParameters.CreatePoolLedgerConfigJSONParameter(genesisFile.absolutePath)

        Pool.createPoolLedgerConfig(poolName, ledgerConfig.toJson()).get()
    }

    /**
     * Creates new or recreates existing pool ledger files with [genesisFile] and [poolName].
     */
    @Throws(ExecutionException::class)
    fun createOrTrunc(genesisFile: File, poolName: String = DEFAULT_POOL_NAME) {
        if (exists(poolName))
            File(EnvironmentUtils.getIndyPoolPath(poolName)).deleteRecursively()

        createNonExisting(genesisFile, poolName)
    }

    /**
     * Opens existing pool ledger with [poolName] and checks connection using [poolConfig]
     */
    @Throws(FileNotFoundException::class, ExecutionException::class)
    fun openExisting(
        poolName: String = DEFAULT_POOL_NAME,
        poolConfig: OpenPoolLedgerJSONParameter = OpenPoolLedgerJSONParameter(null, null)
    ): Pool {
        if (!exists(poolName))
            throw FileNotFoundException("Pool files ${EnvironmentUtils.getIndyPoolPath(poolName)} don't exist")

        return Pool.openPoolLedger(poolName, poolConfig.toJson()).get()
    }

    /**
     * Opens existing pool ledger with [poolName] and checks connection using [poolConfig] or creates new pool ledger
     * with [genesisFile] and [poolName]
     */
    @Throws(ExecutionException::class)
    fun openOrCreate(
        genesisFile: File,
        poolName: String = DEFAULT_POOL_NAME,
        poolConfig: OpenPoolLedgerJSONParameter = OpenPoolLedgerJSONParameter(null, null)
    ): Pool {
        if (!exists(poolName))
            createNonExisting(genesisFile, poolName)

        return openExisting(poolName, poolConfig)
    }
}