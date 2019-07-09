package com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2b

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.IndyCredentialContract
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyCredentialProof
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.*
import com.luxoft.blockchainlab.hyperledger.indy.models.ProofInfo
import com.luxoft.blockchainlab.hyperledger.indy.models.ProofRequest
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.unwrap
import java.util.*

/**
 * Flows to verify predicates on attributes
 * */
object VerifyCredentialFlowB2B {


    /**
     * A flow to verify some conditions from [proofRequest]
     *
     * @param proverName        node that will prove the credentials
     * @param proofRequest      proof request - use [proofRequest] DSL builder to get it
     *
     * @returns [Pair] of [String] to [Boolean] - where [first] is proof id, if verification succeed, [null] otherwise,
     *  and [second] is verification status
     * */
    @InitiatingFlow
    @StartableByRPC
    open class Verifier(
        private val proverName: CordaX500Name,
        private val proofRequest: ProofRequest
    ) : FlowLogic<Pair<String?, Boolean>>() {

        @Suspendable
        override fun call(): Pair<String?, Boolean> {
            try {
                val prover: Party = whoIs(proverName)
                val flowSession: FlowSession = initiateFlow(prover)

                val id = UUID.randomUUID().toString()

                val verifyCredentialOut = flowSession.sendAndReceive<ProofInfo>(proofRequest).unwrap { proof ->
                    val usedData = indyUser().ledgerUser.retrieveDataUsedInProof(proofRequest, proof)
                    val credentialProofOut =
                        IndyCredentialProof(id, proofRequest, proof, usedData, listOf(ourIdentity, prover))

                    if (!indyUser().verifyProofWithLedgerData(credentialProofOut.proofReq, proof))
                        throw FlowException("Proof verification failed")

                    StateAndContract(credentialProofOut, IndyCredentialContract::class.java.name)
                }

                val verifyCredentialCmdType = IndyCredentialContract.Command.Verify()
                val verifyCredentialCmd =
                    Command(verifyCredentialCmdType, listOf(ourIdentity.owningKey, prover.owningKey))

                val trxBuilder = TransactionBuilder(whoIsNotary())
                    .withItems(verifyCredentialOut, verifyCredentialCmd)

                trxBuilder.toWireTransaction(serviceHub)
                    .toLedgerTransaction(serviceHub)
                    .verify()

                val selfSignedTx = serviceHub.signInitialTransaction(trxBuilder)
                val signedTrx = subFlow(CollectSignaturesFlow(selfSignedTx, listOf(flowSession)))

                // Notarise and record the transaction in both parties' vaults.
                finalizeTransaction(signedTrx, listOf(flowSession))

                return Pair(id, true)

            } catch (e: Exception) {
                logger.error("", e)
                return Pair(null, false)
            }
        }
    }

    @InitiatedBy(VerifyCredentialFlowB2B.Verifier::class)
    open class Prover(private val flowSession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            try {
                val indyProofRequest = flowSession.receive<ProofRequest>().unwrap { it }
                val proof = indyUser().createProofFromLedgerData(indyProofRequest)

                flowSession.send(proof)

                val flow = object : SignTransactionFlow(flowSession) {
                    // TODO: Add some checks here.
                    override fun checkTransaction(stx: SignedTransaction) = Unit
                }

                subFlow(flow)
                if (flowSession.counterparty != me())
                    subFlow(ReceiveFinalityFlow(flowSession))

            } catch (e: Exception) {
                logger.error("", e)
                throw FlowException(e.message)
            }
        }
    }
}
