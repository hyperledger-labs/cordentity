package com.luxoft.blockchainlab.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils
import mu.KotlinLogging
import org.hyperledger.indy.sdk.ledger.LedgerInvalidTransactionException
import org.hyperledger.indy.sdk.ledger.LedgerResults
import java.util.concurrent.CompletableFuture

open class IndyWrapperException(msg: String) : IllegalArgumentException(msg)

class ArtifactDoesntExist(msg: String = "") : IndyWrapperException("Artifact $msg doesn't exist on the public ledger")
class ArtifactRequestFailed(msg: String) : IndyWrapperException("Request to public Indy ledger has failed: $msg")

enum class Status { REJECT, REPLY }
data class IndyOpCode(val op: Status, val result: Any?)

val logger = KotlinLogging.logger {}

fun handleIndyError(execResult: String) {
    val res = SerializationUtils.jSONToAny<IndyOpCode>(execResult)
    when (res.op) {
        Status.REJECT -> throw ArtifactRequestFailed("Request has been rejected: ${res.result}")
        Status.REPLY -> logger.info("Request successfully completed: ${res.result}")
    }
}

typealias IndyParser = (msg: String) -> CompletableFuture<LedgerResults.ParseResponseResult>

inline fun <reified T : Any> extractIndyResult(execResult: String, indyParser: IndyParser): T {
    handleIndyError(execResult)

    try {
        val payload = indyParser(execResult).get()
        val output = SerializationUtils.jSONToAny(payload.objectJson!!, T::class.java)

        logger.info("Payload successfully parsed ${payload.id}:${payload.objectJson}")
        return output

    } catch (e: Exception) {
        logger.info("Indy parsing has failed", e)
        if (e.cause is LedgerInvalidTransactionException) throw ArtifactDoesntExist()
        throw ArtifactRequestFailed("Can not parse the response: " + e.message)
    }
}