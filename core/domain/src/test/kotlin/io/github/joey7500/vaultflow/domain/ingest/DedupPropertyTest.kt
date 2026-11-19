package io.github.joey7500.vaultflow.domain.ingest

import io.github.joey7500.vaultflow.domain.ingest.Sim.CS
import io.github.joey7500.vaultflow.domain.ingest.Sim.KB
import io.github.joey7500.vaultflow.domain.ingest.Sim.REVOLUT
import java.time.LocalDateTime
import kotlin.random.Random
import org.junit.Assert.fail
import org.junit.Test

/**
 * Property-style tests: random months of payments, each reported by a random
 * subset of sources with realistic delays, replayed in different orders. After
 * every stream, every account must hold exactly one record per real payment
 * and the correct total: no duplicates, no lost payments, no money on the
 * wrong account. Seeds are fixed, so a failure is reproducible.
 */
class DedupPropertyTest {
    private val start = LocalDateTime.of(2026, 11, 2, 7, 0).atZone(Sim.zone).toInstant().toEpochMilli()

    private enum class Order { ARRIVAL, LATE_EMAILS, PHONE_OFF, REPLAYED }

    /**
     * What a phone really sees, out of order: notifications are handled the moment they are
     * posted (a late push carries a late time, see [phoneOff]), but each mailbox is fetched with
     * its own random delay of up to 2 days (sync off, no network), e-mails keeping their times.
     */
    private fun lateEmails(all: List<IncomingMessage>, rnd: Random): List<IncomingMessage> {
        val lag = all.map { it.sourceKey }.distinct().associateWith { rnd.nextLong(0, 2 * Sim.DAY) }
        return all.map { m ->
            val processedAt = if (m.channel == Channel.EMAIL) m.receivedAtMillis + rnd.nextLong(0, lag.getValue(m.sourceKey) + 1) else m.receivedAtMillis
            m to processedAt
        }.sortedBy { it.second }.map { it.first }
    }

    /**
     * Random periods with the phone off: no Google Wallet payments then (you pay by card), and the
     * bank pushes of that period arrive when the phone is back on, with THAT time. E-mails as usual.
     */
    private fun phoneOff(payments: List<Sim.Payment>, rnd: Random): List<IncomingMessage> {
        val first = payments.flatMap { it.messages }.minOf { it.receivedAtMillis }
        val offs = (1..4).map { val s = first + rnd.nextLong(0, 4 * Sim.DAY); s to s + rnd.nextLong(30 * Sim.MIN, 10 * Sim.HOUR) }
        fun offAt(t: Long) = offs.firstOrNull { (s, e) -> t in s until e }
        return payments.flatMap { p ->
            // Paid by card while the phone was off: no Wallet report, but the bank still reports it
            // (a payment known ONLY to Wallet keeps that report, so it is not silently invisible).
            val hasBankReport = p.messages.any { it.sourceKey != Sim.WALLET }
            // Undecidable, so kept on time: a late push of a payment with no e-mail at all, and a
            // late push while an identical payment (same shop, same amount) happened within 12 h
            // (which push belongs to which e-mail cannot be known).
            val hasMail = p.messages.any { it.channel == Channel.EMAIL }
            val lookalikeNearby = payments.any { o -> o !== p && o.lookalike == p.lookalike && kotlin.math.abs(o.at - p.at) <= 13 * Sim.HOUR }
            p.messages.mapNotNull { m ->
                val off = offAt(m.receivedAtMillis)?.takeIf { hasMail && !lookalikeNearby }
                when {
                    off == null || m.channel == Channel.EMAIL -> m
                    m.sourceKey == Sim.WALLET -> if (hasBankReport) null else m
                    else -> m.copy(receivedAtMillis = off.second, messageKey = m.messageKey + "@" + off.second)
                }
            }
        }.sortedBy { it.receivedAtMillis }
    }

    private fun check(seed: Int, order: Order, payments: Int = 40) {
        val rnd = Random(seed)
        val truth = Sim.randomPayments(rnd, start, payments)
        val all = truth.flatMap { it.messages }
        val stream = when (order) {
            Order.ARRIVAL -> all.sortedBy { it.receivedAtMillis }
            Order.LATE_EMAILS -> lateEmails(all, rnd)
            Order.PHONE_OFF -> phoneOff(truth, rnd)
            // Arrival order, plus re-deliveries: the listener reconnects and re-posts active
            // notifications (same key and time), and e-mails are fetched again after the
            // processed-message list was lost (restore, reinstall, purge).
            Order.REPLAYED -> {
                val base = all.sortedBy { it.receivedAtMillis }
                val replays = base.filter { rnd.nextInt(100) < 30 }
                (base.map { it to it.receivedAtMillis } + replays.map { it to it.receivedAtMillis + rnd.nextLong(0, 2 * Sim.DAY) })
                    .sortedBy { it.second }
                    .map { it.first }
            }
        }
        val phone = Sim.Phone()
        var forgot = false
        stream.forEachIndexed { i, m ->
            if (order == Order.REPLAYED && !forgot && i > stream.size / 2) {
                phone.forgetProcessedMails() // "app restarted / restored mid-stream"
                forgot = true
            }
            phone.receive(m)
        }

        for (account in listOf(CS, KB, REVOLUT)) {
            val expectedCount = truth.count { account in it.deltas }
            val expectedSum = truth.sumOf { it.deltas[account] ?: 0L }
            if (phone.count(account) != expectedCount || phone.sum(account) != expectedSum) {
                val detail = buildString {
                    appendLine("seed=$seed order=$order account=$account: expected $expectedCount records / $expectedSum, " +
                        "got ${phone.count(account)} / ${phone.sum(account)}")
                    appendLine("-- payments:")
                    truth.filter { account in it.deltas }.forEach { appendLine("  ${it.label} ${it.deltas}") }
                    appendLine("-- records:")
                    phone.rows.filter { it.accountId == account }.sortedBy { it.timestampMillis }
                        .forEach { appendLine("  #${it.id} ${Sim.dateTime(it.timestampMillis)} ${it.amountCzkMinor} ${it.merchant} ${it.evidence}") }
                    appendLine("-- stream:")
                    phone.outcomes.forEach { appendLine("  $it") }
                }
                fail(detail)
            }
        }
    }

    @Test fun `arrival order - 300 random months`() = (1..300).forEach { check(it, Order.ARRIVAL) }

    @Test fun `e-mails fetched late in random order - 300 random months`() = (1..300).forEach { check(1_000 + it, Order.LATE_EMAILS) }

    @Test fun `phone switched off - pushes arrive late, no Wallet - 300 random months`() = (1..300).forEach { check(3_000 + it, Order.PHONE_OFF) }

    @Test fun `re-delivered notifications and re-fetched e-mails - 300 random months`() = (1..300).forEach { check(2_000 + it, Order.REPLAYED) }
}
