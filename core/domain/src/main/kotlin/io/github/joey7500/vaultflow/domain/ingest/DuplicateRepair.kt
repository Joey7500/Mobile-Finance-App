package io.github.joey7500.vaultflow.domain.ingest

import io.github.joey7500.vaultflow.domain.model.Transaction
import io.github.joey7500.vaultflow.domain.model.TransactionSource
import java.time.Instant
import java.time.ZoneId
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

    private const val MIRROR = ":mirror"

    /**
     * Version 2 (1.2.0): duplicates that are PROVABLY the same message, so folding them can
     * never merge two real payments:
     * - two records carrying the same message fingerprint (one message stored twice, e.g. a
     *   replayed e-mail that merged into a closer record);
     * - an expected transfer counterpart created by the old "X:mirror:mirror" chain, next to the
     *   record of message X on the same account with the same amount (both stem from message X).
     */
    fun findProvable(transactions: List<Transaction>): List<Fix> {
        val removed = mutableSetOf<Long>()
        val kept = LinkedHashMap<Long, Transaction>()
        val removedFor = LinkedHashMap<Long, MutableList<Transaction>>()
        fun fold(keep: Transaction, dup: Transaction) {
            val base = kept[keep.id] ?: keep
            kept[keep.id] = Deduplicator.merge(base, dup)
            removed += dup.id
            removedFor.getOrPut(keep.id) { mutableListOf() } += dup
        }

        // Same message stored twice.
        transactions.filter { !it.fingerprint.isNullOrBlank() }
            .groupBy { it.fingerprint }
            .values.filter { it.size > 1 }
            .forEach { group ->
                val sorted = group.sortedBy { it.id }
                sorted.drop(1).filter { it.id !in removed && it.accountId == sorted.first().accountId }.forEach { fold(sorted.first(), it) }
            }

        // Chained counterparts "X:mirror:mirror" that only ever held the expected transfer.
        transactions.filter { it.id !in removed && it.evidence == setOf(TransactionSource.INFERRED) && (it.fingerprint ?: "").endsWith(MIRROR + MIRROR) }
            .forEach { chained ->
                var root = chained.fingerprint!!
                while (root.endsWith(MIRROR)) root = root.removeSuffix(MIRROR)
                val origin = transactions.firstOrNull { t ->
                    t.id != chained.id && t.id !in removed && t.accountId == chained.accountId &&
                        t.amountCzkMinor == chained.amountCzkMinor && t.knows(root)
                } ?: return@forEach
                removed += chained.id
                kept.putIfAbsent(origin.id, origin)
                removedFor.getOrPut(origin.id) { mutableListOf() } += chained
            }
        return kept.map { (id, t) -> Fix(t, removedFor[id].orEmpty()) }
    }

    /**
     * POSSIBLE duplicates for you to decide (never merged automatically): a record confirmed only
     * by a bank e-mail next to a record the bank app pushed but no e-mail confirmed, same account,
     * same amount, compatible shop name, same day, and each has exactly one such partner. Older
     * versions created these when a date-only e-mail arrived more than 3 h late. It can also be
     * two real payments (one missed its push, the other its e-mail), so you choose.
     */
    fun suspects(transactions: List<Transaction>, zone: ZoneId): List<Pair<Transaction, Transaction>> {
        fun day(t: Transaction) = Instant.ofEpochMilli(t.timestampMillis).atZone(zone).toLocalDate()
        val mailOnly = transactions.filter { it.evidence == setOf(TransactionSource.EMAIL) && it.amountCzkMinor < 0 }
        val pushedNoMail = transactions.filter {
            TransactionSource.BANK_NOTIFICATION in it.evidence && TransactionSource.EMAIL !in it.evidence && it.amountCzkMinor < 0
        }
        fun partners(m: Transaction) = pushedNoMail.filter { p ->
            p.accountId == m.accountId && p.amountCzkMinor == m.amountCzkMinor && day(p) == day(m) &&
                p.timestampMillis <= m.timestampMillis && Deduplicator.merchantsCompatible(p.merchant, m.merchant)
        }
        return mailOnly.mapNotNull { m ->
            val p = partners(m).singleOrNull() ?: return@mapNotNull null
            val rivals = mailOnly.count { other -> partners(other).any { it.id == p.id } }
            if (rivals == 1) p to m else null
        }
    }
}
