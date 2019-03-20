package com.luxoft.blockchainlab.hyperledger.indy.helpers

import com.luxoft.blockchainlab.hyperledger.indy.models.BlobStorageHandler
import com.luxoft.blockchainlab.hyperledger.indy.models.TailsConfig
import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import org.hyperledger.indy.sdk.blob_storage.BlobStorageReader
import org.hyperledger.indy.sdk.blob_storage.BlobStorageWriter
import java.io.File

/**
 * Helps to manage tails file
 */
object TailsHelper {
    /**
     * Returns [BlobStorageHandler] for some [tailsPath]
     *
     * Creates missed directories in path
     *
     * @param [tailsPath]: [String] - path to tails file directory (actual tails file will be named by its hash)
     * @return: [BlobStorageHandler]
     */
    fun getTailsHandler(tailsPath: String): BlobStorageHandler {
        val tailsConfig = SerializationUtils.anyToJSON(TailsConfig(tailsPath))

        File(tailsPath).mkdirs()

        val reader = BlobStorageReader.openReader("default", tailsConfig).get()
        val writer = BlobStorageWriter.openWriter("default", tailsConfig).get()

        return BlobStorageHandler(reader, writer)
    }
}