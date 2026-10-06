package com.github.xingjianluo.resilientfinanceledgerjava.tests;

import com.github.xingjianluo.resilientfinanceledgerjava.domain.Account;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.ReconciliationReport;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferRequest;
import com.github.xingjianluo.resilientfinanceledgerjava.event.EventUpgrader;
import com.github.xingjianluo.resilientfinanceledgerjava.service.EventStore;
import com.github.xingjianluo.resilientfinanceledgerjava.service.LedgerService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对账报告：按租户的一致快照；借贷独立汇总、逐户核对；
 * 差异定位到租户/账户/差额/涉及分录；并发写入下不误报。
 */
class ReconciliationReportTest {

    /** 测试专用：绕过账务规则直接改动系统余额，人为制造账实差异。 */
    @SuppressWarnings("unchecked")
    private static void tamperBalance(LedgerService ledger, String tenantId, String accountId,
                                      BigDecimal delta) throws Exception {
        Field f = LedgerService.class.getDeclaredField("accounts");
        f.setAccessible(true);
        Map<String, Account> accounts = (Map<String, Account>) f.get(ledger);
        Account account = accounts.get(tenantId + "|" + accountId);
        if (delta.signum() >= 0) {
            account.credit(delta);
        } else {
            account.debit(delta.negate());
        }
    }

    @Test
    void cleanLedgerReconcilesBalanced() {
        LedgerService ledger = new LedgerService(new EventStore(), new EventUpgrader());
        ledger.createAccount("t1", "A", "CNY", new BigDecimal("300"));
        ledger.createAccount("t1", "B", "CNY", new BigDecimal("100"));
        ledger.transfer(new TransferRequest("r1", "t1", "A", "B", new BigDecimal("50"), "CNY"));
        System.out.println("[输入] A=300, B=100, 转账 A->B=50（期初分录2条+转账分录1条）");

        ReconciliationReport report = ledger.reconcile("t1");
        System.out.println("[判定] totalDebits=" + report.totalDebits()
                + ", totalCredits=" + report.totalCredits() + ", balanced=" + report.balanced());
        for (ReconciliationReport.AccountLine line : report.accounts()) {
            System.out.println("[判定] 账户 " + line.accountId() + " 推导=" + line.derivedBalance()
                    + " 系统=" + line.systemBalance() + " 差异=" + line.difference());
        }
        System.out.println("[结果] 差异数=" + report.discrepancies().size());

        assertEquals(0, new BigDecimal("450").compareTo(report.totalDebits()),
                "借方汇总=期初300+期初100+转账50");
        assertEquals(0, new BigDecimal("450").compareTo(report.totalCredits()), "贷方汇总独立计算须相等");
        assertTrue(report.balanced());
        assertTrue(report.discrepancies().isEmpty());
        assertEquals(3, report.accounts().size(), "A、B 与系统期初权益账户逐户核对");
        assertTrue(report.accounts().stream().allMatch(ReconciliationReport.AccountLine::matched));
    }

    @Test
    void plantedDiscrepancyIsLocated() throws Exception {
        LedgerService ledger = new LedgerService(new EventStore(), new EventUpgrader());
        ledger.createAccount("t1", "A", "CNY", new BigDecimal("300"));
        ledger.createAccount("t1", "B", "CNY", new BigDecimal("0"));
        ledger.createAccount("t2", "A", "CNY", new BigDecimal("999"));
        ledger.transfer(new TransferRequest("r1", "t1", "A", "B", new BigDecimal("50"), "CNY"));
        System.out.println("[输入] t1: A=300,B=0 转账50; t2: A=999。人为篡改 t1|A 系统余额 -7（流水不变）");

        tamperBalance(ledger, "t1", "A", new BigDecimal("-7"));

        ReconciliationReport report = ledger.reconcile("t1");
        System.out.println("[判定] balanced=" + report.balanced() + ", 差异数="
                + report.discrepancies().size());
        assertEquals(1, report.discrepancies().size(), "必须且仅定位到一处差异");

        ReconciliationReport.Discrepancy d = report.discrepancies().get(0);
        System.out.println("[结果] 差异: 租户=" + d.tenantId() + ", 账户=" + d.accountId()
                + ", 推导=" + d.derivedBalance() + ", 系统=" + d.systemBalance()
                + ", 差额=" + d.difference() + ", 涉及分录=" + d.involvedEntries().size() + " 笔");
        assertEquals("t1", d.tenantId());
        assertEquals("A", d.accountId());
        assertEquals(0, new BigDecimal("250").compareTo(d.derivedBalance()), "流水推导 A=300-50");
        assertEquals(0, new BigDecimal("243").compareTo(d.systemBalance()), "系统余额被篡改为 243");
        assertEquals(0, new BigDecimal("-7").compareTo(d.difference()), "差额=系统-推导=-7");
        assertEquals(2, d.involvedEntries().size(), "A 的期初分录+转账分录即差异涉及的分录");
        assertTrue(d.involvedEntries().stream().allMatch(e ->
                e.debitAccountId().equals("A") || e.creditAccountId().equals("A")));

        ReconciliationReport t2 = ledger.reconcile("t2");
        System.out.println("[结果] t2 报告 balanced=" + t2.balanced() + "（t1 的差异不得外溢）");
        assertTrue(t2.balanced(), "他租户报告不受 t1 差异影响");
        assertTrue(t2.accounts().stream().noneMatch(a -> a.accountId().equals("B")),
                "报告按租户隔离，不含他租户账户");
    }

    @Test
    void concurrentWritesDoNotCauseFalsePositives() throws Exception {
        LedgerService ledger = new LedgerService(new EventStore(), new EventUpgrader());
        ledger.createAccount("t1", "A", "CNY", new BigDecimal("100000"));
        ledger.createAccount("t1", "B", "CNY", new BigDecimal("100000"));
        ledger.createAccount("t1", "C", "CNY", new BigDecimal("100000"));
        System.out.println("[输入] A=B=C=100000, 48 线程并发转账，主线程并发对账 300 次");

        int threads = 48;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        String[] ids = {"A", "B", "C"};
        for (int i = 0; i < threads; i++) {
            final int n = i;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int k = 0; k < 50; k++) {
                        String from = ids[(n + k) % 3];
                        String to = ids[(n + k + 1) % 3];
                        ledger.transfer(new TransferRequest("req-" + n + "-" + k, "t1", from, to,
                                new BigDecimal("1"), "CNY"));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ConcurrentLinkedQueue<ReconciliationReport> unbalanced = new ConcurrentLinkedQueue<>();
        start.countDown();
        for (int i = 0; i < 300; i++) {
            ReconciliationReport report = ledger.reconcile("t1");
            if (!report.balanced()) {
                unbalanced.add(report);
            }
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        System.out.println("[判定] 并发期间对账 300 次，误报次数=" + unbalanced.size());
        unbalanced.forEach(r -> System.out.println("[误报] " + r.discrepancies()));
        ReconciliationReport finalReport = ledger.reconcile("t1");
        System.out.println("[结果] 终态 balanced=" + finalReport.balanced()
                + ", 流水条数=" + ledger.auditTrail("t1").size());

        assertTrue(unbalanced.isEmpty(), "并发写入下对账不得读到中间状态而误报不平");
        assertTrue(finalReport.balanced(), "写入全部完成后对账必须平衡");
    }
}
