package com.luxoft.blockchainlab.corda.hyperledger.indy.flow.b2c

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.IndyCredentialContract
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyCredentialProof
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.finalizeTransaction
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.indyUser
import com.luxoft.blockchainlab.corda.hyperledger.indy.flow.whoIsNotary
import com.luxoft.blockchainlab.corda.hyperledger.indy.service.awaitFiber
import com.luxoft.blockchainlab.corda.hyperledger.indy.service.connectionService
import com.luxoft.blockchainlab.hyperledger.indy.models.ProofRequest
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.transactions.TransactionBuilder


object VerifyCredentialFlowB2C {
    @InitiatingFlow
    @StartableByRPC
    open class Verifier(
            private val identifier: String,
            private val indyPartyDID: String,
            private val proofRequest: ProofRequest
    ) : FlowLogic<Boolean>() {

        @Suspendable
        override fun call(): Boolean {

            try {
                connectionService().sendProofRequest(proofRequest, indyPartyDID)

                val proof = connectionService().receiveProof(indyPartyDID).awaitFiber()

                val usedData = indyUser().ledgerUser.retrieveDataUsedInProof(proofRequest, proof)
                val credentialProofOut =
                        IndyCredentialProof(identifier, proofRequest, proof, usedData, listOf(ourIdentity))

                if (!indyUser().verifyProofWithLedgerData(credentialProofOut.proofReq, proof))
                    throw FlowException("Proof verification failed")

                val verifyCredentialOut = StateAndContract(credentialProofOut, IndyCredentialContract::class.java.name)

                val verifyCredentialCmdType = IndyCredentialContract.Command.Verify()
                val verifyCredentialCmd =
                        Command(verifyCredentialCmdType, listOf(ourIdentity.owningKey))

                val trxBuilder = TransactionBuilder(whoIsNotary())
                        .withItems(verifyCredentialOut, verifyCredentialCmd)

                trxBuilder.toWireTransaction(serviceHub)
                        .toLedgerTransaction(serviceHub)
                        .verify()

                val selfSignedTx = serviceHub.signInitialTransaction(trxBuilder)

                // Notarise and record the transaction in both parties' vaults.
                finalizeTransaction(selfSignedTx)

                return true

            } catch (e: Exception) {
                logger.error("", e)
                return false
            }
        }
    }
}
