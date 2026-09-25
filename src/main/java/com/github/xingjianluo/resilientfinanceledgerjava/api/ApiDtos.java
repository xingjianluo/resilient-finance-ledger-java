package com.github.xingjianluo.resilientfinanceledgerjava.api;

import com.github.xingjianluo.resilientfinanceledgerjava.domain.RejectionReason;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferStatus;

import java.math.BigDecimal;

/**
 * REST API 传输对象。
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    public record CreateAccountRequest(String tenantId, String accountId, String currency,
                                       BigDecimal initialBalance) {
    }

    public record TransferHttpRequest(String requestId, String tenantId, String fromAccountId,
                                      String toAccountId, BigDecimal amount, String currency) {
    }

    public record TransferHttpResponse(TransferStatus status, RejectionReason reason,
                                       String transactionId) {
    }

    public record BalanceResponse(String tenantId, String accountId, BigDecimal balance) {
    }

    public record ReplayResponse(int applied, int total) {
    }

    public record ErrorResponse(RejectionReason reason, String message) {
    }
}
