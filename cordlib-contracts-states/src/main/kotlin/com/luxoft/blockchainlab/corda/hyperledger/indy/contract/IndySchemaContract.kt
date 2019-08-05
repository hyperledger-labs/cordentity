package com.luxoft.blockchainlab.corda.hyperledger.indy.contract

import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndySchema
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
                else -> throw IllegalArgumentException("Unrecognised command.")
            }
        }
    }

    private fun creation(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        val schema = tx.outputsOfType<IndySchema>()

        "Should contain only one output IndySchema" using (schema.size == 1)

        "Shouldn't contain any other state" using
                (tx.outputStates.size == 1 && tx.inputStates.isEmpty() && tx.referenceStates.isEmpty())
    }

    interface Command : CommandData {
        // when we create new schema
        class Create : TypeOnlyCommandData(), Command
    }
}
