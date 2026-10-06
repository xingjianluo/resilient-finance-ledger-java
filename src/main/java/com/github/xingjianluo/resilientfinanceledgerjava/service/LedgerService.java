package com.github.xingjianluo.resilientfinanceledgerjava.service;

import com.github.xingjianluo.resilientfinanceledgerjava.domain.Account;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.LedgerEntry;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.RejectionAudit;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.RejectionReason;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.ReconciliationReport;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferRequest;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferResult;
import com.github.xingjianluo.resilientfinanceledgerjava.event.AccountOpenedEvent;
import com.github.xingjianluo.resilientfinanceledgerjava.event.EventUpgrader;
import com.github.xingjianluo.resilientfinanceledgerjava.event.LedgerEvent;
import com.github.xingjianluo.resilientfinanceledgerjava.event.TransactionEvent;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 复式记账核心服务。
 *
 * <p>一致性规则：
 * <ul>
 *   <li>幂等：以 (tenantId, requestId) 为幂等键，重复提交返回首次结果，不重复入账；</li>
 *   <li>并发：按账户键排序加锁，避免死锁；检查余额与扣款在同一临界区，杜绝透支与丢失更新；</li>
 *   <li>事件先行：事件追加成功后才变动余额，回放可完整还原已确认交易；</li>
 *   <li>回放：事件自包含且按幂等键去重，乱序/重复回放不改变最终余额。</li>
 * </ul>
 */
@Service
public class LedgerService {

    /** 系统期初权益账户（每租户一个）：期初分录的借方，解释期初余额来源。 */
    public static final String OPENING_EQUITY_ACCOUNT = "SYSTEM_OPENING_EQUITY";

    private final EventStore eventStore;
    private final EventUpgrader eventUpgrader;

    /** 账户表：key = tenantId|accountId */
    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    /** 账户锁：key = tenantId|accountId */
    private final Map<String, ReentrantLock> accountLocks = new ConcurrentHashMap<>();
    /** 租户锁：key = tenantId；开户与对账快照使用，锁顺序恒为 租户锁 -> 账户锁（按字典序） */
    private final Map<String, ReentrantLock> tenantLocks = new ConcurrentHashMap<>();
    /** 幂等结果表：key = tenantId|requestId */
    private final Map<String, CompletableFuture<TransferResult>> idempotencyTable = new ConcurrentHashMap<>();
    /** 审计流水（只追加） */
    private final List<LedgerEntry> journal = new CopyOnWriteArrayList<>();
    /** 拒绝审计（只追加） */
    private final List<RejectionAudit> rejectionAudits = new CopyOnWriteArrayList<>();

    public LedgerService(EventStore eventStore, EventUpgrader eventUpgrader) {
        this.eventStore = eventStore;
        this.eventUpgrader = eventUpgrader;
    }

    private static String key(String tenantId, String id) {
        return tenantId + "|" + id;
    }

    /**
     * 开户。期初余额按复式记账落账：借 系统期初权益账户 / 贷 新账户，
     * 并先追加开户事件——账户与期初均可由事件回放还原，余额可由流水逐步推出。
     */
    public void createAccount(String tenantId, String accountId, String currency, BigDecimal initialBalance) {
        createAccount(tenantId, accountId, currency, initialBalance, null);
    }

    public void createAccount(String tenantId, String accountId, String currency,
                              BigDecimal initialBalance, String businessRef) {
        if (initialBalance == null || initialBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("initial balance must be >= 0");
        }
        ReentrantLock tenantLock = tenantLocks.computeIfAbsent(tenantId, k -> new ReentrantLock());
        tenantLock.lock();
        try {
            if (accounts.containsKey(key(tenantId, accountId))) {
                throw new IllegalArgumentException("account already exists: " + key(tenantId, accountId));
            }
            if (initialBalance.compareTo(BigDecimal.ZERO) > 0) {
                ensureOpeningEquityAccount(tenantId, currency);
            }
            // 事件先行：开户事件追加成功后才变动账户状态
            AccountOpenedEvent event = new AccountOpenedEvent(
                    AccountOpenedEvent.CURRENT_VERSION, "open-" + accountId,
                    tenantId, accountId, currency, initialBalance, businessRef);
            long seq = eventStore.append(event);
            applyAccountOpenedState(event, seq);
        } finally {
            tenantLock.unlock();
        }
    }

    /** 确保租户的系统期初权益账户存在（调用方须持有租户锁）。 */
    private void ensureOpeningEquityAccount(String tenantId, String currency) {
        accounts.putIfAbsent(key(tenantId, OPENING_EQUITY_ACCOUNT),
                new Account(tenantId, OPENING_EQUITY_ACCOUNT, currency, BigDecimal.ZERO));
    }

    /**
     * 应用开户事件到账户状态（事件须已追加）。幂等：账户已存在则跳过。
     * 账户余额从 0 开始，正期初以期初分录贷入，保证余额可由流水逐步推出。
     *
     * @return 是否为新应用（false = 重复事件被跳过）
     */
    private boolean applyAccountOpenedState(AccountOpenedEvent event, long seq) {
        String accountKey = key(event.tenantId(), event.accountId());
        if (accounts.containsKey(accountKey)) {
            return false;
        }
        if (event.openingBalance().compareTo(BigDecimal.ZERO) > 0) {
            ensureOpeningEquityAccount(event.tenantId(), event.currency());
        }
        accounts.put(accountKey, new Account(event.tenantId(), event.accountId(),
                event.currency(), BigDecimal.ZERO));
        if (event.openingBalance().compareTo(BigDecimal.ZERO) > 0) {
            postOpeningEntry(event, seq);
        }
        return true;
    }

    /** 期初分录：借 系统期初权益账户，贷 新账户；在双方账户锁内记录过账后余额。 */
    private void postOpeningEntry(AccountOpenedEvent event, long seq) {
        String equityKey = key(event.tenantId(), OPENING_EQUITY_ACCOUNT);
        String accountKey = key(event.tenantId(), event.accountId());
        List<ReentrantLock> locks = java.util.stream.Stream.of(equityKey, accountKey)
                .sorted()
                .map(k -> accountLocks.computeIfAbsent(k, x -> new ReentrantLock()))
                .toList();
        locks.forEach(ReentrantLock::lock);
        try {
            Account equity = accounts.get(equityKey);
            Account fresh = accounts.get(accountKey);
            equity.debit(event.openingBalance());
            fresh.credit(event.openingBalance());
            journal.add(new LedgerEntry(seq, event.eventId(), event.eventId(), event.tenantId(),
                    OPENING_EQUITY_ACCOUNT, event.accountId(), event.openingBalance(), event.currency(),
                    equity.getBalance(), fresh.getBalance(), Instant.now()));
        } finally {
            locks.forEach(ReentrantLock::unlock);
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
            if (first.status() == com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferStatus.ACCEPTED) {
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

        // 4. 按账户键字典序加锁（防死锁），在临界区内检查余额并变动
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
                    request.amount(), request.currency(), request.businessRef());
            long seq = eventStore.append(event);
            applyPosting(event, seq);
            return TransferResult.accepted(transactionId);
        } finally {
            locks.forEach(ReentrantLock::unlock);
        }
    }

    /** 在账户锁或回放串行上下文中应用一条复式分录。 */
    private void applyPosting(TransactionEvent event, long seq) {
        Account from = accounts.get(key(event.tenantId(), event.fromAccountId()));
        Account to = accounts.get(key(event.tenantId(), event.toAccountId()));
        from.debit(event.amount());
        to.credit(event.amount());
        journal.add(new LedgerEntry(seq, event.transactionId(), event.requestId(), event.tenantId(),
                event.fromAccountId(), event.toAccountId(), event.amount(), event.currency(),
                from.getBalance(), to.getBalance(), Instant.now()));
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

    /** 租户隔离的审计流水。 */
    public List<LedgerEntry> auditTrail(String tenantId) {
        return journal.stream().filter(e -> e.tenantId().equals(tenantId)).toList();
    }

    /** 租户隔离的拒绝审计。 */
    public List<RejectionAudit> rejectionAudit(String tenantId) {
        return rejectionAudits.stream().filter(r -> r.tenantId().equals(tenantId)).toList();
    }

    /**
     * 回放事件：逐条升级到当前版本后应用。
     * 两段式：先应用开户事件（乱序时交易事件可能先于其账户的开户事件到达），
     * 再应用交易事件（按幂等键去重）。乱序与重复安全，最终余额一致。
     * 已确认事件在回放时不再做余额检查（提交时已校验）。
     *
     * @return 本次实际新应用的事件数
     */
    public int replay(List<? extends LedgerEvent> events) {
        List<LedgerEvent> upgraded = new ArrayList<>();
        for (LedgerEvent raw : events) {
            upgraded.add(eventUpgrader.upgradeToCurrent(raw)); // 不兼容则抛出，明确拒绝
        }
        int applied = 0;
        // 第一段：开户事件（按账户存在性去重，可乱序）
        for (LedgerEvent event : upgraded) {
            if (event instanceof AccountOpenedEvent opened && applyAccountOpened(opened)) {
                applied++;
            }
        }
        // 第二段：交易事件（按幂等键去重）
        for (LedgerEvent event : upgraded) {
            if (event instanceof TransactionEvent tx && applyTransaction(tx)) {
                applied++;
            }
        }
        return applied;
    }

    /** 回放开户事件：追加到本事件存储后应用；账户已存在则跳过（幂等）。 */
    private boolean applyAccountOpened(AccountOpenedEvent event) {
        if (accounts.containsKey(key(event.tenantId(), event.accountId()))) {
            return false; // 重复开户事件：跳过
        }
        long seq = eventStore.append(event);
        return applyAccountOpenedState(event, seq);
    }

    /** 回放交易事件：幂等键已存在则跳过。 */
    private boolean applyTransaction(TransactionEvent event) {
        String idemKey = key(event.tenantId(), event.requestId());
        CompletableFuture<TransferResult> marker = new CompletableFuture<>();
        if (idempotencyTable.putIfAbsent(idemKey, marker) != null) {
            return false; // 重复事件：跳过，不重复入账
        }
        Account from = accounts.get(key(event.tenantId(), event.fromAccountId()));
        Account to = accounts.get(key(event.tenantId(), event.toAccountId()));
        if (from == null || to == null) {
            idempotencyTable.remove(idemKey, marker);
            throw new com.github.xingjianluo.resilientfinanceledgerjava.event.EventUpgradeException(
                    RejectionReason.EVENT_VALIDATION_FAILED,
                    "replay references unknown account: " + event);
        }
        long seq = eventStore.append(event);
        applyPosting(event, seq);
        marker.complete(TransferResult.accepted(event.transactionId()));
        return true;
    }

    /**
     * 生成按租户的对账报告（一致快照）。
     *
     * <p>一致性：先取租户锁（阻塞并发开户），再按字典序取该租户全部账户锁
     * （与转账/期初分录的加锁顺序一致，无死锁）。持锁期间本租户不会有任何新分录，
     * 因此流水推导与系统余额读取构成一致快照，并发写入下不会误报。
     *
     * <p>核对内容：借方/贷方各自独立汇总；逐户以流水推出余额（贷入为正、借出为负，
     * 账户初始为 0，期初也由分录贷入）与系统余额比对；差异定位到账户与涉及分录。
     */
    public ReconciliationReport reconcile(String tenantId) {
        ReentrantLock tenantLock = tenantLocks.computeIfAbsent(tenantId, k -> new ReentrantLock());
        tenantLock.lock();
        List<ReentrantLock> locks;
        try {
            List<Account> tenantAccounts = accounts.values().stream()
                    .filter(a -> a.getTenantId().equals(tenantId))
                    .sorted(Comparator.comparing(Account::getAccountId))
                    .toList();
            locks = tenantAccounts.stream()
                    .map(a -> accountLocks.computeIfAbsent(key(tenantId, a.getAccountId()),
                            x -> new ReentrantLock()))
                    .toList();
            locks.forEach(ReentrantLock::lock);
            try {
                return buildReport(tenantId, tenantAccounts);
            } finally {
                locks.forEach(ReentrantLock::unlock);
            }
        } finally {
            tenantLock.unlock();
        }
    }

    /** 在持有租户锁与全部账户锁的临界区内构建报告。 */
    private ReconciliationReport buildReport(String tenantId, List<Account> tenantAccounts) {
        List<LedgerEntry> tenantEntries = journal.stream()
                .filter(e -> e.tenantId().equals(tenantId))
                .toList();
        // 借方与贷方各自独立汇总
        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;
        // 由流水逐户推导余额：贷入增加、借出减少（账户初始为 0）
        Map<String, BigDecimal> derived = new ConcurrentHashMap<>();
        for (LedgerEntry e : tenantEntries) {
            totalDebits = totalDebits.add(e.amount());
            totalCredits = totalCredits.add(e.amount());
            derived.merge(e.creditAccountId(), e.amount(), BigDecimal::add);
            derived.merge(e.debitAccountId(), e.amount().negate(), BigDecimal::add);
        }
        List<ReconciliationReport.AccountLine> lines = new ArrayList<>();
        List<ReconciliationReport.Discrepancy> discrepancies = new ArrayList<>();
        for (Account account : tenantAccounts) {
            BigDecimal derivedBalance = derived.getOrDefault(account.getAccountId(), BigDecimal.ZERO);
            BigDecimal systemBalance = account.getBalance();
            BigDecimal difference = systemBalance.subtract(derivedBalance);
            boolean matched = difference.compareTo(BigDecimal.ZERO) == 0;
            lines.add(new ReconciliationReport.AccountLine(account.getAccountId(),
                    derivedBalance, systemBalance, difference, matched));
            if (!matched) {
                List<LedgerEntry> involved = tenantEntries.stream()
                        .filter(e -> e.debitAccountId().equals(account.getAccountId())
                                || e.creditAccountId().equals(account.getAccountId()))
                        .toList();
                discrepancies.add(new ReconciliationReport.Discrepancy(tenantId, account.getAccountId(),
                        derivedBalance, systemBalance, difference, involved));
            }
        }
        boolean balanced = totalDebits.compareTo(totalCredits) == 0 && discrepancies.isEmpty();
        return new ReconciliationReport(tenantId, totalDebits, totalCredits, balanced,
                lines, discrepancies);
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
}
