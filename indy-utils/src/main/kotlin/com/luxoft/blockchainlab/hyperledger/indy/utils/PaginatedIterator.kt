package com.luxoft.blockchainlab.hyperledger.indy.utils

class PaginatedIterator<T>(private val pageSize: Int = 2, val nextPage: ((Int) -> List<T>)) : Iterator<T> {
    private var page = emptySequence<T>().iterator()
    private var fetchedAll: Boolean = true

    init {
        if (!(pageSize > 1))
            RuntimeException("PageSize($pageSize) should be bigger than 1")
        nextPage()
    }

    private fun nextPage() {
        val list = nextPage(pageSize)
        fetchedAll = list.size < pageSize
        page = list.iterator()
    }

    override fun hasNext() = page.hasNext()

    override fun next(): T {
        val next = page.next()
        if (!page.hasNext() && !fetchedAll)
            nextPage()
        return next
    }
}