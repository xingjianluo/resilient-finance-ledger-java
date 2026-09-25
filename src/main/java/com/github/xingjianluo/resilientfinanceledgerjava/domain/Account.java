package com.github.xingjianluo.resilientfinanceledgerjava.domain;

import java.util.Objects;

/**
 * 账户：按 (tenantId, accountId) 唯一，余额以最小货币单位(long)表示。
 * 余额变更只能在 LedgerService 的全局锁内通过 debit/credit 完成。
 * 普通账户不允许透支；权益类对方科目(allowNegative)允许为负以保持借贷守恒。
 */
public class Account {

    private final String tenantId;
    private final String accountId;
    private final String currency;
    private final boolean allowNegative;
    private long balance;

    public Account(String tenantId, String accountId, String currency, long initialBalance) {
        this(tenantId, accountId, currency, initialBalance, false);
    }

    public Account(String tenantId, String accountId, String currency, long initialBalance, boolean allowNegative) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.currency = Objects.requireNonNull(currency, "currency");
        if (initialBalance < 0 && !allowNegative) {
            throw new IllegalArgumentException("initial balance must be >= 0");
        }
        this.allowNegative = allowNegative;
        this.balance = initialBalance;
    }

    public String tenantId() {
        return tenantId;
    }

    public String accountId() {
        return accountId;
    }

    public String currency() {
        return currency;
    }

    public synchronized long balance() {
        return balance;
    }

    /** 借方过账（余额减少）。普通账户余额不足时拒绝。 */
    public synchronized void debit(long amount) {
        if (amount <= 0 || (!allowNegative && balance < amount)) {
            throw new IllegalStateException("debit rejected: balance=" + balance + " amount=" + amount);
        }
        balance -= amount;
    }

    /** 贷方过账（余额增加）。 */
    public synchronized void credit(long amount) {
        if (amount <= 0) {
            throw new IllegalStateException("credit rejected: amount=" + amount);
        }
        balance += amount;
    }
}
