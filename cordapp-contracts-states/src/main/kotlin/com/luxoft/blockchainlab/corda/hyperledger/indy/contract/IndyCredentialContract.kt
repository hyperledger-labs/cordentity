package com.luxoft.blockchainlab.corda.hyperledger.indy.contract

import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyCredentialProof
import com.luxoft.blockchainlab.hyperledger.indy.IndyUser
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.TypeOnlyCommandData
import net.corda.core.contracts.requireThat
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.LedgerTransaction
import java.security.PublicKey

/**
 * Example contract with credential verification. Use responsibly
 * as Corda will probably remove JNI support (i.e. Libindy calls)
 * in near future in deterministic JVM
 */
class IndyCredentialContract : Contract {

    override fun verify(tx: LedgerTransaction) {
        val incomingCommands = tx.filterCommands<Command> { true }

        for (incomingCommand in incomingCommands) {
            val signers = incomingCommand.signers.toSet()
            val command = incomingCommand.value

            when (command) {
                is Command.Verify -> verification(tx, signers, command.expectedAttrs)
                is Command.Issue -> creation(tx, signers)
                is Command.Revoke -> revocation(tx, signers)
                else -> throw IllegalArgumentException("Unrecognised command.")
            }
        }
    }

    private fun revocation(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        // TODO: should contain 1 input state of type IndyCredential
    }

    private fun verification(tx: LedgerTransaction, signers: Set<PublicKey>, expectedAttrs: List<ExpectedAttr>) =
        requireThat {

            "No inputs should be consumed when creating the proof." using (tx.inputStates.isEmpty())
            "Only one Proof should be created per verification session." using (tx.outputStates.size == 1)

            val indyProof = tx.outputsOfType<IndyCredentialProof>().singleOrNull()
                ?: throw IllegalArgumentException("Invalid type of output")

            "All of the participants must be signers." using (signers.containsAll(indyProof.participants.map { it.owningKey }))

            // TODO: this is unnecessary, 'cause only the caller VerifyProofFlow is interested in proof validity
            // TODO: removing this line will make cordentity compatible with sandboxed JVM
            "IndyCredential should be verified." using (IndyUser.verifyProof(
                indyProof.proofReq,
                indyProof.proof,
                indyProof.usedData
            ))

            expectedAttrs.forEach {
                "Proof provided for invalid value." using indyProof.proof.isAttributeExists(it.value)
            }
        }

    private fun creation(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        // TODO: should contain 1 input and 1 output states of type IndyCredentialDefinition
        // TODO: should contain 1 output state of type IndyCredential
    }

    @CordaSerializable
    data class ExpectedAttr(val name: String, val value: String)

    interface Command : CommandData {
        class Issue : TypeOnlyCommandData(), Command
        data class Verify(val expectedAttrs: List<ExpectedAttr>) : Command
        class Revoke : TypeOnlyCommandData(), Command
    }
}