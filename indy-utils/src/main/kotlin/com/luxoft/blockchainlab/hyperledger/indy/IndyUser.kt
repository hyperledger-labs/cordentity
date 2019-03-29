package com.luxoft.blockchainlab.hyperledger.indy

import com.luxoft.blockchainlab.hyperledger.indy.ledger.IndyPoolLedgerService
import com.luxoft.blockchainlab.hyperledger.indy.wallet.WalletService


/**
 * The central class that encapsulates Indy SDK calls and keeps the corresponding state.
 *
 * Create one instance per each server node that deals with Indy Ledger.
 */
open class IndyUser(
    override val walletService: WalletService,
    override val ledgerService: IndyPoolLedgerService
) : IndyFacade {

    companion object : IndyFacadeBuilder() {
        override fun build(): IndyFacade {
            if (builderLedgerService == null || builderWalletService == null)
                throw RuntimeException("WalletService and LedgerService should be specified")

            return IndyUser(builderWalletService!!, builderLedgerService!!)
        }
    }

}