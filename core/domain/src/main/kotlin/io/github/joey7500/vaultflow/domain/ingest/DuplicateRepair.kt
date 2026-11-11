package io.github.joey7500.vaultflow.domain.ingest

import io.github.joey7500.vaultflow.domain.model.Transaction
import io.github.joey7500.vaultflow.domain.model.TransactionSource
import kotlin.math.abs

/**
 * Cleans up duplicates that were stored before the Wallet re-post fix:
 * a record reported ONLY by Google Wallet is folded into the matching payment
 * (same amount, same account, compatible merchant, within 90 minutes).
 *
 * Conservative on purpose: a Wallet-only record is folded into a record the
 * BANK confirmed (app notification or e-mail), or into another Wallet-only
 * record no more than 3 minutes away. Nothing else is ever touched.
 */
object DuplicateRepair {

    data class Fix(val keep: Transaction, val remove: List<Transaction>)

    fun find(transactions: List<Transaction>): List<Fix> {
        val walletOnly = { t: Transaction -> t.evidence == setOf(TransactionSource.GOOGLE_WALLET) }
        val removed = mutableSetOf<Long>()
        val kept = LinkedHashMap<Long, Transaction>()
        val removedFor = LinkedHashMap<Long, MutableList<Transaction>>()

        for (w in transactions.filter(walletOnly).sortedBy { it.timestampMillis }) {
            if (w.id in removed) continue
            val target = transactions
                .asSequence()
                .filter { it.id != w.id && it.id !in removed }
                .map { kept[it.id] ?: it }
                .filter { t ->
                    val bankConfirmed = t.isConfirmedByBank
                    val nearWallet = walletOnly(t) && abs(t.timestampMillis - w.timestampMillis) <= Deduplicator.SAME_MOMENT_MS &&
                        t.timestampMillis <= w.timestampMillis
                    (bankConfirmed || nearWallet) && Deduplicator.isDuplicate(w, t)
                }
                .minWithOrNull(compareBy<Transaction>({ if (it.isConfirmedByBank) 0 else 1 }, { abs(it.timestampMillis - w.timestampMillis) }))
                ?: continue
            kept[target.id] = Deduplicator.merge(target, w)
            removed += w.id
            removedFor.getOrPut(target.id) { mutableListOf() } += w
        }
        return kept.map { (id, t) -> Fix(t, removedFor[id].orEmpty()) }
    }
}
