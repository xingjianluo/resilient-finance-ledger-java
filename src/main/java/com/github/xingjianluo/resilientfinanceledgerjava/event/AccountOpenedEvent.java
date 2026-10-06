package com.github.xingjianluo.resilientfinanceledgerjava.event;

import java.math.BigDecimal;

/**
 * 开户事件（当前版本 V1）：记录账户创建与期初余额，
 * 使期初余额可经由事件回放还原，不再凭空出现。
 */
public record AccountOpenedEvent(
        int version,
        String tenantId,
        String accountId,
        String currency,
        BigDecimal initialBalance) implements LedgerEvent {

    public static final int CURRENT_VERSION = 1;
}
