package com.github.xingjianluo.resilientfinanceledgerjava.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 审计流水（一条复式记账分录）：借方账户 = fromAccountId，贷方账户 = toAccountId。
 */
public record LedgerEntry(
        long sequence,
        String transactionId,
        String requestId,
        String tenantId,
        String debitAccountId,
        String creditAccountId,
        BigDecimal amount,
        String currency,
        Instant occurredAt) {
}
