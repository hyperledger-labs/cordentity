package com.luxoft.blockchainlab.corda.hyperledger.indy.contract

import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.TypeOnlyCommandData
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction
import java.security.PublicKey


class IndyCredentialDefinitionContract : Contract {
    override fun verify(tx: LedgerTransaction) {
        val incomingCommands = tx.filterCommands<Command> { true }

        for (incomingCommand in incomingCommands) {
            val signers = incomingCommand.signers.toSet()
            val command = incomingCommand.value

            when (command) {
                is Command.Create -> creation(tx, signers)
                is Command.Upgrade -> upgrade(tx, signers)
                is Command.Consume -> consummation(tx, signers)
                else -> throw IllegalArgumentException("Unrecognised command.")
            }
        }
    }

    private fun upgrade(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        // TODO: should contain 1 input and 1 output states of type IndyCredentialDefinition (different)
        // TODO: should contain 1 input and 1 output states of type IndySchema (similar)
        // TODO: input state of type IndyCredentialDefinition should have currentCredNumber == maxCredNumber
    }

    private fun creation(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        // TODO: should contain 1 input and 1 output states of type IndySchema (similar)
        // TODO: should contain 1 output state of type IndyCredentialDefinition
        // TODO: state of type IndyCredentialDefinition should have currentCredNumber == 0 and maxCredNumber > 0
    }

    private fun consummation(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        // TODO: should contain 1 input and 1 output states of type IndyCredentialDefinition (different)
        // TODO: input and output state should be similar except output.currentCredNumber - input.currentCredNumber == 1
    }

    interface Command : CommandData {

        // when we create new credential definition
        class Create : TypeOnlyCommandData(), Command

        // when we reach maxCredNumber
        class Upgrade : TypeOnlyCommandData(), Command

        // when we issue new credential
        class Consume : TypeOnlyCommandData(), Command
    }
}