package com.github.xingjianluo.resilientfinanceledgerjava.domain;

/**
 * 稳定且可区分的拒绝原因。
 */
public enum RejectionReason {
    INVALID_AMOUNT,
    ACCOUNT_NOT_FOUND,
    TENANT_MISMATCH,
    CURRENCY_MISMATCH,
    INSUFFICIENT_FUNDS,
    UNSUPPORTED_EVENT_VERSION,
    DUPLICATE_EVENT_CONFLICT
}
