package com.github.xingjianluo.resilientfinanceledgerjava.domain;

import java.util.Objects;

/**
 * 交易事件（不可变）。schemaVersion 标识事件格式版本：
 *  - v1: 无 tenantId（单租户时代遗留，升级时补默认租户）
 *  - v2: 当前版本，含 tenantId 与 memo
 * sequence 由 EventStore 在追加时分配，全局单调递增，用于乱序重排。
 */
public class TransferEvent {

    public static final int CURRENT_VERSION = 2;

    private final int schemaVersion;
    private final long sequence;
    private final String transactionId;
    private final String tenantId;
    private final String fromAccountId;
    private final String toAccountId;
    private final long amount;
    private final String currency;
    private final String memo;

    public TransferEvent(int schemaVersion, long sequence, String transactionId, String tenantId,
                         String fromAccountId, String toAccountId, long amount, String currency, String memo) {
        this.schemaVersion = schemaVersion;
        this.sequence = sequence;
        this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
        this.tenantId = tenantId;
        this.fromAccountId = Objects.requireNonNull(fromAccountId, "fromAccountId");
        this.toAccountId = Objects.requireNonNull(toAccountId, "toAccountId");
        this.amount = amount;
        this.currency = Objects.requireNonNull(currency, "currency");
        this.memo = memo;
    }

    /** 以当前版本创建新事件（sequence 由 EventStore 分配，此处置 -1）。 */
    public static TransferEvent of(String transactionId, String tenantId, String fromAccountId,
                                   String toAccountId, long amount, String currency, String memo) {
        return new TransferEvent(CURRENT_VERSION, -1, transactionId, tenantId,
                fromAccountId, toAccountId, amount, currency, memo);
    }

    public TransferEvent withSequence(long newSequence) {
        return new TransferEvent(schemaVersion, newSequence, transactionId, tenantId,
                fromAccountId, toAccountId, amount, currency, memo);
    }

    public TransferEvent withTenantId(String newTenantId) {
        return new TransferEvent(schemaVersion, sequence, transactionId, newTenantId,
                fromAccountId, toAccountId, amount, currency, memo);
    }

    public TransferEvent withVersion(int newVersion) {
        return new TransferEvent(newVersion, sequence, transactionId, tenantId,
                fromAccountId, toAccountId, amount, currency, memo);
    }

    public int schemaVersion() { return schemaVersion; }
    public long sequence() { return sequence; }
    public String transactionId() { return transactionId; }
    public String tenantId() { return tenantId; }
    public String fromAccountId() { return fromAccountId; }
    public String toAccountId() { return toAccountId; }
    public long amount() { return amount; }
    public String currency() { return currency; }
    public String memo() { return memo; }

    @Override
    public String toString() {
        return "TransferEvent{v" + schemaVersion + ", seq=" + sequence + ", tx=" + transactionId
                + ", tenant=" + tenantId + ", " + fromAccountId + "->" + toAccountId
                + ", amount=" + amount + " " + currency + "}";
    }
}
