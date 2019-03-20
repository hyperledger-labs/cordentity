package com.luxoft.blockchainlab.hyperledger.indy.helpers

import com.luxoft.blockchainlab.hyperledger.indy.utils.EnvironmentUtils
import org.hyperledger.indy.sdk.pool.InvalidPoolException
import org.hyperledger.indy.sdk.pool.Pool
import org.hyperledger.indy.sdk.pool.PoolJSONParameters
import org.hyperledger.indy.sdk.pool.PoolJSONParameters.OpenPoolLedgerJSONParameter
import org.hyperledger.indy.sdk.pool.PoolLedgerConfigExistsException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ExecutionException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import kotlin.math.absoluteValue


/**
 * Helps to manage ledger pool
 */
object PoolHelper {

    const val DEFAULT_POOL_NAME = "default_pool"

    init { init() }
    @JvmStatic private fun init() = Pool.setProtocolVersion(2).get()

    /**
     * Creates pool ledger files
     *
     * If pool with this [poolName] is already created, throws exception
     *
     * @param genesisFile: [File] - file with genesis transaction
     * @param poolName: [String] - name of the pool
     */
    fun createPoolIfMissing(genesisFile: File, poolName: String = DEFAULT_POOL_NAME) {
        val ledgerConfig = PoolJSONParameters.CreatePoolLedgerConfigJSONParameter(genesisFile.absolutePath)

        Pool.createPoolLedgerConfig(poolName, ledgerConfig.toJson()).get()
    }

    /**
     * Checks if pool ledger directory exists
     *
     * @param poolName: [String] - name of the pool
     */
    fun poolExists(poolName: String = DEFAULT_POOL_NAME): Boolean {
        val poolDir = Paths.get(EnvironmentUtils.getIndyHomePath(), "pool", poolName)

        return poolDir.toFile().exists()
    }

    /**
     * Checks connection with pool and returns [Pool] object if everything is ok.
     *
     * If pool with this [poolName] is already opened, throws exception
     *
     * @param poolName: [String] - name of the pool
     * @param poolConfig: [OpenPoolLedgerJSONParameter] - pool connection config (where one can define timeouts)
     * @return: [Pool] - target pool handle
     */
    fun openPoolIfCreated(
        poolName: String = DEFAULT_POOL_NAME,
        poolConfig: OpenPoolLedgerJSONParameter = OpenPoolLedgerJSONParameter(null, null)
    ): Pool {
        return Pool.openPoolLedger(poolName, poolConfig.toJson()).get()
    }
}