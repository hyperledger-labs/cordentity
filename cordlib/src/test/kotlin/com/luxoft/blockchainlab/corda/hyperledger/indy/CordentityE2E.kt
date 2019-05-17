package com.luxoft.blockchainlab.corda.hyperledger.indy


import net.corda.core.identity.CordaX500Name
import net.corda.node.internal.StartedNode
import net.corda.testing.node.internal.InternalMockNetwork.MockNode
import org.junit.Before
import org.junit.Ignore
import org.junit.Test


/**
 * This test covers the main flow (issue -> prove -> revoke) in a variety of different ways.
 * See [constructTypicalFlow] for details
 *
 * Ignored tests are not-fixed bugs
 */
class CordentityE2E : CordaTestBase() {

    private lateinit var notary: StartedNode<MockNode>

    @Before
    fun setup() {
        notary = net.defaultNotaryNode

        trustee = createPartyNode(CordaX500Name("Trustee", "London", "GB"))
    }

    @Test
    fun `1 issuer 1 prover 1 verifier 1 credential without revocation`() {
        assert(
            constructTypicalFlow(1, 1, 1, 1, true, false, 2)
        )
    }

    @Test
    fun `1 issuer 1 prover 1 verifier 1 credential with revocation`() {
        assert(
            constructTypicalFlow(1, 1, 1, 1, true, true, 2)
        )
    }

    @Test
    fun `1 issuer 1 prover 1 verifier 2 similar credentials without revocation`() {
        assert(
            constructTypicalFlow(1, 1, 1, 2, true, false, 2)
        )
    }

    @Test
    fun `1 issuer 1 prover 1 verifier 2 different credentials without revocation`() {
        assert(
            constructTypicalFlow(1, 1, 1, 2, false, false, 2)
        )
    }

    @Ignore("I don't know how to fix this")
    @Test
    fun `1 issuer 1 prover 1 verifier 2 similar credentials with revocation`() {
        assert(
            constructTypicalFlow(1, 1, 1, 2, true, true, 2)
        )
    }

    @Test
    fun `1 issuer 1 prover 1 verifier 2 different credentials with revocation`() {
        assert(
            constructTypicalFlow(1, 1, 1, 2, false, true, 2)
        )
    }

    @Test
    fun `2 issuers 1 prover 1 verifier 1 credential per issuer without revocation`() {
        assert(
            constructTypicalFlow(2, 1, 1, 1, false, false, 2)
        )
    }

    @Test
    fun `2 issuers 1 prover 1 verifier 1 credential per issuer with revocation`() {
        assert(
            constructTypicalFlow(2, 1, 1, 1, false, false, 2)
        )
    }

    @Test
    fun `2 issuers 1 prover 1 verifier 2 similar credentials per issuer without revocation`() {
        assert(
            constructTypicalFlow(2, 1, 1, 2, true, false, 2)
        )
    }

    @Ignore
    @Test
    fun `2 issuers 1 prover 1 verifier 2 similar credentials per issuer with revocation`() {
        assert(
            constructTypicalFlow(2, 1, 1, 2, true, true, 2)
        )
    }

    @Test
    fun `2 issuers 1 prover 1 verifier 2 different credentials per issuer without revocation`() {
        assert(
            constructTypicalFlow(2, 1, 1, 2, false, false, 2)
        )
    }

    @Test
    fun `2 issuers 1 prover 1 verifier 2 different credentials per issuer with revocation`() {
        assert(
            constructTypicalFlow(2, 1, 1, 2, false, true, 2)
        )
    }

    @Test
    fun `3 issuers 3 provers 3 verifiers 3 similar credentials per issuer without revocation`() {
        assert(
            constructTypicalFlow(3, 3, 3, 3, true, false, 10)
        )
    }

    @Ignore
    @Test
    fun `3 issuers 3 provers 3 verifiers 3 similar credentials per issuer with revocation`() {
        assert(
            constructTypicalFlow(3, 3, 3, 3, true, true, 10)
        )
    }

    @Test
    fun `3 issuers 3 provers 3 verifiers 3 different credentials per issuer without revocation`() {
        assert(
            constructTypicalFlow(3, 3, 3, 3, false, false, 4)
        )
    }

    @Test
    fun `3 issuers 3 provers 3 verifiers 3 different credentials per issuer with revocation`() {
        assert(
            constructTypicalFlow(3, 3, 3, 3, false, true, 4)
        )
    }
}
