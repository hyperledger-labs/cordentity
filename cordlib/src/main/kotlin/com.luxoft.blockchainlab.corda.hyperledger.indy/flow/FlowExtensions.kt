package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.service.IndyService
import com.luxoft.blockchainlab.hyperledger.indy.SsiUser
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.node.NodeInfo
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.ProgressTracker
import java.security.PublicKey


/**
 * Extension methods to reduce boilerplate code in Indy flows
 */

fun FlowLogic<Any>.whoIs(x509: CordaX500Name): Party {
    return serviceHub.identityService.wellKnownPartyFromX500Name(x509)!!
}

fun FlowLogic<Any>.whoIs(publicKey: PublicKey): Party {
    return serviceHub.identityService.partyFromKey(publicKey)!!
}

fun FlowLogic<Any>.whoIsNotary(): Party {
    return serviceHub.networkMapCache.notaryIdentities.single()
}

fun FlowLogic<Any>.indyUser(): SsiUser {
    return serviceHub.cordaService(IndyService::class.java).indyUser
}

fun FlowLogic<Any>.tailsReader() = serviceHub.cordaService(IndyService::class.java).tailsReader
fun FlowLogic<Any>.tailsWriter() = serviceHub.cordaService(IndyService::class.java).tailsWriter

fun NodeInfo.name() = legalIdentities.first().name
fun FlowLogic<Any>.me() = serviceHub.myInfo.legalIdentities.first()

@Suspendable
fun FlowLogic<Any>.finalizeTransaction(
    stx: SignedTransaction, existingSessions: Collection<FlowSession> = emptyList()
): SignedTransaction {
    return finalizeTransaction(stx, FinalityFlow.tracker(), existingSessions)
}

@Suspendable
fun FlowLogic<Any>.finalizeTransaction(
    stx: SignedTransaction, progressTracker: ProgressTracker, existingSessions: Collection<FlowSession> = emptyList()
): SignedTransaction {

    val ledgerTransaction = stx.toLedgerTransaction(serviceHub, checkSufficientSignatures = false)
    val myKeys = serviceHub.keyManagementService.keys

    val existingOtherSessions = existingSessions.filterNot { it.counterparty.owningKey in myKeys }
    val existingOtherSessionsParticipantKeys = existingOtherSessions.map { it.counterparty.owningKey }.toSet()

    val skipKeys = myKeys + existingOtherSessionsParticipantKeys

    val allParticipants =
        ledgerTransaction.outputStates.flatMap { it.participants } +
                ledgerTransaction.inputStates.flatMap { it.participants } +
                ledgerTransaction.commands.flatMap { it.signers.map { whoIs(it) } }

    val otherParticipants = allParticipants.filterNot { it.owningKey in skipKeys }

    val processedParties = mutableSetOf<CordaX500Name>()
    val sessions = otherParticipants.mapNotNull {
        val party = serviceHub.identityService.wellKnownPartyFromAnonymous(it)
            ?: throw IllegalArgumentException("Could not find Party for $it")
        if (processedParties.add(party.name))
            initiateFlow(party)
        else
            null
    }

    return subFlow(FinalityFlow(stx, sessions + existingOtherSessions, progressTracker))
}
