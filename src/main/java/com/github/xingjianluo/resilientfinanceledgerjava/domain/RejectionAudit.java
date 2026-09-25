package com.github.xingjianluo.resilientfinanceledgerjava.domain;

import java.time.Instant;

/**
 * 拒绝审计记录：还原一次被拒绝请求的关键输入与稳定原因码。
 */
public record RejectionAudit(
        String requestId,
        String tenantId,
        String fromAccountId,
        String toAccountId,
        String amount,
        String currency,
        RejectionReason reason,
        Instant occurredAt) {
}
