package com.luxoft.blockchainlab.corda.hyperledger.indy.contract

import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyCredentialDefinition
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndySchema
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
                else -> throw IllegalArgumentException("Unrecognised command.")
            }
        }
    }

    private fun creation(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        val credDef = tx.outputsOfType<IndyCredentialDefinition>()
        val schema = tx.referenceInputsOfType<IndySchema>()

        "Should contain one output IndyCredentialDefinition state" using (credDef.size == 1)
        "Should contain one referent IndySchema state" using (schema.size == 1)
        "Shouldn't contain any other state" using
                (tx.inputStates.isEmpty() && tx.outputStates.size == 1 && tx.referenceStates.size == 1)
    }

    interface Command : CommandData {
        // when we create new credential definition
        class Create : TypeOnlyCommandData(), Command
    }
}
