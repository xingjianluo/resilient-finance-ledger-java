package com.github.xingjianluo.resilientfinanceledgerjava.tests;

import com.github.xingjianluo.resilientfinanceledgerjava.domain.RejectionReason;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferResult;
import com.github.xingjianluo.resilientfinanceledgerjava.ledger.EventStore;
import com.github.xingjianluo.resilientfinanceledgerjava.ledger.EventUpcaster;
import com.github.xingjianluo.resilientfinanceledgerjava.ledger.LedgerService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发提交：不得透支、不得丢失更新、不得重复入账。
 */
class ConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);

    @Test
    void concurrentTransfersNeverOverdraftOrLoseUpdates() throws Exception {
        LedgerService ledger = new LedgerService(new EventStore(), new EventUpcaster());
        int accounts = 10;
        long initial = 1000;
        for (int i = 0; i < accounts; i++) {
            ledger.openAccount("t1", "acc" + i, "CNY", initial);
        }
        int threads = 16;
        int perThread = 50;
        long amount = 10;
        log.info("[input] {} 账户 x {} 初始余额, {} 线程 x {} 笔随机转账, 每笔 {}",
                accounts, initial, threads, perThread, amount);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        ConcurrentLinkedQueue<TransferResult> results = new ConcurrentLinkedQueue<>();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int seed = t;
            tasks.add(() -> {
                java.util.Random rnd = new java.util.Random(seed);
                for (int i = 0; i < perThread; i++) {
                    int from = rnd.nextInt(accounts);
                    int to = (from + 1 + rnd.nextInt(accounts - 1)) % accounts;
                    results.add(ledger.submitTransfer("t1", "k-" + seed + "-" + i,
                            "acc" + from, "acc" + to, amount, "CNY", null));
                }
                return null;
            });
        }
        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(pool.submit(task));
        }
        for (Future<Void> f : futures) {
            f.get();
        }
        pool.shutdown();

        long accepted = results.stream().filter(TransferResult::accepted).count();
        long rejectedInsufficient = results.stream()
                .filter(r -> !r.accepted() && r.rejectionReason() == RejectionReason.INSUFFICIENT_FUNDS).count();
        log.info("[decision] 总提交={} 成功={} 余额不足拒绝={} 其他拒绝={}",
                results.size(), accepted, rejectedInsufficient, results.size() - accepted - rejectedInsufficient);
        assertEquals(threads * perThread, results.size(), "丢失更新：提交数与结果数不一致");

        long total = 0;
        for (int i = 0; i < accounts; i++) {
            long balance = ledger.balance("t1", "acc" + i).orElseThrow();
            assertTrue(balance >= 0, "acc" + i + " 透支: " + balance);
            total += balance;
        }
        log.info("[result] 各账户余额均 >= 0, 总额={} (期望 {})", total, accounts * initial);
        assertEquals(accounts * initial, total, "总额必须守恒（无透支、无凭空创造）");
        assertEquals(0, ledger.conservationSum("t1"), "总借贷必须守恒");
        // 流水条数 = 期初 2*accounts + 成功交易 * 2
        assertEquals(2L * accounts + 2 * accepted, ledger.journal("t1").size(), "不得重复入账");
    }

    @Test
    void concurrentDuplicateSubmissionsApplyOnce() throws Exception {
        LedgerService ledger = new LedgerService(new EventStore(), new EventUpcaster());
        ledger.openAccount("t1", "a", "CNY", 100);
        ledger.openAccount("t1", "b", "CNY", 0);
        int threads = 32;
        log.info("[input] {} 线程用同一幂等键 dup-1 并发提交 a->b 50", threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        ConcurrentLinkedQueue<TransferResult> results = new ConcurrentLinkedQueue<>();
        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                results.add(ledger.submitTransfer("t1", "dup-1", "a", "b", 50, "CNY", null));
                return null;
            }));
        }
        for (Future<Void> f : futures) {
            f.get();
        }
        pool.shutdown();

        long distinctTx = results.stream().map(TransferResult::transactionId).distinct().count();
        log.info("[decision] 全部成功={} 不同交易号数={}",
                results.stream().allMatch(TransferResult::accepted), distinctTx);
        assertTrue(results.stream().allMatch(TransferResult::accepted));
        assertEquals(1, distinctTx, "同一幂等键必须解析为同一交易");
        assertEquals(50, ledger.balance("t1", "a").orElseThrow());
        assertEquals(50, ledger.balance("t1", "b").orElseThrow());
        assertEquals(4, ledger.journal("t1").size(), "只入账一次：a期初2条(b期初为0无流水)+本次2条");
        log.info("[result] a=50 b=50, 仅入账一次");
    }
}
