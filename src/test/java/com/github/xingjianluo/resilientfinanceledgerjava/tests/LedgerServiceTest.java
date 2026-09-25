package com.github.xingjianluo.resilientfinanceledgerjava.tests;

import com.github.xingjianluo.resilientfinanceledgerjava.domain.RejectionReason;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferResult;
import com.github.xingjianluo.resilientfinanceledgerjava.ledger.EventStore;
import com.github.xingjianluo.resilientfinanceledgerjava.ledger.EventUpcaster;
import com.github.xingjianluo.resilientfinanceledgerjava.ledger.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 核心转账：借贷相等、拒绝原因可区分、幂等重复提交、租户隔离、守恒。
 */
class LedgerServiceTest {

    private static final Logger log = LoggerFactory.getLogger(LedgerServiceTest.class);

    private LedgerService ledger;

    @BeforeEach
    void setUp() {
        ledger = new LedgerService(new EventStore(), new EventUpcaster());
        ledger.openAccount("t1", "alice", "CNY", 1000);
        ledger.openAccount("t1", "bob", "CNY", 500);
        ledger.openAccount("t1", "carol", "USD", 300);
        ledger.openAccount("t2", "alice", "CNY", 9000); // 同名账户属于另一租户
        log.info("[setup] t1:alice=1000 CNY, t1:bob=500 CNY, t1:carol=300 USD, t2:alice=9000 CNY");
    }

    @Test
    void successfulTransferMovesBalanceWithEqualDebitAndCredit() {
        log.info("[input] t1 alice->bob 200 CNY");
        TransferResult result = ledger.submitTransfer("t1", "k1", "alice", "bob", 200, "CNY", "rent");
        log.info("[decision] accepted={} txId={}", result.accepted(), result.transactionId());
        assertTrue(result.accepted());
        assertEquals(800, ledger.balance("t1", "alice").orElseThrow());
        assertEquals(700, ledger.balance("t1", "bob").orElseThrow());
        long conservation = ledger.conservationSum("t1");
        log.info("[result] alice=800 bob=700 conservationSum={}", conservation);
        assertEquals(0, conservation, "总借贷必须守恒");
        // 流水：期初 4 条(alice,bob,carol 各2条) + 本次 2 条
        assertEquals(8, ledger.journal("t1").size());
    }

    @Test
    void rejectionsAreDistinguishable() {
        TransferResult invalidAmount = ledger.submitTransfer("t1", "k2", "alice", "bob", 0, "CNY", null);
        TransferResult notFound = ledger.submitTransfer("t1", "k3", "alice", "nobody", 1, "CNY", null);
        TransferResult tenantMismatch = ledger.submitTransfer("t2", "k4", "alice", "bob", 1, "CNY", null);
        TransferResult currencyMismatch = ledger.submitTransfer("t1", "k5", "alice", "carol", 1, "CNY", null);
        TransferResult insufficient = ledger.submitTransfer("t1", "k6", "bob", "alice", 99999, "CNY", null);
        log.info("[result] invalidAmount={} notFound={} tenantMismatch={} currencyMismatch={} insufficient={}",
                invalidAmount.rejectionReason(), notFound.rejectionReason(), tenantMismatch.rejectionReason(),
                currencyMismatch.rejectionReason(), insufficient.rejectionReason());
        assertEquals(RejectionReason.INVALID_AMOUNT, invalidAmount.rejectionReason());
        assertEquals(RejectionReason.ACCOUNT_NOT_FOUND, notFound.rejectionReason());
        assertEquals(RejectionReason.TENANT_MISMATCH, tenantMismatch.rejectionReason());
        assertEquals(RejectionReason.CURRENCY_MISMATCH, currencyMismatch.rejectionReason());
        assertEquals(RejectionReason.INSUFFICIENT_FUNDS, insufficient.rejectionReason());
        // 全部被拒，余额不变
        assertEquals(1000, ledger.balance("t1", "alice").orElseThrow());
        assertEquals(500, ledger.balance("t1", "bob").orElseThrow());
    }

    @Test
    void duplicateSubmissionIsIdempotent() {
        log.info("[input] 同一幂等键 k7 提交两次 alice->bob 100");
        TransferResult first = ledger.submitTransfer("t1", "k7", "alice", "bob", 100, "CNY", "m");
        TransferResult second = ledger.submitTransfer("t1", "k7", "alice", "bob", 100, "CNY", "m");
        log.info("[decision] first={} second={} sameTx={}", first.accepted(), second.accepted(),
                first.transactionId().equals(second.transactionId()));
        assertEquals(first, second);
        assertEquals(900, ledger.balance("t1", "alice").orElseThrow());
        assertEquals(600, ledger.balance("t1", "bob").orElseThrow());
        // 流水只增加 2 条（不重复入账）
        assertEquals(8, ledger.journal("t1").size());
        log.info("[result] alice=900 bob=600 journal=8 (无重复流水)");
    }

    @Test
    void tenantIsolationForBalanceAndJournal() {
        ledger.submitTransfer("t1", "k8", "alice", "bob", 50, "CNY", null);
        ledger.submitTransfer("t2", "k9", "alice", "alice", 1, "CNY", null); // t2 无 bob -> 拒绝
        log.info("[check] t2 查询 t1 的 bob 余额");
        assertTrue(ledger.balance("t2", "bob").isEmpty(), "跨租户不可见");
        assertEquals(9000, ledger.balance("t2", "alice").orElseThrow(), "t2 数据不受 t1 交易影响");
        assertTrue(ledger.journal("t2").stream().noneMatch(j -> j.accountId().equals("bob")),
                "t2 流水不含 t1 账户");
        assertEquals(0, ledger.conservationSum("t2"));
        log.info("[result] 租户隔离成立，t2 守恒");
    }
}
