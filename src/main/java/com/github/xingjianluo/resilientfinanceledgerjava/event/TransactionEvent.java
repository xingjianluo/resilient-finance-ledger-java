package com.github.xingjianluo.resilientfinanceledgerjava.event;

import java.math.BigDecimal;

/**
 * 交易事件（当前版本 V3）。
 *
 * <p>版本演进：
 * <ul>
 *   <li>V1：无 requestId 字段，由 EventUpgrader 以 transactionId 派生幂等键；</li>
 *   <li>V2：引入 requestId；</li>
 *   <li>V3：新增可选业务参考信息 businessReference（可为 null，不参与校验）。</li>
 * </ul>
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
        String businessReference) implements LedgerEvent {

    public static final int CURRENT_VERSION = 3;

    /** 兼容 V1/V2 构造：无业务参考信息。 */
    public TransactionEvent(int version, String transactionId, String requestId, String tenantId,
                            String fromAccountId, String toAccountId, BigDecimal amount, String currency) {
        this(version, transactionId, requestId, tenantId, fromAccountId, toAccountId, amount, currency, null);
    }
}
