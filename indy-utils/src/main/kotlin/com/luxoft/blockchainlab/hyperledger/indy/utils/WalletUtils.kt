package com.luxoft.blockchainlab.hyperledger.indy.utils

import com.luxoft.blockchainlab.hyperledger.indy.helpers.WalletConfig
import com.luxoft.blockchainlab.hyperledger.indy.helpers.WalletHelper
import com.luxoft.blockchainlab.hyperledger.indy.helpers.WalletPassword
import com.luxoft.blockchainlab.hyperledger.indy.models.IdentityDetails
import org.hyperledger.indy.sdk.did.Did
import org.hyperledger.indy.sdk.did.DidResults
import org.hyperledger.indy.sdk.ledger.Ledger
import org.hyperledger.indy.sdk.pool.Pool
import org.hyperledger.indy.sdk.wallet.Wallet


object WalletUtils {
    private val trusteeWallet: Wallet by lazy {
        WalletHelper.createOrTrunc("trustee","trustee")
        val wallet = WalletHelper.openExisting("trustee", "trustee")
        makeTrustee(wallet)

        wallet
    }
    private val trusteeDidInfo: DidResults.CreateAndStoreMyDidResult by lazy { makeTrustee(trusteeWallet) }

    fun makeTrustee(wallet: Wallet) = Did.createAndStoreMyDid(wallet, """{"seed":"000000000000000000000000Trustee1"}""").get()

    fun grantLedgerRights(
        pool: Pool,
        issuerDidInfo: IdentityDetails
    ) {
        val nymRequest = Ledger.buildNymRequest(
            trusteeDidInfo.did,
            issuerDidInfo.did,
            issuerDidInfo.verkey,
            null,
            "TRUSTEE"
        ).get()

        Ledger.signAndSubmitRequest(pool, trusteeWallet, trusteeDidInfo.did, nymRequest).get()
    }
}
