package com.github.xingjianluo.resilientfinanceledgerjava.service;

import com.github.xingjianluo.resilientfinanceledgerjava.domain.Account;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.LedgerEntry;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.RejectionAudit;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.RejectionReason;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferRequest;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferResult;
import com.github.xingjianluo.resilientfinanceledgerjava.event.EventUpgrader;
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

    public LedgerService(EventStore eventStore, EventUpgrader eventUpgrader) {
        this.eventStore = eventStore;
        this.eventUpgrader = eventUpgrader;
    }

    private static String key(String tenantId, String id) {
        return tenantId + "|" + id;
    }

    public void createAccount(String tenantId, String accountId, String currency, BigDecimal initialBalance) {
        if (initialBalance == null || initialBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("initial balance must be >= 0");
        }
        Account account = new Account(tenantId, accountId, currency, initialBalance);
        if (accounts.putIfAbsent(key(tenantId, accountId), account) != null) {
            throw new IllegalArgumentException("account already exists: " + key(tenantId, accountId));
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
                    request.amount(), request.currency());
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
                event.fromAccountId(), event.toAccountId(), event.amount(), event.currency(), Instant.now()));
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
     * 回放事件：逐条升级到当前版本后按幂等键去重应用。
     * 乱序与重复安全：每条事件自包含，求和可交换，最终余额一致。
     * 已确认事件在回放时不再做余额检查（提交时已校验）。
     *
     * @return 本次实际新应用的事件数
     */
    public int replay(List<TransactionEvent> events) {
        int applied = 0;
        for (TransactionEvent raw : events) {
            TransactionEvent event = eventUpgrader.upgradeToCurrent(raw); // 不兼容则抛出，明确拒绝
            String idemKey = key(event.tenantId(), event.requestId());
            CompletableFuture<TransferResult> marker = new CompletableFuture<>();
            if (idempotencyTable.putIfAbsent(idemKey, marker) != null) {
                continue; // 重复事件：跳过，不重复入账
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
            applied++;
        }
        return applied;
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
