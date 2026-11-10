package io.github.joey7500.vaultflow.data.local

import io.github.joey7500.vaultflow.data.local.entity.AccountEntity
import io.github.joey7500.vaultflow.data.local.entity.CategoryEntity
import io.github.joey7500.vaultflow.data.local.entity.ManualAssetEntity
import io.github.joey7500.vaultflow.data.local.entity.RuleEntity
import io.github.joey7500.vaultflow.data.local.entity.TransactionEntity
import io.github.joey7500.vaultflow.domain.model.Account
import io.github.joey7500.vaultflow.domain.model.AccountType
import io.github.joey7500.vaultflow.domain.model.Category
import io.github.joey7500.vaultflow.domain.model.ClassificationRule
import io.github.joey7500.vaultflow.domain.model.FlowKind
import io.github.joey7500.vaultflow.domain.model.ManualAsset
import io.github.joey7500.vaultflow.domain.model.ManualAssetKind
import io.github.joey7500.vaultflow.domain.model.RuleMatch
import io.github.joey7500.vaultflow.domain.model.Transaction
import io.github.joey7500.vaultflow.domain.model.TransactionSource
import io.github.joey7500.vaultflow.domain.model.TransactionStatus

private inline fun <reified E : Enum<E>> enumOr(value: String, default: E): E =
    runCatching { enumValueOf<E>(value) }.getOrDefault(default)

fun CategoryEntity.toDomain() = Category(
    id = id, key = key, name = name, group = groupName,
    kind = enumOr(kind, FlowKind.EXPENSE), monthlyBudgetMinor = monthlyBudgetMinor, sortOrder = sortOrder,
)

fun TransactionEntity.toDomain() = Transaction(
    id = id, timestampMillis = timestampMillis, amountMinor = amountMinor, currency = currency,
    amountCzkMinor = amountCzkMinor, merchant = merchant, accountId = accountId, categoryId = categoryId,
    source = enumOr(source, TransactionSource.MANUAL), status = enumOr(status, TransactionStatus.SETTLED),
    confidence = confidence, note = note, sourcePackage = sourcePackage, fingerprint = fingerprint,
    evidence = evidence.split(',').mapNotNull { e -> runCatching { enumValueOf<TransactionSource>(e.trim()) }.getOrNull() }
        .toSet().ifEmpty { setOf(enumOr(source, TransactionSource.MANUAL)) },
)

fun Transaction.toEntity() = TransactionEntity(
    id = id, timestampMillis = timestampMillis, amountMinor = amountMinor, currency = currency,
    amountCzkMinor = amountCzkMinor, merchant = merchant, accountId = accountId, categoryId = categoryId,
    source = source.name, status = status.name, confidence = confidence, note = note,
    sourcePackage = sourcePackage, fingerprint = fingerprint,
    evidence = evidence.joinToString(",") { it.name },
)

fun AccountEntity.toDomain() = Account(
    id = id, name = name, type = enumOr(type, AccountType.BANK), anchorBalanceMinor = anchorBalanceMinor,
    anchorDateMillis = anchorDateMillis, isLiquid = isLiquid,
    ownIdentifiers = ownIdentifiers.lines().map { it.trim() }.filter { it.isNotEmpty() },
    cardIdentifiers = cardIdentifiers.lines().map { it.filter(Char::isDigit) }.filter { it.length >= 4 },
    transferAliases = transferAliases.lines().map { it.trim() }.filter { it.length >= 3 },
    overdraftMinor = overdraftMinor,
    lastCheckAtMillis = lastCheckAtMillis,
    lastCheckDiffMinor = lastCheckDiffMinor,
)

fun RuleEntity.toDomain() = ClassificationRule(
    id = id, pattern = pattern, match = enumOr(matchType, RuleMatch.CONTAINS), categoryId = categoryId,
    createdAtMillis = createdAtMillis,
)

fun ManualAssetEntity.toDomain() = ManualAsset(
    id = id, name = name, kind = enumOr(kind, ManualAssetKind.OTHER), valueMinor = valueMinor,
    updatedAtMillis = updatedAtMillis, note = note,
)
