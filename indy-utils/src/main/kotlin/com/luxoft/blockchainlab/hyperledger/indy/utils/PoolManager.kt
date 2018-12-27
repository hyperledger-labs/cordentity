package com.luxoft.blockchainlab.hyperledger.indy.utils

import org.hyperledger.indy.sdk.pool.Pool
import org.hyperledger.indy.sdk.pool.PoolJSONParameters
import org.hyperledger.indy.sdk.pool.PoolJSONParameters.OpenPoolLedgerJSONParameter
import org.hyperledger.indy.sdk.pool.PoolLedgerConfigExistsException
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException

object PoolManager {

    const val DEFAULT_POOL_NAME = "default_pool"

    //ONLY FOR IN PROJECT TESTS
    val TEST_GENESIS_FILE_PATH by lazy { javaClass.classLoader.getResource("docker_pool_transactions_genesis.txt").file }
    val TEST_GENESIS_FILE by lazy { File(TEST_GENESIS_FILE_PATH) }

    private val openIndyPools = ConcurrentHashMap<String, Pool>()

    fun openIndyPool(
        genesisFile: File,
        poolName: String = DEFAULT_POOL_NAME,
        poolConfig: OpenPoolLedgerJSONParameter = OpenPoolLedgerJSONParameter(null, null)
    ): Pool {
        val pool = openIndyPools.getOrPut(poolName) {
            val ledgerConfig = PoolJSONParameters.CreatePoolLedgerConfigJSONParameter(genesisFile.absolutePath)

            try {
                Pool.createPoolLedgerConfig(poolName, ledgerConfig.toJson()).get()
            } catch (e: ExecutionException) {
                if (e.cause !is PoolLedgerConfigExistsException) throw e
                // ok
            }

            Pool.setProtocolVersion(2).get()

            Pool.openPoolLedger(poolName, poolConfig.toJson()).get()
        }

        return pool
    }
}