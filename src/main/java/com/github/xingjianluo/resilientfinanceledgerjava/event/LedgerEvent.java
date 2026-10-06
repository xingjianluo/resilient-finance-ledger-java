package com.github.xingjianluo.resilientfinanceledgerjava.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 账本事件基类（只追加事件存储中的一切事件）。
 *
 * <p>JSON 多态：以 eventType 属性区分事件类型；旧格式报文没有 eventType 属性，
 * 缺省按 TransactionEvent 反序列化，保证旧事件可照常回放。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
        property = "eventType", defaultImpl = TransactionEvent.class)
@JsonSubTypes({
        @JsonSubTypes.Type(value = TransactionEvent.class, name = "TRANSACTION"),
        @JsonSubTypes.Type(value = AccountOpenedEvent.class, name = "ACCOUNT_OPENED")
})
public sealed interface LedgerEvent permits TransactionEvent, AccountOpenedEvent {

    /** 事件格式版本（按事件类型各自演进）。 */
    int version();

    /** 所属租户。 */
    String tenantId();
}
