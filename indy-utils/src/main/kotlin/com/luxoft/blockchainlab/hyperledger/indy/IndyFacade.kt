package com.luxoft.blockchainlab.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.ledger.LedgerService
import com.luxoft.blockchainlab.hyperledger.indy.models.CredentialDefinition
import com.luxoft.blockchainlab.hyperledger.indy.models.CredentialDefinitionId
import com.luxoft.blockchainlab.hyperledger.indy.models.RevocationRegistryInfo
import com.luxoft.blockchainlab.hyperledger.indy.models.Schema
import com.luxoft.blockchainlab.hyperledger.indy.wallet.WalletService


interface IndyFacade {
    val walletService: WalletService
    val ledgerService: LedgerService

    fun createAndStoreSchema(name: String, version: String, attributes: List<String>): Schema
    fun createAndStoreCredentialDefinition(schema: Schema, enableRevocation: Boolean): CredentialDefinition
    fun createAndStoreRevocationRegistry(
        credentialDefinitionId: CredentialDefinitionId,
        maxCredentialNumber: Int
    ): RevocationRegistryInfo
    // TODO: continue
}

abstract class IndyFacadeBuilder {
    var builderWalletService: WalletService? = null
    var builderLedgerService: LedgerService? = null

    fun with(walletService: WalletService): IndyFacadeBuilder {
        builderWalletService = walletService
        return this
    }

    fun with(ledgerService: LedgerService): IndyFacadeBuilder {
        builderLedgerService = ledgerService
        return this
    }

    abstract fun build(): IndyFacade
}
