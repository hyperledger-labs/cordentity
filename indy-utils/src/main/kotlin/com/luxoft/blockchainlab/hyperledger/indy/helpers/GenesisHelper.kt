package com.luxoft.blockchainlab.hyperledger.indy.helpers

import java.io.File


/**
 * Utilities for genesis file management
 */
object GenesisHelper {

    /**
     * Checks if genesis [path] exists
     */
    fun exists(path: File) = path.exists()

    /**
     * Creates new genesis file with [path] and [lazyContent].
     *
     * @throws FileAlreadyExistsException
     * @throws SecurityException if you don't have write access to [path]
     */
    @Throws(FileAlreadyExistsException::class, SecurityException::class)
    fun createNonExisting(path: File, lazyContent: () -> String) {
        if (exists(path))
            throw FileAlreadyExistsException(path)

        val parentDirectory = path.parentFile
        parentDirectory.mkdirs()

        path.createNewFile()
        path.writeText(lazyContent())
    }

    /**
     * Creates new or recreates existing genesis file with [path] and [lazyContent].
     *
     * @throws SecurityException if you don't have write access to [path]
     */
    @Throws(SecurityException::class)
    fun createOrTrunc(path: File, lazyContent: () -> String) {
        val parentDirectory = path.parentFile
        parentDirectory.mkdirs()

        if (exists(path))
            path.deleteRecursively()

        path.createNewFile()
        path.writeText(lazyContent())
    }
}