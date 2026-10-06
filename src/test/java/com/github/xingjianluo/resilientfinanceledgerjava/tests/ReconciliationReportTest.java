package com.github.xingjianluo.resilientfinanceledgerjava.tests;

import com.github.xingjianluo.resilientfinanceledgerjava.domain.ReconciliationReport;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferRequest;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对账报告：借/贷独立汇总、逐户“流水推出余额 vs 系统余额”核对、
 * 差异可定位到租户/账户/金额/分录，并发写入下报告为一致快照不误报。
 */
class ReconciliationReportTest {

    private static ReconciliationReport.AccountReconciliation row(ReconciliationReport report,
                                                                  String accountId) {
        return report.accounts().stream()
                .filter(a -> a.accountId().equals(accountId)).findFirst().orElseThrow();
    }

    @Test
    void cleanLedgerProducesConsistentReport() {
        LedgerService ledger = new LedgerService(new EventStore(), new EventUpgrader());
        System.out.println("[输入] t1: A=500, B=200, 转账 A->B=120; t2: A=999");
        ledger.createAccount("t1", "A", "CNY", new BigDecimal("500"));
        ledger.createAccount("t1", "B", "CNY", new BigDecimal("200"));
        ledger.createAccount("t2", "A", "CNY", new BigDecimal("999"));
        ledger.transfer(new TransferRequest("r1", "t1", "A", "B", new BigDecimal("120"), "CNY"));

        ReconciliationReport report = ledger.reconcile("t1");
        System.out.println("[判定] t1 报告: 借方合计=" + report.totalDebits()
                + ", 贷方合计=" + report.totalCredits() + ", 分录数=" + report.entryCount());
        for (ReconciliationReport.AccountReconciliation a : report.accounts()) {
            System.out.println("[判定] 账户 " + a.accountId() + " 推出=" + a.derivedBalance()
                    + " 系统=" + a.systemBalance() + " matched=" + a.matched());
        }
        System.out.println("[结果] consistent=" + report.consistent());

        assertTrue(report.totalsBalanced(), "借方合计必须等于贷方合计");
        assertEquals(0, new BigDecimal("820").compareTo(report.totalDebits()),
                "借方独立汇总 = 500 + 200 + 120");
        assertEquals(0, new BigDecimal("820").compareTo(report.totalCredits()));
        assertEquals(3, report.entryCount(), "2 条期初 + 1 条转账");
        assertEquals(2, report.accounts().size(), "报告只含本租户账户（租户隔离）");
        assertEquals(0, new BigDecimal("380").compareTo(row(report, "A").derivedBalance()));
        assertEquals(0, new BigDecimal("320").compareTo(row(report, "B").derivedBalance()));
        assertTrue(report.consistent(), "无差异时报告必须一致");

        ReconciliationReport t2 = ledger.reconcile("t2");
        assertEquals(1, t2.accounts().size(), "t2 报告看不到 t1 的账户");
        assertTrue(t2.consistent());
    }

    @Test
    void tamperedBalanceIsLocatedByReport() {
        LedgerService ledger = new LedgerService(new EventStore(), new EventUpgrader());
        System.out.println("[输入] t1: A=500, B=200, 转账 A->B=120; 随后人为把 B 余额多改 +50");
        ledger.createAccount("t1", "A", "CNY", new BigDecimal("500"));
        ledger.createAccount("t1", "B", "CNY", new BigDecimal("200"));
        ledger.transfer(new TransferRequest("r1", "t1", "A", "B", new BigDecimal("120"), "CNY"));
        ledger.tamperBalanceForTesting("t1", "B", new BigDecimal("50"));

        ReconciliationReport report = ledger.reconcile("t1");
        ReconciliationReport.AccountReconciliation rowB = row(report, "B");
        ReconciliationReport.AccountReconciliation rowA = row(report, "A");
        System.out.println("[判定] B: 推出=" + rowB.derivedBalance() + " 系统=" + rowB.systemBalance()
                + " 差额=" + rowB.difference() + " 涉及分录=" + rowB.entrySequences());
        System.out.println("[结果] consistent=" + report.consistent() + ", A.matched=" + rowA.matched()
                + ", B.matched=" + rowB.matched());

        assertFalse(report.consistent(), "账实不符时报告必须不一致");
        assertEquals("t1", report.tenantId(), "报告必须指出差异所在租户");
        assertTrue(rowA.matched(), "未被篡改的账户必须核对一致");
        assertFalse(rowB.matched(), "被篡改的账户必须被定位出来");
        assertEquals(0, new BigDecimal("50").compareTo(rowB.difference()),
                "差额必须等于人为制造的差异金额");
        assertEquals(0, new BigDecimal("320").compareTo(rowB.derivedBalance()));
        assertEquals(0, new BigDecimal("370").compareTo(rowB.systemBalance()));
        assertEquals(List.of(2L, 3L), rowB.entrySequences(),
                "必须给出该账户的相关分录序号以定位差异（期初 seq=2, 转账 seq=3）");
        assertTrue(report.totalsBalanced(), "总额仍配平，差异定位靠逐户核对");
    }

    @Test
    void reconciliationUnderConcurrentWritesNeverReportsFalsePositive() throws Exception {
        LedgerService ledger = new LedgerService(new EventStore(), new EventUpgrader());
        System.out.println("[输入] A=100000, B=100000; 8 线程并发双向转账, 主线程并发对账 200 次");
        ledger.createAccount("t1", "A", "CNY", new BigDecimal("100000"));
        ledger.createAccount("t1", "B", "CNY", new BigDecimal("100000"));

        int threads = 8;
        AtomicInteger nextRequest = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<ReconciliationReport> reports = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 50; j++) {
                        int id = nextRequest.getAndIncrement();
                        TransferRequest req = n % 2 == 0
                                ? new TransferRequest("c-" + id, "t1", "A", "B", new BigDecimal("1"), "CNY")
                                : new TransferRequest("c-" + id, "t1", "B", "A", new BigDecimal("1"), "CNY");
                        ledger.transfer(req);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        for (int i = 0; i < 200; i++) {
            reports.add(ledger.reconcile("t1"));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        long inconsistent = reports.stream().filter(r -> !r.consistent()).count();
        System.out.println("[判定] 并发期间对账 " + reports.size() + " 次, 不一致报告数=" + inconsistent);
        ReconciliationReport finalReport = ledger.reconcile("t1");
        System.out.println("[结果] 终态: consistent=" + finalReport.consistent()
                + ", 借方合计=" + finalReport.totalDebits()
                + ", A=" + row(finalReport, "A").systemBalance()
                + ", B=" + row(finalReport, "B").systemBalance());

        assertEquals(0, inconsistent, "并发写入下对账不得读到中间状态而误报不平");
        assertTrue(finalReport.consistent(), "终态必须一致");
        assertEquals(0, new BigDecimal("200000").compareTo(
                row(finalReport, "A").systemBalance().add(row(finalReport, "B").systemBalance())),
                "总额必须守恒");
    }
}
