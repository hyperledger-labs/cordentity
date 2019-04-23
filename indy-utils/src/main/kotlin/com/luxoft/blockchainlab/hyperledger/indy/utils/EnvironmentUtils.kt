package com.luxoft.blockchainlab.hyperledger.indy.utils

import org.apache.commons.io.FileUtils
import org.apache.commons.io.FileUtils.getUserDirectoryPath
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path


internal object EnvironmentUtils {
    val testPoolIP: String
        get() {
            val testPoolIp = System.getenv("TEST_POOL_IP")
            return testPoolIp ?: "127.0.0.1"
        }

    fun getIndyHomePath(): String {
        return System.getProperty("INDY_HOME") ?: "${getUserDirectoryPath()}/.indy_client"
    }

    fun getIndyPoolPath(): String {
        return System.getProperty("INDY_POOL_PATH") ?: "${getUserDirectoryPath()}/.indy_client"
    }

    fun getIndyPoolPath(poolName: String) = getIndyPoolPath() + "/pool/$poolName"

    fun getIndyWalletPath(walletName: String) = getIndyHomePath() + "/wallet/$walletName"

    fun getIndyHomePath(filename: String): String {
        return "${getIndyHomePath()}/$filename"
    }

    internal fun getTmpPath(): String {
        return System.getProperty("INDY_TMP") ?: FileUtils.getTempDirectoryPath() + "/indy"
    }

    internal fun getTmpPath(filename: String): String {
        return "${getTmpPath()}/$filename"
    }

    @Throws(IOException::class)
    internal fun createSymbolicLink(targetPath: Path, linkPath: Path) {
        if (Files.exists(linkPath)) {
            Files.delete(linkPath)
        }
        Files.createSymbolicLink(linkPath, targetPath)
    }

}
