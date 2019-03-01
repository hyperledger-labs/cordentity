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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.nio.file.Files.createSymbolicLink
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import kotlin.math.absoluteValue


/**
 * Helps to manage ledger pool
 */
object PoolHelper {

    const val DEFAULT_POOL_NAME = "default_pool"

    //ONLY FOR IN PROJECT TESTS
    val TEST_GENESIS_FILE_PATH by lazy {
        javaClass.classLoader.getResource("docker_pool_transactions_genesis.txt").file
    }
    val TEST_GENESIS_FILE by lazy { File(TEST_GENESIS_FILE_PATH) }

    /**
     * Creates (or not if exists) pool ledger files and returns [Pool] object
     *
     * If pool already opened, creates a symlink with random name that targets needed pool
     *
     * @param genesisFile: [File] - file with genesis transaction
     * @param poolName: [String] - name of pool
     * @param poolConfig: [OpenPoolLedgerJSONParameter] - pool config (when you create new pool)
     * @return: [Pool] - target pool handle
     */
    fun getPool(
        genesisFile: File,
        poolName: String = DEFAULT_POOL_NAME,
        poolConfig: OpenPoolLedgerJSONParameter = OpenPoolLedgerJSONParameter(null, null)
    ): Pool {

        val ledgerConfig = PoolJSONParameters.CreatePoolLedgerConfigJSONParameter(genesisFile.absolutePath)

        try {
            Pool.createPoolLedgerConfig(poolName, ledgerConfig.toJson()).get()
        } catch (e: ExecutionException) {
            if (e.cause !is PoolLedgerConfigExistsException) throw e
        }

        Pool.setProtocolVersion(2).get()

        return try {
            Pool.openPoolLedger(poolName, poolConfig.toJson()).get()
        } catch (ex: ExecutionException) {
            if (ex.cause !is InvalidPoolException) throw ex

            val linkName = symlinkPool(poolName)

            Pool.openPoolLedger(linkName, poolConfig.toJson()).get()
        }
    }

    private fun symlinkPool(poolName: String): String {
        val poolDir = Paths.get(EnvironmentUtils.getIndyHomePath(), "pool")
        val linkName = poolName + Random().nextLong().absoluteValue
        val targetDir = poolDir.resolve(poolName)
        val targetGenesisPath = targetDir.resolve("$poolName.txn")
        val targetConfigPath = targetDir.resolve("config.json")

        val linkDir = poolDir.resolve(linkName)
        linkDir.toFile().mkdir()
        val linkGenesisPath = linkDir.resolve("$linkName.txn")
        val linkConfigPath = linkDir.resolve("config.json")

        createSymbolicLink(targetGenesisPath, linkGenesisPath)
        createSymbolicLink(targetConfigPath, linkConfigPath)

        return linkName
    }

    @Throws(IOException::class)
    private fun createSymbolicLink(targetPath: Path, linkPath: Path) {
        if (Files.exists(linkPath)) {
            Files.delete(linkPath)
        }
        Files.createSymbolicLink(linkPath, targetPath)
    }
}