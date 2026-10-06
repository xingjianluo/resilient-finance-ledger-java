package com.github.xingjianluo.resilientfinanceledgerjava.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 审计流水（一条复式记账分录）：借方账户 = debitAccountId，贷方账户 = creditAccountId。
 *
 * <p>每条分录记录过账后双方账户的余额（debitBalanceAfter / creditBalanceAfter），
 * 对账时可逐笔核对“过账之后账户变成了多少”。期初入账分录的借方为系统期初权益
 * 虚账户（非真实账户，余额侧记为 null）。
 */
public record LedgerEntry(
        long sequence,
        EntryType type,
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

    /** 兼容旧构造：默认为转账分录，不带过账后余额。 */
    public LedgerEntry(long sequence, String transactionId, String requestId, String tenantId,
                       String debitAccountId, String creditAccountId, BigDecimal amount,
                       String currency, Instant occurredAt) {
        this(sequence, EntryType.TRANSFER, transactionId, requestId, tenantId, debitAccountId,
                creditAccountId, amount, currency, null, null, occurredAt);
    }
}
