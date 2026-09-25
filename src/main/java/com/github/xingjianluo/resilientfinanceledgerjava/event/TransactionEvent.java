package com.github.xingjianluo.resilientfinanceledgerjava.event;

import java.math.BigDecimal;

/**
 * 交易事件（当前版本 V2）。V1 事件缺少 requestId 字段，由 EventUpgrader 升级。
 */
public record TransactionEvent(
        int version,
        String transactionId,
        String requestId,
        String tenantId,
        String fromAccountId,
        String toAccountId,
        BigDecimal amount,
        String currency) {

    public static final int CURRENT_VERSION = 2;
}
