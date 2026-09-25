package com.github.xingjianluo.resilientfinanceledgerjava.domain;

import java.math.BigDecimal;

/**
 * 转账提交请求。requestId 为幂等键。
 */
public record TransferRequest(
        String requestId,
        String tenantId,
        String fromAccountId,
        String toAccountId,
        BigDecimal amount,
        String currency) {
}
