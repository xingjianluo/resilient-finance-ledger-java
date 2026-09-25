package com.github.xingjianluo.resilientfinanceledgerjava.domain;

/**
 * 可区分且稳定的拒绝原因码。
 */
public enum RejectionReason {
    INSUFFICIENT_FUNDS,
    CURRENCY_MISMATCH,
    TENANT_MISMATCH,
    INVALID_AMOUNT,
    ACCOUNT_NOT_FOUND,
    UNSUPPORTED_EVENT_VERSION,
    EVENT_VALIDATION_FAILED
}
