package com.github.xingjianluo.resilientfinanceledgerjava.tests;

import com.github.xingjianluo.resilientfinanceledgerjava.domain.EntryType;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.RejectionReason;
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
 * 幂等：重复提交（串行与并发）不得重复入账，且返回首次结果。
 */
class IdempotencyTest {

    private static long transferEntries(LedgerService ledger) {
        return ledger.auditTrail("t1").stream().filter(e -> e.type() == EntryType.TRANSFER).count();
    }

    @Test
    void duplicateSubmissionAppliedOnlyOnce() {
        LedgerService ledger = new LedgerService(new EventStore(), new EventUpgrader());
        ledger.createAccount("t1", "A", "CNY", new BigDecimal("500"));
        ledger.createAccount("t1", "B", "CNY", new BigDecimal("0"));
        TransferRequest req = new TransferRequest("req-1", "t1", "A", "B", new BigDecimal("100"), "CNY");
        System.out.println("[输入] 同一 requestId=req-1 串行提交 3 次, 金额=100");

        TransferResult r1 = ledger.transfer(req);
        TransferResult r2 = ledger.transfer(req);
        TransferResult r3 = ledger.transfer(req);
        System.out.println("[判定] r1=" + r1.status() + ", r2=" + r2.status() + ", r3=" + r3.status());
        System.out.println("[结果] A=" + ledger.balanceOf("t1", "A") + ", B=" + ledger.balanceOf("t1", "B")
                + ", 流水条数=" + ledger.auditTrail("t1").size());

        assertEquals(TransferStatus.ACCEPTED, r1.status());
        assertEquals(TransferStatus.DUPLICATE, r2.status());
        assertEquals(TransferStatus.DUPLICATE, r3.status());
        assertEquals(r1.transactionId(), r2.transactionId(), "幂等命中必须返回首次交易号");
        assertEquals(0, new BigDecimal("400").compareTo(ledger.balanceOf("t1", "A")), "只入账一次");
        assertEquals(0, new BigDecimal("100").compareTo(ledger.balanceOf("t1", "B")));
        assertEquals(1, transferEntries(ledger), "不得重复生成流水");
        assertEquals(3, ledger.auditTrail("t1").size(), "流水 = 2 条期初 + 1 条转账");
    }

    @Test
    void concurrentDuplicateSubmissionAppliedOnlyOnce() throws Exception {
        LedgerService ledger = new LedgerService(new EventStore(), new EventUpgrader());
        ledger.createAccount("t1", "A", "CNY", new BigDecimal("500"));
        ledger.createAccount("t1", "B", "CNY", new BigDecimal("0"));
        System.out.println("[输入] 32 线程并发提交同一 requestId=req-c, 金额=100");

        int threads = 32;
        ConcurrentLinkedQueue<TransferResult> results = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    results.add(ledger.transfer(
                            new TransferRequest("req-c", "t1", "A", "B", new BigDecimal("100"), "CNY")));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        long accepted = results.stream().filter(r -> r.status() == TransferStatus.ACCEPTED).count();
        long duplicates = results.stream().filter(r -> r.status() == TransferStatus.DUPLICATE).count();
        System.out.println("[判定] accepted=" + accepted + ", duplicate=" + duplicates);
        System.out.println("[结果] A=" + ledger.balanceOf("t1", "A") + ", B=" + ledger.balanceOf("t1", "B")
                + ", 流水条数=" + ledger.auditTrail("t1").size());

        assertEquals(1, accepted, "并发下同键请求只允许一笔成功");
        assertEquals(threads - 1, duplicates);
        assertEquals(0, new BigDecimal("100").compareTo(ledger.balanceOf("t1", "B")), "只入账一次");
        assertEquals(1, transferEntries(ledger));
    }

    @Test
    void rejectedRequestReplayReturnsSameStableReason() {
        LedgerService ledger = new LedgerService(new EventStore(), new EventUpgrader());
        ledger.createAccount("t1", "A", "CNY", new BigDecimal("50"));
        ledger.createAccount("t1", "B", "CNY", new BigDecimal("0"));
        TransferRequest req = new TransferRequest("req-x", "t1", "A", "B", new BigDecimal("100"), "CNY");
        System.out.println("[输入] 余额=50, 提交金额=100（不足），随后同 requestId 重试");

        TransferResult r1 = ledger.transfer(req);
        TransferResult r2 = ledger.transfer(req);
        System.out.println("[判定] r1.reason=" + r1.reason() + ", r2.reason=" + r2.reason());
        System.out.println("[结果] A=" + ledger.balanceOf("t1", "A") + ", 流水条数="
                + ledger.auditTrail("t1").size() + ", 拒绝审计条数=" + ledger.rejectionAudit("t1").size());

        assertEquals(TransferStatus.REJECTED, r1.status());
        assertEquals(RejectionReason.INSUFFICIENT_FUNDS, r1.reason());
        assertEquals(RejectionReason.INSUFFICIENT_FUNDS, r2.reason(), "重试必须返回相同的稳定原因");
        assertEquals(0, new BigDecimal("50").compareTo(ledger.balanceOf("t1", "A")), "拒绝不得变动余额");
        assertEquals(0, transferEntries(ledger), "拒绝不得生成转账流水");
        assertEquals(1, ledger.rejectionAudit("t1").size(), "拒绝原因必须可审计");
    }
}
