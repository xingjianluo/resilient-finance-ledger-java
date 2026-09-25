package com.github.xingjianluo.resilientfinanceledgerjava.event;

import com.github.xingjianluo.resilientfinanceledgerjava.domain.RejectionReason;

/**
 * 事件无法兼容升级时抛出，携带稳定原因码。
 */
public class EventUpgradeException extends RuntimeException {
    private final RejectionReason reason;

    public EventUpgradeException(RejectionReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectionReason getReason() { return reason; }
}
