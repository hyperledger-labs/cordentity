package com.luxoft.blockchainlab.hyperledger.indy.models

import com.luxoft.blockchainlab.hyperledger.indy.utils.SerializationUtils

data class NymResponse(
        val op: String,
        val result: Result
) {
    data class Result(
            val `data`: String?,
            val dest: String,
            val identifier: String,
            val reqId: Long,
            val seqNo: Int?,
            val state_proof: StateProof?,
            val txnTime: Int?,
            val type: String
    ) {
        fun getData(): Data? = `data`?.run { Data.fromString(this) }
    }

    data class Data(
            val dest: String,
            val identifier: String,
            val role: String,
            val seqNo: Int,
            val txnTime: Int,
            val verkey: String
    ) {
        companion object {
            fun fromString(str: String): Data = SerializationUtils.jSONToAny(str)
        }
    }

    data class StateProof(
            val multi_signature: MultiSignature,
            val proof_nodes: String,
            val root_hash: String
    )

    data class MultiSignature(
            val participants: List<String>,
            val signature: String,
            val value: Value
    )

    data class Value(
            val ledger_id: Int,
            val pool_state_root_hash: String,
            val state_root_hash: String,
            val timestamp: Int,
            val txn_root_hash: String
    )
}
