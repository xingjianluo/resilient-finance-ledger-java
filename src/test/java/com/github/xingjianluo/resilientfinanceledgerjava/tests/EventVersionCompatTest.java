package com.github.xingjianluo.resilientfinanceledgerjava.tests;

import com.github.xingjianluo.resilientfinanceledgerjava.domain.RejectionReason;
import com.github.xingjianluo.resilientfinanceledgerjava.event.EventUpgradeException;
import com.github.xingjianluo.resilientfinanceledgerjava.event.EventUpgrader;
import com.github.xingjianluo.resilientfinanceledgerjava.event.TransactionEvent;
import com.github.xingjianluo.resilientfinanceledgerjava.service.EventStore;
import com.github.xingjianluo.resilientfinanceledgerjava.service.LedgerService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件版本兼容：旧版本（V1）可升级回放；未来/损坏版本必须明确拒绝而非产生错误账务。
 */
class EventVersionCompatTest {

    private TransactionEvent event(int version, String txId, String requestId, String amount) {
        return new TransactionEvent(version, txId, requestId, "t1", "A", "B",
                amount == null ? null : new BigDecimal(amount), "CNY");
    }

    @Test
    void v1EventsCanBeUpgradedAndReplayed() {
        System.out.println("[输入] 两条 V1 事件（无 requestId 字段）: legacy-1=100, legacy-2=30");
        LedgerService ledger = new LedgerService(new EventStore(), new EventUpgrader());
        ledger.createAccount("t1", "A", "CNY", new BigDecimal("1000"));
        ledger.createAccount("t1", "B", "CNY", new BigDecimal("0"));

        List<TransactionEvent> v1Events = List.of(event(1, "legacy-1", null, "100"),
                event(1, "legacy-2", null, "30"));
        int applied = ledger.replay(v1Events);
        System.out.println("[判定] 升级后新应用事件数=" + applied);
        System.out.println("[结果] A=" + ledger.balanceOf("t1", "A") + ", B=" + ledger.balanceOf("t1", "B")
                + ", 流水条数=" + ledger.auditTrail("t1").size());

        assertEquals(2, applied);
        assertEquals(0, new BigDecimal("870").compareTo(ledger.balanceOf("t1", "A")));
        assertEquals(0, new BigDecimal("130").compareTo(ledger.balanceOf("t1", "B")));
        assertTrue(ledger.isBalanced("t1"));
    }

    @Test
    void unknownFutureVersionIsRejectedWithStableReason() {
        System.out.println("[输入] 一条 V4（未来版本）事件");
        LedgerService ledger = new LedgerService(new EventStore(), new EventUpgrader());
        ledger.createAccount("t1", "A", "CNY", new BigDecimal("1000"));
        ledger.createAccount("t1", "B", "CNY", new BigDecimal("0"));

        EventUpgradeException ex = assertThrows(EventUpgradeException.class,
                () -> ledger.replay(List.of(event(4, "future-1", "req-f", "10"))));
        System.out.println("[判定] 拒绝原因=" + ex.getReason() + ", 消息=" + ex.getMessage());
        System.out.println("[结果] A=" + ledger.balanceOf("t1", "A") + ", 流水条数="
                + ledger.auditTrail("t1").size());

        assertEquals(RejectionReason.UNSUPPORTED_EVENT_VERSION, ex.getReason(),
                "无法兼容的版本必须返回稳定原因码");
        assertEquals(0, new BigDecimal("1000").compareTo(ledger.balanceOf("t1", "A")),
                "拒绝事件不得产生任何账务变动");
        assertEquals(1, ledger.auditTrail("t1").size(), "仅开户期初分录，拒绝不得新增流水");
    }

    @Test
    void corruptEventFieldsAreRejected() {
        System.out.println("[输入] V2 事件：金额非正数 / 缺少租户");
        EventUpgrader upgrader = new EventUpgrader();
        EventUpgradeException e1 = assertThrows(EventUpgradeException.class,
                () -> upgrader.upgradeToCurrent(event(2, "bad-1", "r", "0")));
        EventUpgradeException e2 = assertThrows(EventUpgradeException.class,
                () -> upgrader.upgradeToCurrent(new TransactionEvent(2, "bad-2", "r", "",
                        "A", "B", new BigDecimal("10"), "CNY")));
        System.out.println("[判定] e1=" + e1.getReason() + ", e2=" + e2.getReason());
        assertEquals(RejectionReason.EVENT_VALIDATION_FAILED, e1.getReason());
        assertEquals(RejectionReason.EVENT_VALIDATION_FAILED, e2.getReason());
        System.out.println("[结果] 损坏事件被明确拒绝，无错误账务");
    }
}
