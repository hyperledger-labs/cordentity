package com.luxoft.blockchainlab.hyperledger.indy.helpers

import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Path


/**
 * Utilities for genesis file management
 */
object GenesisHelper {

    /**
     * Get already existing genesis file
     *
     * Checks if genesis file exists and returns [File] object.
     *
     * @param genesisPath: String - path to genesis file
     * @return: [File] - genesis file
     * @throws FileNotFoundException - if genesis file not found
     */
    @Throws(FileNotFoundException::class)
    fun getGenesis(genesisPath: String): File {
        val genesisFile = File(genesisPath)

        // making this check here for better debugging
        if (!genesisFile.exists())
            throw FileNotFoundException("Genesis file not found at $genesisPath")

        return genesisFile
    }

    /**
     * Get genesis file if it doesn't exist in system yet
     *
     * Creates all required parent directories according to [newFilePath] and rewrites genesis file with [content]
     *
     * @param content: [String] - genesis file content (genesis transaction)
     * @param newFilePath: [Path] - path to genesis file which will be created
     * @returns: [File] - genesis file
     * @throws SecurityException - if you're not allowed to create this [newFilePath]
     */
    fun getGenesis(content: String, newFilePath: Path): File {
        val parentDirectory = newFilePath.parent
        parentDirectory.toFile().mkdirs()

        val genesis = newFilePath.toFile()
        if (genesis.exists())
            genesis.delete()
        genesis.createNewFile()
        genesis.writeText(content)

        return genesis
    }
}