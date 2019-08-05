package com.luxoft.blockchainlab.corda.hyperledger.indy.contract

import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyCredentialDefinition
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyRevocationRegistryDefinition
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.TypeOnlyCommandData
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction
import java.security.PublicKey


class IndyRevocationRegistryContract : Contract {
    override fun verify(tx: LedgerTransaction) {
        val incomingCommands = tx.filterCommands<Command> { true }

        for (incomingCommand in incomingCommands) {
            val signers = incomingCommand.signers.toSet()
            val command = incomingCommand.value

            when (command) {
                is Command.Create -> creation(tx, signers)
                is Command.Upgrade -> upgrade(tx, signers)
                is Command.Issue -> consummation(tx, signers)
                else -> throw IllegalArgumentException("Unrecognised command.")
            }
        }
    }

    private fun upgrade(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        val revRegIn = tx.inputsOfType<IndyRevocationRegistryDefinition>()
        val revRegOut = tx.outputsOfType<IndyRevocationRegistryDefinition>()

        "Should contain different input and output IndyRevocationRegistryDefinition states" using
                (revRegIn.size == 1 && revRegOut.size == 1 && revRegIn.first().id != revRegOut.first().id)

        "IndyRevocationRegistryDefinition input state should be valid (credNumber == maxCredNumber)" using
                (revRegIn.first().currentCredNumber == revRegIn.first().credentialsLimit)

        "IndyRevocationRegistryDefinition output state should be valid (credNumber == 0, maxCredNumber > 1)" using
                (revRegOut.first().currentCredNumber == 0 && revRegOut.first().credentialsLimit > 1)
    }

    private fun creation(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        val revReg = tx.outputsOfType<IndyRevocationRegistryDefinition>()
        val credDef = tx.referenceInputsOfType<IndyCredentialDefinition>()

        "Should contain exact one output IndyRevocationRegistryDefinition state" using (revReg.size == 1)

        "IndyRevocationRegistryDefinition state should be valid (credNumber == 0, maxCredNumber > 1)" using
                (revReg.first().currentCredNumber == 0 && revReg.first().credentialsLimit > 1)

        "Should contain exact one referent IndyCredentialDefinition state" using (credDef.size == 1)

        "Shouldn't contain any other state" using
                (tx.inputStates.isEmpty() && tx.outputStates.size == 1 && tx.referenceStates.size == 1)
    }

    private fun consummation(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        val revRegIn = tx.inputsOfType<IndyRevocationRegistryDefinition>()
        val revRegOut = tx.outputsOfType<IndyRevocationRegistryDefinition>()

        "Should contain same one input and output IndyRevocationRegistryDefinition state" using
                (revRegIn.size == 1 && revRegOut.size == 1 && revRegIn.first().id == revRegOut.first().id)

        "IndyRevocationRegistryDefinition states should be valid (output.currentCredNumber - input.currentCredNumber == 1)" using
                (revRegOut.first().currentCredNumber - revRegIn.first().currentCredNumber == 1)
    }

    interface Command : CommandData {
        // when we create new revocation registry
        class Create : TypeOnlyCommandData(), Command

        // when we reach maxCredNumber
        class Upgrade : TypeOnlyCommandData(), Command

        // when we issue new credential
        class Issue : TypeOnlyCommandData(), Command
    }
}
