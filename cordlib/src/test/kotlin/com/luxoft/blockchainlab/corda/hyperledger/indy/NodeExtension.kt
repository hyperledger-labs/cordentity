package com.luxoft.blockchainlab.corda.hyperledger.indy

import com.luxoft.blockchainlab.corda.hyperledger.indy.service.IndyService
import net.corda.core.identity.CordaX500Name
import net.corda.node.internal.StartedNode
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.internal.InternalMockNetwork

fun StartedNode<InternalMockNetwork.MockNode>.getParty() = this.info.singleIdentity()

fun StartedNode<InternalMockNetwork.MockNode>.getName() = getParty().name

fun StartedNode<InternalMockNetwork.MockNode>.getPubKey() = getParty().owningKey

fun CordaX500Name.getNodeByName(net: InternalMockNetwork) =
    net.defaultNotaryNode.services.identityService.wellKnownPartyFromX500Name(this)!!

fun StartedNode<InternalMockNetwork.MockNode>.getPartyDid() =
    this.services.cordaService(IndyService::class.java).indyUser.did