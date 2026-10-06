package com.github.xingjianluo.resilientfinanceledgerjava.tests;

import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferRequest;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferResult;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferStatus;
import com.github.xingjianluo.resilientfinanceledgerjava.event.EventUpgrader;
import com.github.xingjianluo.resilientfinanceledgerjava.service.EventStore;
import com.github.xingjianluo.resilientfinanceledgerjava.service.LedgerService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发转账：不得透支、丢失更新或重复入账，总额守恒。
 */
class ConcurrentTransferTest {

    @Test
    void concurrentTransfersNeverOverdraftAndConserveTotal() throws Exception {
        LedgerService ledger = new LedgerService(new EventStore(), new EventUpgrader());
        String tenant = "t1";
        BigDecimal initial = new BigDecimal("1000");
        ledger.createAccount(tenant, "A", "CNY", initial);
        ledger.createAccount(tenant, "B", "CNY", initial);
        System.out.println("[输入] A=1000, B=1000, 64 线程并发双向转账, 金额=10");

        int threads = 64;
        BigDecimal amount = new BigDecimal("10");
        ConcurrentLinkedQueue<TransferResult> results = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            final int n = i;
            pool.submit(() -> {
                try {
                    start.await();
                    // 一半 A->B，一半 B->A，制造竞争
                    TransferRequest req = n % 2 == 0
                            ? new TransferRequest("req-" + n, tenant, "A", "B", amount, "CNY")
                            : new TransferRequest("req-" + n, tenant, "B", "A", amount, "CNY");
                    results.add(ledger.transfer(req));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        long accepted = results.stream().filter(r -> r.status() == TransferStatus.ACCEPTED).count();
        long rejected = results.stream().filter(r -> r.status() == TransferStatus.REJECTED).count();
        BigDecimal balanceA = ledger.balanceOf(tenant, "A");
        BigDecimal balanceB = ledger.balanceOf(tenant, "B");
        System.out.println("[判定] accepted=" + accepted + ", rejected=" + rejected
                + ", 流水条数=" + ledger.auditTrail(tenant).size());
        System.out.println("[结果] A=" + balanceA + ", B=" + balanceB
                + ", 总额=" + balanceA.add(balanceB) + ", 借贷守恒=" + ledger.isBalanced(tenant));

        assertEquals(threads, accepted + rejected, "每个请求必须有且仅有一个确定结果");
        assertEquals(accepted + 2, ledger.auditTrail(tenant).size(),
                "2 条期初分录 + 成功转账分录（无丢失更新/重复入账）");
        assertEquals(0, initial.add(initial).compareTo(balanceA.add(balanceB)), "总额必须守恒");
        assertTrue(balanceA.compareTo(BigDecimal.ZERO) >= 0 && balanceB.compareTo(BigDecimal.ZERO) >= 0,
                "任何账户不得透支");
        assertTrue(ledger.isBalanced(tenant), "总借贷必须守恒");
    }

    @Test
    void concurrentOversubscriptionNeverOverdrafts() throws Exception {
        LedgerService ledger = new LedgerService(new EventStore(), new EventUpgrader());
        String tenant = "t1";
        ledger.createAccount(tenant, "A", "CNY", new BigDecimal("1000"));
        ledger.createAccount(tenant, "B", "CNY", new BigDecimal("0"));
        System.out.println("[输入] A=1000, B=0, 200 线程并发 A->B, 每笔=10（超额竞争）");

        int threads = 200;
        ConcurrentLinkedQueue<TransferResult> results = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            final int n = i;
            pool.submit(() -> {
                try {
                    start.await();
                    results.add(ledger.transfer(new TransferRequest(
                            "over-" + n, tenant, "A", "B", new BigDecimal("10"), "CNY")));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        long accepted = results.stream().filter(r -> r.status() == TransferStatus.ACCEPTED).count();
        long insufficient = results.stream().filter(r -> r.reason()
                == com.github.xingjianluo.resilientfinanceledgerjava.domain.RejectionReason.INSUFFICIENT_FUNDS).count();
        BigDecimal balanceA = ledger.balanceOf(tenant, "A");
        BigDecimal balanceB = ledger.balanceOf(tenant, "B");
        System.out.println("[判定] accepted=" + accepted + ", INSUFFICIENT_FUNDS=" + insufficient);
        System.out.println("[结果] A=" + balanceA + ", B=" + balanceB);

        assertEquals(100, accepted, "1000/10 恰好允许 100 笔成功");
        assertEquals(100, insufficient, "其余 100 笔必须以 INSUFFICIENT_FUNDS 拒绝");
        assertEquals(0, BigDecimal.ZERO.compareTo(balanceA), "A 不得透支");
        assertEquals(0, new BigDecimal("1000").compareTo(balanceB), "B 必须恰好收到全部成功金额");
        assertEquals(accepted + 1, ledger.auditTrail(tenant).size(), "1 条期初分录 + 成功转账分录");
    }
}
