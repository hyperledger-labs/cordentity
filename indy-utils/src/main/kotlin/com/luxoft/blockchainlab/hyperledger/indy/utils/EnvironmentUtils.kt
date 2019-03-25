package com.luxoft.blockchainlab.hyperledger.indy.utils

import org.apache.commons.io.FileUtils
import org.apache.commons.io.FileUtils.getUserDirectoryPath
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import kotlin.math.absoluteValue


internal object EnvironmentUtils {
    val testPoolIP: String
        get() {
            val testPoolIp = System.getenv("TEST_POOL_IP")
            return testPoolIp ?: "127.0.0.1"
        }

    fun getIndyHomePath(): String {
        return getUserDirectoryPath() + "/.indy_client/"
    }

    fun getIndyPoolPath(poolName: String) = getIndyHomePath() + "/pool/$poolName"

    fun getIndyWalletPath(walletName: String) = getIndyHomePath() + "/wallet/$walletName"

    fun getIndyHomePath(filename: String): String {
        return getIndyHomePath() + filename
    }

    internal fun getTmpPath(): String {
        return FileUtils.getTempDirectoryPath() + "/indy/"
    }

    internal fun getTmpPath(filename: String): String {
        return getTmpPath() + filename
    }

    @Throws(IOException::class)
    internal fun createSymbolicLink(targetPath: Path, linkPath: Path) {
        if (Files.exists(linkPath)) {
            Files.delete(linkPath)
        }
        Files.createSymbolicLink(linkPath, targetPath)
    }

}
