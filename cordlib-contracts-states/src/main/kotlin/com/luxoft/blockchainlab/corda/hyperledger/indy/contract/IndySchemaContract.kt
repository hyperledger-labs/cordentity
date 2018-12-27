package com.luxoft.blockchainlab.corda.hyperledger.indy.contract

import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.TypeOnlyCommandData
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction
import java.security.PublicKey


class IndySchemaContract : Contract {
    override fun verify(tx: LedgerTransaction) {
        val incomingCommands = tx.filterCommands<Command> { true }

        for (incomingCommand in incomingCommands) {
            val signers = incomingCommand.signers.toSet()
            val command = incomingCommand.value

            when (command) {
                is Command.Create -> creation(tx, signers)
                is Command.Consume -> consummation(tx, signers)
                else -> throw IllegalArgumentException("Unrecognised command.")
            }
        }
    }

    private fun consummation(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        // TODO: should contain 1 input and 1 output states of type IndySchema (similar)
    }

    private fun creation(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        // TODO: should contain 1 output state of type IndySchema
    }

    interface Command : CommandData {

        // when we create new schema
        class Create : TypeOnlyCommandData(), Command

        // when we issue new credential definition
        class Consume : TypeOnlyCommandData(), Command
    }
}