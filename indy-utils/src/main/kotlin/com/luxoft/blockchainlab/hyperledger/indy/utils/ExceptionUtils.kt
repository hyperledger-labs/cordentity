package com.luxoft.blockchainlab.hyperledger.indy.utils


fun getRootCause(throwable: Throwable?): Throwable? {

    var rootCause: Throwable? = throwable

    // for fighting unlikely cyclic dependencies
    val list = mutableListOf(rootCause)

    while (rootCause?.cause != null) {
        rootCause = rootCause.cause
        if (list.contains(rootCause)) return null
        list.add(rootCause)
    }

    return rootCause
}