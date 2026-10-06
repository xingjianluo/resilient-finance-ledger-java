package com.github.xingjianluo.resilientfinanceledgerjava.tests;

import com.github.xingjianluo.resilientfinanceledgerjava.domain.LedgerEntry;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.ReconciliationReport;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferRequest;
import com.github.xingjianluo.resilientfinanceledgerjava.event.EventUpgrader;
import com.github.xingjianluo.resilientfinanceledgerjava.service.EventStore;
import com.github.xingjianluo.resilientfinanceledgerjava.service.LedgerService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 期初余额按复式记账落账：借贷配平、来源可解释、余额可由流水逐步推出、
 * 流水记录每笔过账后余额。
 */
class OpeningBalancePostingTest {

    @Test
    void openingBalanceIsPostedAsBalancedDoubleEntry() {
        LedgerService ledger = new LedgerService(new EventStore(), new EventUpgrader());
        System.out.println("[输入] 开户 t1:A 期初=500, t1:B 期初=0");
        ledger.createAccount("t1", "A", "CNY", new BigDecimal("500"));
        ledger.createAccount("t1", "B", "CNY", new BigDecimal("0"));

        List<LedgerEntry> journal = ledger.auditTrail("t1");
        System.out.println("[判定] 流水条数=" + journal.size() + ", 首条分录=" + journal.get(0));
        assertEquals(1, journal.size(), "正期初产生且仅产生一条期初分录（零期初不产生分录）");

        LedgerEntry opening = journal.get(0);
        assertEquals(LedgerService.OPENING_EQUITY_ACCOUNT, opening.debitAccountId(),
                "期初分录借方必须是系统期初权益账户（解释资金来源）");
        assertEquals("A", opening.creditAccountId(), "期初分录贷方必须是新账户");
        assertEquals(0, new BigDecimal("500").compareTo(opening.amount()));
        assertEquals(0, new BigDecimal("-500").compareTo(opening.debitBalanceAfter()),
                "权益账户过账后余额=-500（资金来源）");
        assertEquals(0, new BigDecimal("500").compareTo(opening.creditBalanceAfter()),
                "新账户过账后余额=500");
        System.out.println("[结果] 期初分录借贷配平: 借 " + opening.debitAccountId() + "="
                + opening.debitBalanceAfter() + ", 贷 " + opening.creditAccountId() + "="
                + opening.creditBalanceAfter() + ", 借贷守恒=" + ledger.isBalanced("t1"));
        assertTrue(ledger.isBalanced("t1"), "含期初分录的总借贷必须守恒");

        ReconciliationReport report = ledger.reconcile("t1");
        System.out.println("[结果] 对账报告 balanced=" + report.balanced() + ", 账户数="
                + report.accounts().size());
        assertTrue(report.balanced(), "含期初的账本对账必须平衡");
        assertTrue(report.accounts().stream().anyMatch(
                a -> a.accountId().equals(LedgerService.OPENING_EQUITY_ACCOUNT) && a.matched()),
                "系统期初权益账户也须逐户核对一致");
    }

    @Test
    void balanceIsDerivableFromJournalStepByStep() {
        LedgerService ledger = new LedgerService(new EventStore(), new EventUpgrader());
        ledger.createAccount("t1", "A", "CNY", new BigDecimal("300"));
        ledger.createAccount("t1", "B", "CNY", new BigDecimal("100"));
        System.out.println("[输入] A=300, B=100; 转账 A->B=50, A->B=20");
        ledger.transfer(new TransferRequest("r1", "t1", "A", "B", new BigDecimal("50"), "CNY"));
        ledger.transfer(new TransferRequest("r2", "t1", "A", "B", new BigDecimal("20"), "CNY"));

        // 只靠流水逐步推导每个账户余额：账户从 0 开始，贷入加、借出减
        Map<String, BigDecimal> derived = new HashMap<>();
        List<LedgerEntry> journal = ledger.auditTrail("t1");
        for (LedgerEntry e : journal) {
            derived.merge(e.creditAccountId(), e.amount(), BigDecimal::add);
            derived.merge(e.debitAccountId(), e.amount().negate(), BigDecimal::add);
            // 每一步推导结果都必须与分录记录的过账后余额一致
            assertEquals(0, derived.get(e.debitAccountId()).compareTo(e.debitBalanceAfter()),
                    "借方过账后余额必须与逐步推导一致: seq=" + e.sequence());
            assertEquals(0, derived.get(e.creditAccountId()).compareTo(e.creditBalanceAfter()),
                    "贷方过账后余额必须与逐步推导一致: seq=" + e.sequence());
            System.out.println("[判定] seq=" + e.sequence() + " " + e.debitAccountId() + "->"
                    + e.creditAccountId() + " " + e.amount()
                    + " | 过账后 " + e.debitAccountId() + "=" + e.debitBalanceAfter()
                    + ", " + e.creditAccountId() + "=" + e.creditBalanceAfter());
        }
        System.out.println("[结果] 推导 A=" + derived.get("A") + ", B=" + derived.get("B")
                + " | 系统 A=" + ledger.balanceOf("t1", "A") + ", B=" + ledger.balanceOf("t1", "B"));
        assertEquals(0, derived.get("A").compareTo(ledger.balanceOf("t1", "A")),
                "A 余额必须可由流水推出");
        assertEquals(0, derived.get("B").compareTo(ledger.balanceOf("t1", "B")),
                "B 余额必须可由流水推出");
        assertEquals(4, journal.size(), "2 条期初分录 + 2 条转账分录");
    }
}
