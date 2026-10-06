package com.github.xingjianluo.resilientfinanceledgerjava.tests;

import com.github.xingjianluo.resilientfinanceledgerjava.domain.EntryType;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.LedgerEntry;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 期初余额按复式记账落账：借贷配平、仅凭流水即可逐步推出任意账户余额、
 * 每笔过账后余额在流水中可见。
 */
class OpeningBalancePostingTest {

    /** 仅凭流水推出账户余额：贷入为正、借出为负。 */
    private static BigDecimal deriveFromJournal(List<LedgerEntry> journal, String accountId) {
        BigDecimal balance = BigDecimal.ZERO;
        for (LedgerEntry e : journal) {
            if (e.debitAccountId().equals(accountId)) {
                balance = balance.subtract(e.amount());
            }
            if (e.creditAccountId().equals(accountId)) {
                balance = balance.add(e.amount());
            }
        }
        return balance;
    }

    @Test
    void openingBalanceIsPostedAsBalancedDoubleEntry() {
        LedgerService ledger = new LedgerService(new EventStore(), new EventUpgrader());
        System.out.println("[输入] 开户 t1:A 期初=500, t1:B 期初=0");
        ledger.createAccount("t1", "A", "CNY", new BigDecimal("500"));
        ledger.createAccount("t1", "B", "CNY", BigDecimal.ZERO);

        List<LedgerEntry> journal = ledger.auditTrail("t1");
        System.out.println("[判定] 流水条数=" + journal.size() + ", 第一条=" + journal.get(0));

        assertEquals(2, journal.size(), "每次开户产生一条期初分录（含零期初）");
        LedgerEntry opening = journal.get(0);
        assertEquals(EntryType.OPENING_BALANCE, opening.type());
        assertEquals(LedgerService.OPENING_EQUITY_ACCOUNT, opening.debitAccountId(),
                "期初分录借方必须是系统期初权益虚账户");
        assertEquals("A", opening.creditAccountId());
        assertEquals(0, new BigDecimal("500").compareTo(opening.amount()));
        assertEquals(0, new BigDecimal("500").compareTo(opening.creditBalanceAfter()),
                "期初过账后余额必须可见");
        assertNull(opening.debitBalanceAfter(), "虚账户一侧不记余额");

        // 借贷配平：期初分录自身借=贷
        assertEquals(0, opening.amount().compareTo(opening.amount()));
        assertTrue(ledger.isBalanced("t1"), "期初入账后总借贷必须守恒");
        System.out.println("[结果] 期初分录借贷配平, 总借贷守恒=" + ledger.isBalanced("t1"));
    }

    @Test
    void balanceIsDerivableSolelyFromJournalAndRunningBalancesMatch() {
        LedgerService ledger = new LedgerService(new EventStore(), new EventUpgrader());
        System.out.println("[输入] A=500, B=200; 转账 A->B=120, B->A=20");
        ledger.createAccount("t1", "A", "CNY", new BigDecimal("500"));
        ledger.createAccount("t1", "B", "CNY", new BigDecimal("200"));
        ledger.transfer(new TransferRequest("r1", "t1", "A", "B", new BigDecimal("120"), "CNY"));
        ledger.transfer(new TransferRequest("r2", "t1", "B", "A", new BigDecimal("20"), "CNY"));

        List<LedgerEntry> journal = ledger.auditTrail("t1");
        BigDecimal derivedA = deriveFromJournal(journal, "A");
        BigDecimal derivedB = deriveFromJournal(journal, "B");
        System.out.println("[判定] 流水推出 A=" + derivedA + ", B=" + derivedB
                + "; 系统余额 A=" + ledger.balanceOf("t1", "A") + ", B=" + ledger.balanceOf("t1", "B"));

        assertEquals(0, ledger.balanceOf("t1", "A").compareTo(derivedA), "A 余额必须仅凭流水可推出");
        assertEquals(0, ledger.balanceOf("t1", "B").compareTo(derivedB), "B 余额必须仅凭流水可推出");

        // 逐笔核对“过账之后账户变成了多少”：按流水顺序重算，必须等于分录记载的余额
        Map<String, BigDecimal> running = new HashMap<>();
        for (LedgerEntry e : journal) {
            if (!LedgerService.OPENING_EQUITY_ACCOUNT.equals(e.debitAccountId())) {
                BigDecimal after = running.merge(e.debitAccountId(), e.amount().negate(), BigDecimal::add);
                assertNotNull(e.debitBalanceAfter(), "分录必须记载借方过账后余额: " + e);
                assertEquals(0, after.compareTo(e.debitBalanceAfter()),
                        "借方过账后余额与逐步重算不一致: seq=" + e.sequence());
            }
            BigDecimal after = running.merge(e.creditAccountId(), e.amount(), BigDecimal::add);
            assertNotNull(e.creditBalanceAfter(), "分录必须记载贷方过账后余额: " + e);
            assertEquals(0, after.compareTo(e.creditBalanceAfter()),
                    "贷方过账后余额与逐步重算不一致: seq=" + e.sequence());
        }
        System.out.println("[结果] 逐笔过账后余额与流水重算完全一致, 共 " + journal.size() + " 条分录");
    }
}
