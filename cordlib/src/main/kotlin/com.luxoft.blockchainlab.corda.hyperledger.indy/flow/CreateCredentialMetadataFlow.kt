package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.hyperledger.indy.models.CredentialDefinitionId
import com.luxoft.blockchainlab.hyperledger.indy.models.RevocationRegistryDefinitionId
import com.luxoft.blockchainlab.hyperledger.indy.models.SchemaId
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC


object CreateCredentialMetadataFlow {
    @InitiatingFlow
    @StartableByRPC
    class Authority(
        private val schemaName: String,
        private val schemaVersion: String,
        private val schemaAttributes: List<String>,
        private val credentialLimit: Int
    ): FlowLogic<Triple<SchemaId, CredentialDefinitionId, RevocationRegistryDefinitionId>>() {
        @Suspendable
        override fun call(): Triple<SchemaId, CredentialDefinitionId, RevocationRegistryDefinitionId> {
            val schemaId = subFlow(CreateSchemaFlow.Authority(schemaName, schemaVersion, schemaAttributes))
            val credentialDefinitionId = subFlow(CreateCredentialDefinitionFlow.Authority(schemaId, true))
            val revocationRegistryDefinitionId = subFlow(CreateRevocationRegistryFlow.Authority(credentialDefinitionId, credentialLimit))

            return Triple(schemaId, credentialDefinitionId, revocationRegistryDefinitionId)
        }
    }
}
