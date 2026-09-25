package com.github.xingjianluo.resilientfinanceledgerjava.ledger;

import com.github.xingjianluo.resilientfinanceledgerjava.domain.Account;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.JournalEntry;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.RejectionReason;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferEvent;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 复式记账核心服务。
 *
 * 一致性规则：
 *  - 全部状态变更（校验、事件追加、余额过账、流水登记、幂等记录）在同一把锁内完成，
 *    并发提交不会透支、丢失更新或重复入账；
 *  - 幂等键 (tenantId, idempotencyKey) 唯一映射到一个交易号与结果，重复提交返回首次结果；
 *  - 事件追加到 EventStore 后才过账，重启后用同一 EventStore 构造新实例即可回放恢复；
 *  - 回放按全局序号排序（纠正乱序），按交易号去重（重复事件不产生重复流水），
 *    旧版本事件经 Upcaster 升级，无法兼容时明确拒绝。
 */
public class LedgerService {

    /** 期初余额的对方科目（租户级权益账户），保证总借贷守恒。 */
    public static final String EQUITY_ACCOUNT_PREFIX = "EQUITY:";

    private final EventStore eventStore;
    private final EventUpcaster upcaster;

    /** 全局锁：所有状态变更在其保护下串行完成。 */
    private final Object lock = new Object();

    private final Map<String, Account> accounts = new LinkedHashMap<>();      // tenant|account -> Account
    private final Map<String, TransferResult> idempotencyIndex = new LinkedHashMap<>(); // tenant|key -> result
    private final Map<String, Long> appliedEvents = new LinkedHashMap<>();    // transactionId -> sequence
    private final List<JournalEntry> journal = new ArrayList<>();

    public LedgerService(EventStore eventStore, EventUpcaster upcaster) {
        this.eventStore = eventStore;
        this.upcaster = upcaster;
    }

    private static String key(String tenantId, String id) {
        return tenantId + "|" + id;
    }

    // ---------- 账户 ----------

    /** 开户并登记期初余额（借：租户权益，贷：账户），保证守恒。重复开户幂等返回已有账户。 */
    public Account openAccount(String tenantId, String accountId, String currency, long initialBalance) {
        synchronized (lock) {
            Account existing = accounts.get(key(tenantId, accountId));
            if (existing != null) {
                return existing;
            }
            Account account = new Account(tenantId, accountId, currency, initialBalance);
            accounts.put(key(tenantId, accountId), account);
            if (initialBalance > 0) {
                String equityId = EQUITY_ACCOUNT_PREFIX + tenantId;
                accounts.putIfAbsent(key(tenantId, equityId),
                        new Account(tenantId, equityId, currency, 0, true));
                Account equity = accounts.get(key(tenantId, equityId));
                equity.debit(initialBalance); // 权益账户允许为负（负债方向）
                journal.add(new JournalEntry(0L, "OPENING:" + accountId, tenantId, equityId,
                        JournalEntry.Direction.DEBIT, initialBalance, currency,
                        equity.balance(), "opening balance equity offset"));
                journal.add(new JournalEntry(0L, "OPENING:" + accountId, tenantId, accountId,
                        JournalEntry.Direction.CREDIT, initialBalance, currency,
                        account.balance(), "opening balance"));
            }
            return account;
        }
    }

    // ---------- 转账提交（幂等） ----------

    public TransferResult submitTransfer(String tenantId, String idempotencyKey,
                                         String fromAccountId, String toAccountId,
                                         long amount, String currency, String memo) {
        synchronized (lock) {
            String idemKey = key(tenantId, idempotencyKey);
            TransferResult prior = idempotencyIndex.get(idemKey);
            if (prior != null) {
                return prior; // 重复提交：返回首次结果，不重复入账
            }
            TransferResult result = validateAndApply(tenantId, idempotencyKey,
                    fromAccountId, toAccountId, amount, currency, memo);
            idempotencyIndex.put(idemKey, result);
            return result;
        }
    }

    private TransferResult validateAndApply(String tenantId, String idempotencyKey,
                                            String fromAccountId, String toAccountId,
                                            long amount, String currency, String memo) {
        if (amount <= 0) {
            return TransferResult.rejected(RejectionReason.INVALID_AMOUNT, "amount must be > 0: " + amount);
        }
        Account from = accounts.get(key(tenantId, fromAccountId));
        Account to = accounts.get(key(tenantId, toAccountId));
        if (from == null || to == null) {
            // 区分“账户不存在”与“账户属于其他租户”：仅针对缺失的那个账户判定
            String missingId = from == null ? fromAccountId : toAccountId;
            boolean existsElsewhere = accounts.values().stream().anyMatch(a ->
                    !a.tenantId().equals(tenantId) && a.accountId().equals(missingId));
            if (existsElsewhere) {
                return TransferResult.rejected(RejectionReason.TENANT_MISMATCH,
                        "account " + missingId + " not owned by tenant " + tenantId);
            }
            return TransferResult.rejected(RejectionReason.ACCOUNT_NOT_FOUND,
                    "account not found: " + missingId);
        }
        if (!from.currency().equals(currency) || !to.currency().equals(currency)) {
            return TransferResult.rejected(RejectionReason.CURRENCY_MISMATCH,
                    "expected " + from.currency() + "/" + to.currency() + " got " + currency);
        }
        if (from.balance() < amount) {
            return TransferResult.rejected(RejectionReason.INSUFFICIENT_FUNDS,
                    "balance=" + from.balance() + " amount=" + amount);
        }
        String transactionId = tenantId + ":" + idempotencyKey;
        TransferEvent event = eventStore.append(
                TransferEvent.of(transactionId, tenantId, fromAccountId, toAccountId, amount, currency, memo));
        if (!appliedEvents.containsKey(transactionId)) {
            // 重启后幂等索引已丢失：事件存储去重 + 已应用集合共同保证不重复入账
            applyEvent(event);
        }
        return TransferResult.ok(transactionId);
    }

    /** 在锁内应用事件：借贷过账 + 登记双向流水。 */
    private void applyEvent(TransferEvent event) {
        Account from = accounts.get(key(event.tenantId(), event.fromAccountId()));
        Account to = accounts.get(key(event.tenantId(), event.toAccountId()));
        from.debit(event.amount());
        to.credit(event.amount());
        String reason = "transfer " + event.transactionId()
                + (event.memo() == null ? "" : " (" + event.memo() + ")");
        journal.add(new JournalEntry(event.sequence(), event.transactionId(), event.tenantId(),
                event.fromAccountId(), JournalEntry.Direction.DEBIT, event.amount(),
                event.currency(), from.balance(), reason));
        journal.add(new JournalEntry(event.sequence(), event.transactionId(), event.tenantId(),
                event.toAccountId(), JournalEntry.Direction.CREDIT, event.amount(),
                event.currency(), to.balance(), reason));
        appliedEvents.put(event.transactionId(), event.sequence());
    }

    // ---------- 回放 ----------

    /** 从事件存储回放：按序号排序、按交易号去重、旧版本升级、不兼容则拒绝。 */
    public ReplayResult replayFromStore() {
        return replay(eventStore.all());
    }

    /** 回放给定事件（可含重复、乱序、旧版本）。 */
    public ReplayResult replay(List<TransferEvent> events) {
        synchronized (lock) {
            List<TransferEvent> ordered = events.stream()
                    .sorted(java.util.Comparator.comparingLong(TransferEvent::sequence))
                    .toList();
            int applied = 0, skipped = 0;
            List<ReplayResult.Rejection> rejections = new ArrayList<>();
            for (TransferEvent raw : ordered) {
                TransferEvent event;
                try {
                    event = upcaster.toCurrentVersion(raw);
                } catch (EventUpcaster.EventUpgradeException e) {
                    rejections.add(new ReplayResult.Rejection(
                            raw.transactionId(), e.reason(), e.getMessage()));
                    continue;
                }
                if (appliedEvents.containsKey(event.transactionId())) {
                    skipped++; // 重复事件：不改变余额、不重复生成流水
                    continue;
                }
                if (!accounts.containsKey(key(event.tenantId(), event.fromAccountId()))
                        || !accounts.containsKey(key(event.tenantId(), event.toAccountId()))) {
                    rejections.add(new ReplayResult.Rejection(event.transactionId(),
                            RejectionReason.ACCOUNT_NOT_FOUND,
                            "account missing for event " + event.transactionId()));
                    continue;
                }
                applyEvent(event);
                applied++;
            }
            return new ReplayResult(applied, skipped, List.copyOf(rejections));
        }
    }

    /** 回放结果：应用数、去重跳过数、明确拒绝列表。 */
    public record ReplayResult(int applied, int skippedDuplicates, List<Rejection> rejections) {
        public record Rejection(String transactionId, RejectionReason reason, String detail) {}
    }

    // ---------- 查询（按租户隔离） ----------

    public Optional<Long> balance(String tenantId, String accountId) {
        synchronized (lock) {
            Account account = accounts.get(key(tenantId, accountId));
            return account == null ? Optional.empty() : Optional.of(account.balance());
        }
    }

    public List<JournalEntry> journal(String tenantId) {
        synchronized (lock) {
            return journal.stream().filter(j -> j.tenantId().equals(tenantId)).toList();
        }
    }

    /** 守恒校验：租户内全部流水有符号金额之和必须为 0（总借贷相等）。 */
    public long conservationSum(String tenantId) {
        synchronized (lock) {
            return journal.stream()
                    .filter(j -> j.tenantId().equals(tenantId))
                    .mapToLong(JournalEntry::signedAmount)
                    .sum();
        }
    }
}
