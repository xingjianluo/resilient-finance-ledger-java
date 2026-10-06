package com.github.xingjianluo.resilientfinanceledgerjava.service;

import com.github.xingjianluo.resilientfinanceledgerjava.domain.Account;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.EntryType;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.LedgerEntry;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.ReconciliationReport;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.RejectionAudit;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.RejectionReason;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferRequest;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferResult;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferStatus;
import com.github.xingjianluo.resilientfinanceledgerjava.event.AccountOpenedEvent;
import com.github.xingjianluo.resilientfinanceledgerjava.event.EventUpgradeException;
import com.github.xingjianluo.resilientfinanceledgerjava.event.EventUpgrader;
import com.github.xingjianluo.resilientfinanceledgerjava.event.LedgerEvent;
import com.github.xingjianluo.resilientfinanceledgerjava.event.TransactionEvent;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 复式记账核心服务。
 *
 * <p>一致性规则：
 * <ul>
 *   <li>期初即复式：开户时以“系统期初权益虚账户 → 新账户”的复式分录落账，
 *       任意账户余额都可只凭流水逐步推出；</li>
 *   <li>幂等：以 (tenantId, requestId) 为幂等键，重复提交返回首次结果，不重复入账；</li>
 *   <li>并发：按账户键排序加锁，避免死锁；检查余额与扣款在同一临界区，杜绝透支与丢失更新；</li>
 *   <li>事件先行：事件追加成功后才变动余额，回放可完整还原已确认交易与开户；</li>
 *   <li>回放：先整批升级校验（任何一条不兼容则整批拒绝、账务零变化），再按幂等键去重应用，
 *       乱序/重复回放不改变最终余额；</li>
 *   <li>对账：对账报告在全局写锁下取一致快照，并发写入期间不会读到中间状态而误报。</li>
 * </ul>
 */
@Service
public class LedgerService {

    /** 系统期初权益虚账户：只出现在期初分录的借方，不是真实账户，不可开户/转账。 */
    public static final String OPENING_EQUITY_ACCOUNT = "SYSTEM_OPENING_EQUITY";

    private final EventStore eventStore;
    private final EventUpgrader eventUpgrader;

    /** 账户表：key = tenantId|accountId */
    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    /** 账户锁：key = tenantId|accountId */
    private final Map<String, ReentrantLock> accountLocks = new ConcurrentHashMap<>();
    /** 幂等结果表：key = tenantId|requestId */
    private final Map<String, CompletableFuture<TransferResult>> idempotencyTable = new ConcurrentHashMap<>();
    /** 审计流水（只追加） */
    private final List<LedgerEntry> journal = new CopyOnWriteArrayList<>();
    /** 拒绝审计（只追加） */
    private final List<RejectionAudit> rejectionAudits = new CopyOnWriteArrayList<>();
    /**
     * 快照锁：所有“追加事件 + 变动余额 + 写流水”的临界区持读锁（彼此可并发），
     * 对账报告持写锁，保证报告读到的是一致快照而非中间状态。
     */
    private final ReentrantReadWriteLock snapshotLock = new ReentrantReadWriteLock();

    public LedgerService(EventStore eventStore, EventUpgrader eventUpgrader) {
        this.eventStore = eventStore;
        this.eventUpgrader = eventUpgrader;
    }

    private static String key(String tenantId, String id) {
        return tenantId + "|" + id;
    }

    /**
     * 开户：期初余额以复式分录落账（借：系统期初权益虚账户，贷：新账户），
     * 并追加开户事件，使重启后仅凭事件即可还原账户与余额。
     */
    public void createAccount(String tenantId, String accountId, String currency, BigDecimal initialBalance) {
        if (initialBalance == null || initialBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("initial balance must be >= 0");
        }
        if (OPENING_EQUITY_ACCOUNT.equals(accountId)) {
            throw new IllegalArgumentException("reserved account id: " + OPENING_EQUITY_ACCOUNT);
        }
        snapshotLock.readLock().lock();
        try {
            Account account = new Account(tenantId, accountId, currency, BigDecimal.ZERO);
            if (accounts.putIfAbsent(key(tenantId, accountId), account) != null) {
                throw new IllegalArgumentException("account already exists: " + key(tenantId, accountId));
            }
            ReentrantLock lock = accountLocks.computeIfAbsent(key(tenantId, accountId),
                    k -> new ReentrantLock());
            lock.lock();
            try {
                AccountOpenedEvent event = new AccountOpenedEvent(
                        AccountOpenedEvent.CURRENT_VERSION, tenantId, accountId, currency, initialBalance);
                long seq = eventStore.append(event);
                applyOpening(event, seq);
            } finally {
                lock.unlock();
            }
        } finally {
            snapshotLock.readLock().unlock();
        }
    }

    /**
     * 提交转账。幂等：相同 (tenantId, requestId) 的重复提交返回首次结果。
     */
    public TransferResult transfer(TransferRequest request) {
        String idemKey = key(request.tenantId(), request.requestId());
        CompletableFuture<TransferResult> mine = new CompletableFuture<>();
        CompletableFuture<TransferResult> existing = idempotencyTable.putIfAbsent(idemKey, mine);
        if (existing != null) {
            TransferResult first = existing.join();
            // 幂等命中：成功则报告 DUPLICATE，拒绝则原样返回稳定原因
            if (first.status() == TransferStatus.ACCEPTED) {
                return TransferResult.duplicate(first.transactionId());
            }
            return first;
        }
        try {
            TransferResult result = doTransfer(request);
            mine.complete(result);
            return result;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            idempotencyTable.remove(idemKey, mine);
            throw e;
        }
    }

    private TransferResult doTransfer(TransferRequest request) {
        // 1. 金额校验
        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            return reject(request, RejectionReason.INVALID_AMOUNT);
        }
        String fromKey = key(request.tenantId(), request.fromAccountId());
        String toKey = key(request.tenantId(), request.toAccountId());

        // 2. 账户存在性 / 租户隔离：账户存在于其他租户时返回 TENANT_MISMATCH（可区分）
        Account from = accounts.get(fromKey);
        Account to = accounts.get(toKey);
        if (from == null) {
            return reject(request, existsUnderOtherTenant(request.fromAccountId(), request.tenantId())
                    ? RejectionReason.TENANT_MISMATCH : RejectionReason.ACCOUNT_NOT_FOUND);
        }
        if (to == null) {
            return reject(request, existsUnderOtherTenant(request.toAccountId(), request.tenantId())
                    ? RejectionReason.TENANT_MISMATCH : RejectionReason.ACCOUNT_NOT_FOUND);
        }

        // 3. 币种校验
        if (!from.getCurrency().equals(request.currency()) || !to.getCurrency().equals(request.currency())) {
            return reject(request, RejectionReason.CURRENCY_MISMATCH);
        }

        // 4. 快照读锁 + 按账户键字典序加锁（统一锁序：先快照锁后账户锁，防死锁）
        snapshotLock.readLock().lock();
        try {
            List<ReentrantLock> locks = java.util.stream.Stream.of(fromKey, toKey)
                    .sorted()
                    .map(k -> accountLocks.computeIfAbsent(k, x -> new ReentrantLock()))
                    .toList();
            locks.forEach(ReentrantLock::lock);
            try {
                if (from.getBalance().compareTo(request.amount()) < 0) {
                    return reject(request, RejectionReason.INSUFFICIENT_FUNDS);
                }
                // 5. 事件先行：追加成功才变动余额
                String transactionId = "tx-" + UUID.randomUUID();
                TransactionEvent event = new TransactionEvent(
                        TransactionEvent.CURRENT_VERSION, transactionId, request.requestId(),
                        request.tenantId(), request.fromAccountId(), request.toAccountId(),
                        request.amount(), request.currency());
                long seq = eventStore.append(event);
                applyPosting(event, seq);
                return TransferResult.accepted(transactionId);
            } finally {
                locks.forEach(ReentrantLock::unlock);
            }
        } finally {
            snapshotLock.readLock().unlock();
        }
    }

    /** 期初分录：借 = 系统期初权益虚账户，贷 = 新账户。调用方必须持有账户锁与快照读锁。 */
    private void applyOpening(AccountOpenedEvent event, long seq) {
        Account account = accounts.get(key(event.tenantId(), event.accountId()));
        if (event.initialBalance().compareTo(BigDecimal.ZERO) > 0) {
            account.credit(event.initialBalance());
        }
        journal.add(new LedgerEntry(seq, EntryType.OPENING_BALANCE,
                "opening-" + event.accountId(), null, event.tenantId(),
                OPENING_EQUITY_ACCOUNT, event.accountId(), event.initialBalance(), event.currency(),
                null, account.getBalance(), Instant.now()));
    }

    /** 转账分录：借 = 付款账户，贷 = 收款账户。调用方必须持有双方账户锁与快照读锁。 */
    private void applyPosting(TransactionEvent event, long seq) {
        Account from = accounts.get(key(event.tenantId(), event.fromAccountId()));
        Account to = accounts.get(key(event.tenantId(), event.toAccountId()));
        from.debit(event.amount());
        to.credit(event.amount());
        journal.add(new LedgerEntry(seq, EntryType.TRANSFER, event.transactionId(), event.requestId(),
                event.tenantId(), event.fromAccountId(), event.toAccountId(), event.amount(),
                event.currency(), from.getBalance(), to.getBalance(), Instant.now()));
    }

    private boolean existsUnderOtherTenant(String accountId, String tenantId) {
        return accounts.values().stream()
                .anyMatch(a -> a.getAccountId().equals(accountId) && !a.getTenantId().equals(tenantId));
    }

    private TransferResult reject(TransferRequest request, RejectionReason reason) {
        rejectionAudits.add(new RejectionAudit(request.requestId(), request.tenantId(),
                request.fromAccountId(), request.toAccountId(),
                String.valueOf(request.amount()), request.currency(), reason, Instant.now()));
        return TransferResult.rejected(reason);
    }

    /** 租户隔离的余额查询：他租户账户不可见。 */
    public BigDecimal balanceOf(String tenantId, String accountId) {
        Account account = accounts.get(key(tenantId, accountId));
        if (account == null) {
            throw new IllegalArgumentException("account not found for tenant: " + key(tenantId, accountId));
        }
        return account.getBalance();
    }

    /** 租户隔离的审计流水（含期初分录，可按 type 区分）。 */
    public List<LedgerEntry> auditTrail(String tenantId) {
        return journal.stream().filter(e -> e.tenantId().equals(tenantId)).toList();
    }

    /** 租户隔离的拒绝审计。 */
    public List<RejectionAudit> rejectionAudit(String tenantId) {
        return rejectionAudits.stream().filter(r -> r.tenantId().equals(tenantId)).toList();
    }

    /**
     * 回放事件：两阶段保证“不兼容则账务零变化”。
     *
     * <p>阶段一：整批升级到当前版本并校验关键字段，同时校验转账事件引用的账户
     * （含本批开户事件将创建的账户）；任何一条不合法直接抛出，不应用任何事件。
     *
     * <p>阶段二：先应用开户事件（按账户存在性幂等去重），再按原顺序应用转账事件
     * （按幂等键去重）。开户与转账之间无顺序依赖，重排安全；转账之间求和可交换，
     * 乱序/重复回放不改变最终余额。已确认事件在回放时不再做余额检查（提交时已校验）。
     *
     * @return 本次实际新应用的事件数
     */
    public int replay(List<? extends LedgerEvent> events) {
        // 阶段一：整批升级 + 校验，任何一条不兼容则整批拒绝，账务零变化
        List<LedgerEvent> upgraded = events.stream()
                .map(eventUpgrader::upgradeToCurrent)
                .toList();
        validateAccountReferences(upgraded);

        // 阶段二：先开户后转账
        int applied = 0;
        for (LedgerEvent event : upgraded) {
            if (event instanceof AccountOpenedEvent opened) {
                applied += replayOpening(opened);
            }
        }
        for (LedgerEvent event : upgraded) {
            if (event instanceof TransactionEvent transaction) {
                applied += replayTransfer(transaction);
            }
        }
        return applied;
    }

    /** 校验转账事件引用的账户存在（含本批开户事件将创建的账户），否则整批拒绝。 */
    private void validateAccountReferences(List<LedgerEvent> upgraded) {
        Set<String> known = new HashSet<>(accounts.keySet());
        for (LedgerEvent event : upgraded) {
            if (event instanceof AccountOpenedEvent opened) {
                known.add(key(opened.tenantId(), opened.accountId()));
            }
        }
        for (LedgerEvent event : upgraded) {
            if (event instanceof TransactionEvent transaction) {
                if (!known.contains(key(transaction.tenantId(), transaction.fromAccountId()))
                        || !known.contains(key(transaction.tenantId(), transaction.toAccountId()))) {
                    throw new EventUpgradeException(RejectionReason.EVENT_VALIDATION_FAILED,
                            "replay references unknown account: " + transaction);
                }
            }
        }
    }

    /** 回放开户事件：账户已存在且参数一致则幂等跳过；参数冲突明确拒绝。 */
    private int replayOpening(AccountOpenedEvent event) {
        snapshotLock.readLock().lock();
        try {
            String k = key(event.tenantId(), event.accountId());
            Account existing = accounts.get(k);
            if (existing != null) {
                if (!existing.getCurrency().equals(event.currency())) {
                    throw new EventUpgradeException(RejectionReason.EVENT_VALIDATION_FAILED,
                            "account-opened event conflicts with existing account: " + event);
                }
                return 0; // 幂等跳过
            }
            Account account = new Account(event.tenantId(), event.accountId(), event.currency(),
                    BigDecimal.ZERO);
            if (accounts.putIfAbsent(k, account) != null) {
                return 0; // 并发下已被创建：幂等跳过
            }
            ReentrantLock lock = accountLocks.computeIfAbsent(k, x -> new ReentrantLock());
            lock.lock();
            try {
                long seq = eventStore.append(event);
                applyOpening(event, seq);
            } finally {
                lock.unlock();
            }
            return 1;
        } finally {
            snapshotLock.readLock().unlock();
        }
    }

    /** 回放转账事件：按幂等键去重，账户锁 + 快照读锁内应用。 */
    private int replayTransfer(TransactionEvent event) {
        String idemKey = key(event.tenantId(), event.requestId());
        CompletableFuture<TransferResult> marker = new CompletableFuture<>();
        if (idempotencyTable.putIfAbsent(idemKey, marker) != null) {
            return 0; // 重复事件：跳过，不重复入账
        }
        try {
            snapshotLock.readLock().lock();
            try {
                String fromKey = key(event.tenantId(), event.fromAccountId());
                String toKey = key(event.tenantId(), event.toAccountId());
                List<ReentrantLock> locks = java.util.stream.Stream.of(fromKey, toKey)
                        .sorted()
                        .map(k -> accountLocks.computeIfAbsent(k, x -> new ReentrantLock()))
                        .toList();
                locks.forEach(ReentrantLock::lock);
                try {
                    long seq = eventStore.append(event);
                    applyPosting(event, seq);
                } finally {
                    locks.forEach(ReentrantLock::unlock);
                }
            } finally {
                snapshotLock.readLock().unlock();
            }
            marker.complete(TransferResult.accepted(event.transactionId()));
            return 1;
        } catch (RuntimeException e) {
            idempotencyTable.remove(idemKey, marker);
            throw e;
        }
    }

    /** 校验租户维度总借贷守恒：流水借方合计 == 贷方合计。 */
    public boolean isBalanced(String tenantId) {
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (LedgerEntry e : journal) {
            if (!e.tenantId().equals(tenantId)) {
                continue;
            }
            debit = debit.add(e.amount());
            credit = credit.add(e.amount());
        }
        return debit.compareTo(credit) == 0;
    }

    /**
     * 生成按租户的对账报告（一致快照：持快照写锁，并发写入期间不会读到中间状态）。
     *
     * <p>报告内容：借方/贷方独立汇总；每个账户由流水推出的余额与系统余额逐户核对；
     * 对不上的账户给出差额、相关分录序号与逐笔重算不一致的分录序号。
     */
    public ReconciliationReport reconcile(String tenantId) {
        snapshotLock.writeLock().lock();
        try {
            List<LedgerEntry> entries = auditTrail(tenantId);

            // 借方/贷方独立汇总
            BigDecimal totalDebits = BigDecimal.ZERO;
            BigDecimal totalCredits = BigDecimal.ZERO;
            // 由流水逐户推出余额：贷入为正、借出为负（虚账户只参与总额，不产生账户行）
            Map<String, BigDecimal> derived = new HashMap<>();
            Map<String, List<Long>> sequencesByAccount = new HashMap<>();
            for (LedgerEntry e : entries) {
                totalDebits = totalDebits.add(e.amount());
                totalCredits = totalCredits.add(e.amount());
                if (!OPENING_EQUITY_ACCOUNT.equals(e.debitAccountId())) {
                    derived.merge(e.debitAccountId(), e.amount().negate(), BigDecimal::add);
                    sequencesByAccount.computeIfAbsent(e.debitAccountId(), k -> new ArrayList<>())
                            .add(e.sequence());
                }
                derived.merge(e.creditAccountId(), e.amount(), BigDecimal::add);
                sequencesByAccount.computeIfAbsent(e.creditAccountId(), k -> new ArrayList<>())
                        .add(e.sequence());
            }

            List<ReconciliationReport.AccountReconciliation> rows = new ArrayList<>();
            boolean allMatched = true;
            for (Account account : accounts.values()) {
                if (!account.getTenantId().equals(tenantId)) {
                    continue;
                }
                BigDecimal derivedBalance = derived.getOrDefault(account.getAccountId(), BigDecimal.ZERO);
                BigDecimal systemBalance = account.getBalance();
                BigDecimal difference = systemBalance.subtract(derivedBalance);
                boolean matched = difference.compareTo(BigDecimal.ZERO) == 0;
                allMatched &= matched;
                List<Long> entrySequences = sequencesByAccount
                        .getOrDefault(account.getAccountId(), List.of());
                List<Long> divergent = matched ? List.of()
                        : findDivergentSequences(entries, account.getAccountId());
                rows.add(new ReconciliationReport.AccountReconciliation(
                        account.getAccountId(), derivedBalance, systemBalance, difference,
                        matched, entrySequences, divergent));
            }
            rows.sort(java.util.Comparator.comparing(ReconciliationReport.AccountReconciliation::accountId));

            boolean totalsBalanced = totalDebits.compareTo(totalCredits) == 0;
            return new ReconciliationReport(tenantId, entries.size(), totalDebits, totalCredits,
                    totalsBalanced, rows, totalsBalanced && allMatched);
        } finally {
            snapshotLock.writeLock().unlock();
        }
    }

    /**
     * 逐笔重算该账户的余额序列，找出“分录记载的过账后余额”与“重算值”不一致的分录序号。
     */
    private List<Long> findDivergentSequences(List<LedgerEntry> entries, String accountId) {
        List<Long> divergent = new ArrayList<>();
        BigDecimal running = BigDecimal.ZERO;
        for (LedgerEntry e : entries) {
            BigDecimal recorded = null;
            if (e.debitAccountId().equals(accountId)) {
                running = running.subtract(e.amount());
                recorded = e.debitBalanceAfter();
            } else if (e.creditAccountId().equals(accountId)) {
                running = running.add(e.amount());
                recorded = e.creditBalanceAfter();
            } else {
                continue;
            }
            if (recorded != null && recorded.compareTo(running) != 0) {
                divergent.add(e.sequence());
            }
        }
        return divergent;
    }

    /**
     * 仅用于测试：人为制造账实不符（绕过流水直接变动余额），验证对账报告能定位差异。
     */
    public void tamperBalanceForTesting(String tenantId, String accountId, BigDecimal delta) {
        Account account = accounts.get(key(tenantId, accountId));
        if (account == null) {
            throw new IllegalArgumentException("account not found for tenant: " + key(tenantId, accountId));
        }
        account.credit(delta);
    }
}
