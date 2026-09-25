package com.github.xingjianluo.resilientfinanceledgerjava.domain;

import java.math.BigDecimal;

/**
 * 账户：按 (tenantId, accountId) 唯一，余额只允许通过复式记账变动。
 */
public class Account {
    private final String tenantId;
    private final String accountId;
    private final String currency;
    private BigDecimal balance;

    public Account(String tenantId, String accountId, String currency, BigDecimal initialBalance) {
        this.tenantId = tenantId;
        this.accountId = accountId;
        this.currency = currency;
        this.balance = initialBalance;
    }

    public String getTenantId() { return tenantId; }
    public String getAccountId() { return accountId; }
    public String getCurrency() { return currency; }

    public synchronized BigDecimal getBalance() { return balance; }

    /** 借出（扣减）。调用方必须持有账户锁并已完成余额检查。 */
    public void debit(BigDecimal amount) {
        synchronized (this) {
            this.balance = this.balance.subtract(amount);
        }
    }

    /** 贷入（增加）。调用方必须持有账户锁。 */
    public void credit(BigDecimal amount) {
        synchronized (this) {
            this.balance = this.balance.add(amount);
        }
    }
}
