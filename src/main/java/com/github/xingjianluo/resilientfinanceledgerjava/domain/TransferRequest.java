package com.github.xingjianluo.resilientfinanceledgerjava.domain;

import java.math.BigDecimal;

/**
 * 转账提交请求。requestId 为幂等键。
 *
 * @param businessRef 可选业务参考信息（如订单号），透传至交易事件，不参与账务计算
 */
public record TransferRequest(
        String requestId,
        String tenantId,
        String fromAccountId,
        String toAccountId,
        BigDecimal amount,
        String currency,
        String businessRef) {

    /** 兼容构造：无业务参考信息。 */
    public TransferRequest(String requestId, String tenantId, String fromAccountId,
                           String toAccountId, BigDecimal amount, String currency) {
        this(requestId, tenantId, fromAccountId, toAccountId, amount, currency, null);
    }
}
