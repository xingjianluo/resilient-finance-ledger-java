package com.github.xingjianluo.resilientfinanceledgerjava.tests;

import com.github.xingjianluo.resilientfinanceledgerjava.domain.ReconciliationReport;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.RejectionReason;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferRequest;
import com.github.xingjianluo.resilientfinanceledgerjava.event.AccountOpenedEvent;
import com.github.xingjianluo.resilientfinanceledgerjava.event.EventUpgradeException;
import com.github.xingjianluo.resilientfinanceledgerjava.event.EventUpgrader;
import com.github.xingjianluo.resilientfinanceledgerjava.event.LedgerEvent;
import com.github.xingjianluo.resilientfinanceledgerjava.event.TransactionEvent;
import com.github.xingjianluo.resilientfinanceledgerjava.service.EventStore;
import com.github.xingjianluo.resilientfinanceledgerjava.service.LedgerService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件恢复与版本兼容：开户/期初作为事件参与回放，重启后仅凭事件还原账户与余额；
 * V1/V2 旧事件照常回放，V3 可选业务参考信息随行；不兼容事件整批拒绝且账务零变化。
 */
class EventRecoveryTest {

    @Test
    void restartRecoversAccountsAndBalancesFromEventsAlone() {
        EventStore store = new EventStore();
        LedgerService ledger = new LedgerService(store, new EventUpgrader());
        System.out.println("[输入] 开户 A=500, B=200; 转账 A->B=120, B->A=20");
        ledger.createAccount("t1", "A", "CNY", new BigDecimal("500"));
        ledger.createAccount("t1", "B", "CNY", new BigDecimal("200"));
        ledger.transfer(new TransferRequest("r1", "t1", "A", "B", new BigDecimal("120"), "CNY"));
        ledger.transfer(new TransferRequest("r2", "t1", "B", "A", new BigDecimal("20"), "CNY"));

        List<LedgerEvent> events = store.all();
        System.out.println("[判定] 事件存储条数=" + events.size()
                + "（2 条开户 + 2 条转账），原余额 A=" + ledger.balanceOf("t1", "A")
                + ", B=" + ledger.balanceOf("t1", "B"));

        // “重启”：全新账本 + 全新事件存储，乱序回放（含一条重复），不预先开户
        List<LedgerEvent> shuffled = new ArrayList<>(events);
        shuffled.add(events.get(2)); // 重复一条转账事件
        Collections.shuffle(shuffled, new java.util.Random(7));
        LedgerService recovered = new LedgerService(new EventStore(), new EventUpgrader());
        int applied = recovered.replay(shuffled);

        System.out.println("[判定] 乱序+重复回放 " + shuffled.size() + " 条, 新应用=" + applied);
        System.out.println("[结果] 恢复后 A=" + recovered.balanceOf("t1", "A")
                + ", B=" + recovered.balanceOf("t1", "B")
                + ", 流水条数=" + recovered.auditTrail("t1").size()
                + ", 对账一致=" + recovered.reconcile("t1").consistent());

        assertEquals(4, applied, "重复事件被幂等跳过");
        assertEquals(ledger.balanceOf("t1", "A"), recovered.balanceOf("t1", "A"),
                "仅凭事件必须还原出相同余额");
        assertEquals(ledger.balanceOf("t1", "B"), recovered.balanceOf("t1", "B"));
        assertEquals(ledger.auditTrail("t1").size(), recovered.auditTrail("t1").size());
        assertTrue(recovered.reconcile("t1").consistent(), "恢复后的账本必须通过对账");
    }

    @Test
    void mixedVersionBatchWithBusinessReferenceReplays() {
        LedgerService ledger = new LedgerService(new EventStore(), new EventUpgrader());
        System.out.println("[输入] 开户事件 + V1/V2/V3 转账事件混批回放（V3 带业务参考信息）");
        List<LedgerEvent> batch = List.of(
                new AccountOpenedEvent(1, "t1", "A", "CNY", new BigDecimal("1000")),
                new AccountOpenedEvent(1, "t1", "B", "CNY", BigDecimal.ZERO),
                new TransactionEvent(1, "legacy-1", null, "t1", "A", "B",
                        new BigDecimal("100"), "CNY"),
                new TransactionEvent(2, "tx-v2", "req-v2", "t1", "A", "B",
                        new BigDecimal("50"), "CNY"),
                new TransactionEvent(3, "tx-v3", "req-v3", "t1", "B", "A",
                        new BigDecimal("30"), "CNY", "invoice-2026-001"));
        int applied = ledger.replay(batch);

        System.out.println("[判定] 新应用=" + applied + ", A=" + ledger.balanceOf("t1", "A")
                + ", B=" + ledger.balanceOf("t1", "B"));
        assertEquals(5, applied);
        assertEquals(0, new BigDecimal("880").compareTo(ledger.balanceOf("t1", "A")));
        assertEquals(0, new BigDecimal("120").compareTo(ledger.balanceOf("t1", "B")));
        assertTrue(ledger.reconcile("t1").consistent());

        // 回放后同 requestId 仍幂等：整批重复回放不得再次入账
        assertEquals(0, ledger.replay(batch), "整批重复回放不得再次入账");
        System.out.println("[结果] 混批回放成功且整批幂等, 对账一致="
                + ledger.reconcile("t1").consistent());
    }

    @Test
    void v3BusinessReferenceIsPreservedInStoredEvent() {
        EventStore store = new EventStore();
        LedgerService ledger = new LedgerService(store, new EventUpgrader());
        ledger.createAccount("t1", "A", "CNY", new BigDecimal("100"));
        ledger.createAccount("t1", "B", "CNY", BigDecimal.ZERO);
        System.out.println("[输入] 回放一条 V3 事件, businessReference=po-42");
        ledger.replay(List.of(new TransactionEvent(3, "tx-ref", "req-ref", "t1", "A", "B",
                new BigDecimal("10"), "CNY", "po-42")));

        TransactionEvent stored = store.all().stream()
                .filter(e -> e instanceof TransactionEvent)
                .map(e -> (TransactionEvent) e).findFirst().orElseThrow();
        System.out.println("[判定] 存储事件 version=" + stored.version()
                + ", businessReference=" + stored.businessReference());
        assertEquals(3, stored.version());
        assertEquals("po-42", stored.businessReference(), "可选业务参考信息必须原样保留");
        System.out.println("[结果] A=" + ledger.balanceOf("t1", "A")
                + ", B=" + ledger.balanceOf("t1", "B"));
    }

    @Test
    void incompatibleEventRejectsWholeBatchWithZeroLedgerChange() {
        LedgerService ledger = new LedgerService(new EventStore(), new EventUpgrader());
        ledger.createAccount("t1", "A", "CNY", new BigDecimal("500"));
        int journalBefore = ledger.auditTrail("t1").size();
        System.out.println("[输入] 批量回放：合法开户事件 + 合法转账事件 + 一条 V99 未来版本事件");

        List<LedgerEvent> batch = List.of(
                new AccountOpenedEvent(1, "t1", "B", "CNY", new BigDecimal("100")),
                new TransactionEvent(3, "tx-ok", "req-ok", "t1", "A", "B",
                        new BigDecimal("10"), "CNY"),
                new TransactionEvent(99, "tx-future", "req-future", "t1", "A", "B",
                        new BigDecimal("10"), "CNY"));
        EventUpgradeException ex = assertThrows(EventUpgradeException.class,
                () -> ledger.replay(batch));

        System.out.println("[判定] 拒绝原因=" + ex.getReason()
                + ", A=" + ledger.balanceOf("t1", "A")
                + ", 流水条数=" + ledger.auditTrail("t1").size() + "（回放前=" + journalBefore + "）");
        assertEquals(RejectionReason.UNSUPPORTED_EVENT_VERSION, ex.getReason());
        assertEquals(0, new BigDecimal("500").compareTo(ledger.balanceOf("t1", "A")),
                "整批拒绝：余额不得有任何变化");
        assertEquals(journalBefore, ledger.auditTrail("t1").size(),
                "整批拒绝：流水不得有任何变化");
        assertThrows(IllegalArgumentException.class, () -> ledger.balanceOf("t1", "B"),
                "整批拒绝：本批开户事件也不得生效");
        System.out.println("[结果] 不兼容事件整批拒绝, 账务零变化");
    }

    @Test
    void invalidOpeningEventIsRejectedWithoutCreatingAccount() {
        LedgerService ledger = new LedgerService(new EventStore(), new EventUpgrader());
        System.out.println("[输入] 回放一条期初余额为负的开户事件");
        EventUpgradeException ex = assertThrows(EventUpgradeException.class,
                () -> ledger.replay(List.of(
                        new AccountOpenedEvent(1, "t1", "X", "CNY", new BigDecimal("-1")))));
        System.out.println("[判定] 拒绝原因=" + ex.getReason());
        assertEquals(RejectionReason.EVENT_VALIDATION_FAILED, ex.getReason());
        assertThrows(IllegalArgumentException.class, () -> ledger.balanceOf("t1", "X"),
                "非法开户事件不得创建账户");
        assertTrue(ledger.auditTrail("t1").isEmpty(), "非法开户事件不得产生流水");
        System.out.println("[结果] 非法开户事件被明确拒绝, 账户未创建, 流水为空");
    }
}
