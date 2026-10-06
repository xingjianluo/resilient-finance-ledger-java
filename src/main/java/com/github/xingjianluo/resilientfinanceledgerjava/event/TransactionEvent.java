package com.github.xingjianluo.resilientfinanceledgerjava.event;

import java.math.BigDecimal;

/**
 * 交易事件（当前版本 V3）。
 *
 * <p>版本演进：V1 缺少 requestId 字段；V2 缺少 businessRef 字段；
 * 均由 EventUpgrader 逐级升级，旧版本事件可照常回放。
 *
 * @param businessRef 可选业务参考信息（如订单号），不参与账务计算，可为 null
 */
public record TransactionEvent(
        int version,
        String transactionId,
        String requestId,
        String tenantId,
        String fromAccountId,
        String toAccountId,
        BigDecimal amount,
        String currency,
        String businessRef) implements LedgerEvent {

    public static final int CURRENT_VERSION = 3;

    /** 兼容 V1/V2 构造：无 businessRef。 */
    public TransactionEvent(int version, String transactionId, String requestId, String tenantId,
                            String fromAccountId, String toAccountId, BigDecimal amount, String currency) {
        this(version, transactionId, requestId, tenantId, fromAccountId, toAccountId, amount, currency, null);
    }
}
