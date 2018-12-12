package com.luxoft.blockchainlab.hyperledger.indy.utils

import org.apache.commons.io.FileUtils
import java.io.File
import java.io.IOException

object StorageUtils {

    @Throws(IOException::class)
    private fun cleanDirectory(path: File) {
        if (path.isDirectory) {
            FileUtils.cleanDirectory(path)
        }
    }

    @Throws(IOException::class)
    fun cleanupStorage() {

        val tmpDir = File(EnvironmentUtils.getTmpPath())
        val homeDir = File(EnvironmentUtils.getIndyHomePath())

        cleanDirectory(tmpDir)
        cleanDirectory(homeDir)
    }

}