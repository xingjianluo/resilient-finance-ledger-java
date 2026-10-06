package com.github.xingjianluo.resilientfinanceledgerjava.event;

/**
 * 账本事件基类（密封）：交易事件 / 开户事件。事件存储与回放统一处理该类型。
 */
public sealed interface LedgerEvent permits TransactionEvent, AccountOpenedEvent {

    int version();

    String tenantId();
}
