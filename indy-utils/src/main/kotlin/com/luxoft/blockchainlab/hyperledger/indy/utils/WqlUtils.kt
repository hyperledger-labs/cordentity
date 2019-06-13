package com.luxoft.blockchainlab.hyperledger.indy.utils

import java.lang.RuntimeException

interface WqlLexem {
    fun toMap(): Map<String, Any>
    fun toJsonString(): String = SerializationUtils.anyToJSON(toMap())
}

interface WqlOperator : WqlLexem {
    val name: String
}

data class EqWqlOperator(override val name: String, val value: String) : WqlOperator {
    override fun toMap() = mapOf("attr::$name::value" to value)
}
data class NeqWqlOperator(override val name: String, val value: String) : WqlOperator {
    override fun toMap() = mapOf("attr::$name::value" to mapOf("\$neq" to value))
}
data class GtWqlOperator(override val name: String, val value: String) : WqlOperator {
    override fun toMap() = mapOf("attr::$name::value" to mapOf("\$gt" to value))
}
data class GteWqlOperator(override val name: String, val value: String) : WqlOperator {
    override fun toMap() = mapOf("attr::$name::value" to mapOf("\$gte" to value))
}
data class LtWqlOperator(override val name: String, val value: String) : WqlOperator {
    override fun toMap() = mapOf("attr::$name::value" to mapOf("\$lt" to value))
}
data class LteWqlOperator(override val name: String, val value: String) : WqlOperator {
    override fun toMap() = mapOf("attr::$name::value" to mapOf("\$lte" to value))
}
data class LikeWqlOperator(override val name: String, val value: String) : WqlOperator {
    override fun toMap() = mapOf("attr::$name::value" to mapOf("\$like" to value))
}
data class InWqlOperator(override val name: String, val values: List<String>) : WqlOperator {
    override fun toMap() = mapOf("attr::$name::value" to mapOf("\$in" to values))
}

class WqlException(override val message: String) : RuntimeException()

interface WqlQuery : WqlLexem {
    fun and(init: ComplexWqlQueryModifier)
    fun or(init: ComplexWqlQueryModifier)
    fun not(init: SimpleWqlQueryModifier)

    infix fun String.eq(value: String)
    infix fun String.neq(value: String)
    infix fun String.gt(value: String)
    infix fun String.gte(value: String)
    infix fun String.lt(value: String)
    infix fun String.lte(value: String)
    infix fun String.like(value: String)
    infix fun String.containsIn(values: List<String>)
}

abstract class SimpleWqlQuery : WqlQuery {
    var lexem: WqlLexem? = null

    private fun throwIfQueryAlreadySet() {
        if (lexem != null)
            throw WqlException("Only AND and OR WQL queries can have multiple subqueries")
    }

    override fun and(init: ComplexWqlQueryModifier) {
        throwIfQueryAlreadySet()
        val query = AndWqlQuery()
        query.init()

        lexem = query
    }

    override fun or(init: ComplexWqlQueryModifier) {
        throwIfQueryAlreadySet()
        val query = OrWqlQuery()
        query.init()

        lexem = query
    }

    override fun not(init: SimpleWqlQueryModifier) {
        throwIfQueryAlreadySet()
        val query = NotWqlQuery()
        query.init()

        lexem = query
    }

    override infix fun String.eq(value: String) {
        throwIfQueryAlreadySet()
        lexem = EqWqlOperator(this@eq, value)
    }

    override infix fun String.neq(value: String) {
        throwIfQueryAlreadySet()
        lexem = NeqWqlOperator(this@neq, value)
    }

    override infix fun String.gt(value: String) {
        throwIfQueryAlreadySet()
        lexem = GtWqlOperator(this@gt, value)
    }

    override infix fun String.gte(value: String) {
        throwIfQueryAlreadySet()
        lexem = GtWqlOperator(this@gte, value)
    }

    override infix fun String.lt(value: String) {
        throwIfQueryAlreadySet()
        lexem = LtWqlOperator(this@lt, value)
    }

    override infix fun String.lte(value: String) {
        throwIfQueryAlreadySet()
        lexem = LteWqlOperator(this@lte, value)
    }

    override infix fun String.like(value: String) {
        throwIfQueryAlreadySet()
        lexem = LikeWqlOperator(this@like, value)
    }

    override infix fun String.containsIn(values: List<String>) {
        throwIfQueryAlreadySet()
        lexem = InWqlOperator(this@containsIn, values)
    }
}

abstract class ComplexWqlQuery : WqlQuery {
    val lexems = mutableListOf<WqlLexem>()

    override fun and(init: ComplexWqlQueryModifier) {
        val query = AndWqlQuery()
        query.init()

        lexems.add(query)
    }

    override fun or(init: ComplexWqlQueryModifier) {
        val query = OrWqlQuery()
        query.init()

        lexems.add(query)
    }

    override fun not(init: SimpleWqlQueryModifier) {
        val query = NotWqlQuery()
        query.init()

        lexems.add(query)
    }

    override fun String.eq(value: String) {
        lexems.add(EqWqlOperator(this@eq, value))
    }

    override fun String.neq(value: String) {
        lexems.add(NeqWqlOperator(this@neq, value))
    }

    override fun String.gt(value: String) {
        lexems.add(GtWqlOperator(this@gt, value))
    }

    override fun String.gte(value: String) {
        lexems.add(GteWqlOperator(this@gte, value))
    }

    override fun String.lt(value: String) {
        lexems.add(LtWqlOperator(this@lt, value))
    }

    override fun String.lte(value: String) {
        lexems.add(LteWqlOperator(this@lte, value))
    }

    override fun String.like(value: String) {
        lexems.add(LikeWqlOperator(this@like, value))
    }

    override fun String.containsIn(values: List<String>) {
        lexems.add(InWqlOperator(this@containsIn, values))
    }
}

class IsWqlQuery : SimpleWqlQuery() {
    override fun toMap(): Map<String, Any> {
        if (lexem == null)
            throw WqlException("Empty IS WQL query")
        else
            return lexem!!.toMap()
    }
}

class NotWqlQuery : SimpleWqlQuery() {
    override fun toMap(): Map<String, Any> {
        if (lexem == null)
            throw WqlException("Empty NOT WQL query")
        else
            return mapOf("\$not" to lexem!!.toMap())
    }
}

class AndWqlQuery : ComplexWqlQuery() {
    override fun toMap(): Map<String, Any> {
        when {
            lexems.isEmpty() -> throw WqlException("Empty AND WQL query")
            lexems.size < 2 -> throw WqlException("AND WQL query should have at least 2 operators")
            else -> return lexems.map { it.toMap() }.reduce { acc, map -> acc.toMutableMap().apply { putAll(map) } }
        }
    }
}

class OrWqlQuery : ComplexWqlQuery() {
    override fun toMap(): Map<String, Any> {
        when {
            lexems.isEmpty() -> throw WqlException("Empty OR WQL query")
            lexems.size < 2 -> throw WqlException("OR WQL query should have at least 2 operators")
            else -> return mapOf("\$or" to lexems.map { it.toMap() })
        }
    }
}

typealias SimpleWqlQueryModifier = SimpleWqlQuery.() -> Unit
typealias ComplexWqlQueryModifier = ComplexWqlQuery.() -> Unit

fun wql(init: SimpleWqlQueryModifier): SimpleWqlQuery {
    val query = IsWqlQuery()
    query.init()
    return query
}
