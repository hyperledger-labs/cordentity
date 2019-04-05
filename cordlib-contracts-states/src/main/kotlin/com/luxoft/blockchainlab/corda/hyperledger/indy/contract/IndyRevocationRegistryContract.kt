package com.luxoft.blockchainlab.corda.hyperledger.indy.contract

import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.TypeOnlyCommandData
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction
import java.security.PublicKey


class IndyRevocationRegistryContract : Contract {
    override fun verify(tx: LedgerTransaction) {
        val incomingCommands = tx.filterCommands<IndyRevocationRegistryContract.Command> { true }

        for (incomingCommand in incomingCommands) {
            val signers = incomingCommand.signers.toSet()
            val command = incomingCommand.value

            when (command) {
                is IndyRevocationRegistryContract.Command.Create -> creation(tx, signers)
                is IndyRevocationRegistryContract.Command.Upgrade -> upgrade(tx, signers)
                is IndyRevocationRegistryContract.Command.Issue -> consummation(tx, signers)
                else -> throw IllegalArgumentException("Unrecognised command.")
            }
        }
    }

    private fun upgrade(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        // TODO: should contain 1 input and 1 output states of type IndyRevocationRegistry (different)
        // TODO: input state of type IndyRevocationRegistry should have currentCredNumber == maxCredNumber
    }

    private fun creation(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        // TODO: should contain 1 input and 1 output states of type IndyCredentialDefinition (similar)
        // TODO: should contain 1 output state of type IndyRevocationRegistry
        // TODO: state of type IndyRevocationRegistry should have currentCredNumber == 0 and maxCredNumber > 0
    }

    private fun consummation(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        // TODO: should contain 1 input and 1 output states of type IndyRevocationRegistry (different)
        // TODO: input and output state should be similar except output.currentCredNumber - input.currentCredNumber == 1
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
