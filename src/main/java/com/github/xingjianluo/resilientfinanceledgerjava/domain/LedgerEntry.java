package com.github.xingjianluo.resilientfinanceledgerjava.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 审计流水（一条复式记账分录）：借方账户 = debitAccountId，贷方账户 = creditAccountId。
 *
 * <p>每条分录记录过账后双方账户余额（debitBalanceAfter / creditBalanceAfter），
 * 查流水即可逐步核对任意账户余额的推演过程。
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
        BigDecimal debitBalanceAfter,
        BigDecimal creditBalanceAfter,
        Instant occurredAt) {
}
