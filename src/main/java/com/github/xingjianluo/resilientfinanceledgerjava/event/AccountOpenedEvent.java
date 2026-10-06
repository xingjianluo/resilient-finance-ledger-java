package com.github.xingjianluo.resilientfinanceledgerjava.event;

import java.math.BigDecimal;

/**
 * 开户事件（当前版本 V1）：记录账户开立与期初余额，
 * 使期初余额不再凭空出现——重启后仅靠事件即可还原账户与余额。
 *
 * @param businessRef 可选业务参考信息（如开户工单号），不参与账务计算
 */
public record AccountOpenedEvent(
        int version,
        String eventId,
        String tenantId,
        String accountId,
        String currency,
        BigDecimal openingBalance,
        String businessRef) implements LedgerEvent {

    public static final int CURRENT_VERSION = 1;
}
