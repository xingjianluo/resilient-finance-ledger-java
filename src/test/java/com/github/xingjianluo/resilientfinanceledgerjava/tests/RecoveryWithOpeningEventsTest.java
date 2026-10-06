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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 带开户事件的恢复与事件版本兼容边界：
 * 仅靠事件还原账户与余额；V1/V2 旧事件照常回放；V3 可选业务参考信息透传；
 * 无法识别的新版本或非法内容明确拒绝且账务零变化。
 */
class RecoveryWithOpeningEventsTest {

    @Test
    void recoveryFromEventsOnlyRestoresAccountsAndBalances() {
        EventStore store = new EventStore();
        LedgerService ledger = new LedgerService(store, new EventUpgrader());
        ledger.createAccount("t1", "A", "CNY", new BigDecimal("800"));
        ledger.createAccount("t1", "B", "CNY", new BigDecimal("200"));
        ledger.transfer(new TransferRequest("r1", "t1", "A", "B", new BigDecimal("150"), "CNY"));
        System.out.println("[输入] 开户 A=800/B=200 + 转账 A->B=150，事件总数=" + store.all().size());

        // 重启：不预先建户，仅靠事件恢复
        LedgerService recovered = new LedgerService(new EventStore(), new EventUpgrader());
        int applied = recovered.replay(store.all());
        System.out.println("[判定] 新应用事件=" + applied);
        System.out.println("[结果] 恢复后 A=" + recovered.balanceOf("t1", "A")
                + ", B=" + recovered.balanceOf("t1", "B")
                + ", 流水=" + recovered.auditTrail("t1").size()
                + ", 权益账户=" + recovered.balanceOf("t1", LedgerService.OPENING_EQUITY_ACCOUNT));

        assertEquals(3, applied, "2 条开户事件 + 1 条转账事件全部应用");
        assertEquals(ledger.balanceOf("t1", "A"), recovered.balanceOf("t1", "A"));
        assertEquals(ledger.balanceOf("t1", "B"), recovered.balanceOf("t1", "B"));
        assertEquals(ledger.balanceOf("t1", LedgerService.OPENING_EQUITY_ACCOUNT),
                recovered.balanceOf("t1", LedgerService.OPENING_EQUITY_ACCOUNT),
                "系统期初权益账户也须一致还原");
        assertEquals(ledger.auditTrail("t1").size(), recovered.auditTrail("t1").size());

        ReconciliationReport report = recovered.reconcile("t1");
        System.out.println("[结果] 恢复后对账 balanced=" + report.balanced());
        assertTrue(report.balanced(), "恢复后的账本必须通过对账");
    }

    @Test
    void oldAndNewEventVersionsReplayTogether() {
        LedgerService ledger = new LedgerService(new EventStore(), new EventUpgrader());
        System.out.println("[输入] 开户事件V1 x2 + 交易事件 V1/V2/V3 各一（V3 带 businessRef）");
        List<LedgerEvent> events = List.of(
                new AccountOpenedEvent(1, "open-A", "t1", "A", "CNY", new BigDecimal("1000"), null),
                new AccountOpenedEvent(1, "open-B", "t1", "B", "CNY", BigDecimal.ZERO, "seed"),
                // V1：无 requestId；V2：无 businessRef；V3：当前版本带可选业务参考
                new TransactionEvent(1, "legacy-1", null, "t1", "A", "B",
                        new BigDecimal("100"), "CNY"),
                new TransactionEvent(2, "tx-v2", "req-v2", "t1", "A", "B",
                        new BigDecimal("50"), "CNY"),
                new TransactionEvent(3, "tx-v3", "req-v3", "t1", "A", "B",
                        new BigDecimal("25"), "CNY", "ORDER-2026-001"));
        int applied = ledger.replay(events);
        System.out.println("[判定] 新应用事件=" + applied);
        System.out.println("[结果] A=" + ledger.balanceOf("t1", "A")
                + ", B=" + ledger.balanceOf("t1", "B")
                + ", 对账=" + ledger.reconcile("t1").balanced());

        assertEquals(5, applied);
        assertEquals(0, new BigDecimal("825").compareTo(ledger.balanceOf("t1", "A")));
        assertEquals(0, new BigDecimal("175").compareTo(ledger.balanceOf("t1", "B")));
        assertTrue(ledger.reconcile("t1").balanced());

        // V3 的可选业务参考信息须原样保留在事件存储中
        List<LedgerEvent> stored = storedEvents(ledger);
        TransactionEvent v3 = stored.stream()
                .filter(TransactionEvent.class::isInstance).map(TransactionEvent.class::cast)
                .filter(e -> e.transactionId().equals("tx-v3")).findFirst().orElseThrow();
        System.out.println("[结果] V3 事件 businessRef=" + v3.businessRef());
        assertEquals("ORDER-2026-001", v3.businessRef(), "可选业务参考信息须透传保存");
    }

    @Test
    void unknownEventVersionIsRejectedWithoutAnySideEffects() {
        LedgerService ledger = new LedgerService(new EventStore(), new EventUpgrader());
        System.out.println("[输入] 同批回放：合法开户事件(V1) + 无法识别的开户事件(V99)");
        List<LedgerEvent> batch = List.of(
                new AccountOpenedEvent(1, "open-A", "t1", "A", "CNY",
                        new BigDecimal("500"), null),
                new AccountOpenedEvent(99, "open-X", "t1", "X", "CNY",
                        new BigDecimal("1"), null));

        EventUpgradeException ex = assertThrows(EventUpgradeException.class,
                () -> ledger.replay(batch));
        System.out.println("[判定] 拒绝原因=" + ex.getReason());
        assertEquals(RejectionReason.UNSUPPORTED_EVENT_VERSION, ex.getReason());

        System.out.println("[结果] 账户A存在="
                + accountExists(ledger, "t1", "A") + ", 流水=" + ledger.auditTrail("t1").size());
        assertTrue(accountExists(ledger, "t1", "A") == false,
                "整批拒绝：同批合法事件也不得入账，不能靠猜补齐");
        assertTrue(ledger.auditTrail("t1").isEmpty(), "拒绝后账务必须零变化");
    }

    @Test
    void invalidOpeningEventContentIsRejectedWithoutSideEffects() {
        LedgerService ledger = new LedgerService(new EventStore(), new EventUpgrader());
        System.out.println("[输入] 非法开户事件：期初为负数 / 账户ID为空");
        EventUpgradeException e1 = assertThrows(EventUpgradeException.class,
                () -> ledger.replay(List.of(new AccountOpenedEvent(1, "open-N", "t1", "N",
                        "CNY", new BigDecimal("-5"), null))));
        EventUpgradeException e2 = assertThrows(EventUpgradeException.class,
                () -> ledger.replay(List.of(new AccountOpenedEvent(1, "open-B", "t1", " ",
                        "CNY", new BigDecimal("5"), null))));
        System.out.println("[判定] e1=" + e1.getReason() + ", e2=" + e2.getReason());
        assertEquals(RejectionReason.EVENT_VALIDATION_FAILED, e1.getReason());
        assertEquals(RejectionReason.EVENT_VALIDATION_FAILED, e2.getReason());
        System.out.println("[结果] 流水=" + ledger.auditTrail("t1").size() + "（必须为零）");
        assertTrue(ledger.auditTrail("t1").isEmpty(), "非法事件不得产生任何账务变化");
    }

    @Test
    void repeatedReplayOfOpeningEventsIsIdempotent() {
        EventStore store = new EventStore();
        LedgerService ledger = new LedgerService(store, new EventUpgrader());
        ledger.createAccount("t1", "A", "CNY", new BigDecimal("300"));
        ledger.transfer(new TransferRequest("r1", "t1", "A", LedgerService.OPENING_EQUITY_ACCOUNT,
                new BigDecimal("50"), "CNY"));
        List<LedgerEvent> snapshot = store.all();
        System.out.println("[输入] 同一批 " + snapshot.size() + " 条事件连续回放两次");

        LedgerService recovered = new LedgerService(new EventStore(), new EventUpgrader());
        int first = recovered.replay(snapshot);
        int second = recovered.replay(snapshot);
        System.out.println("[判定] 第一次应用=" + first + ", 第二次应用=" + second);
        System.out.println("[结果] A=" + recovered.balanceOf("t1", "A")
                + ", 流水=" + recovered.auditTrail("t1").size());

        assertEquals(snapshot.size(), first);
        assertEquals(0, second, "重复回放不得重复入账");
        assertEquals(0, new BigDecimal("250").compareTo(recovered.balanceOf("t1", "A")));
        assertTrue(recovered.reconcile("t1").balanced());
    }

    private static boolean accountExists(LedgerService ledger, String tenantId, String accountId) {
        try {
            ledger.balanceOf(tenantId, accountId);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<LedgerEvent> ledgerEventsOf(LedgerService ledger) throws Exception {
        var f = LedgerService.class.getDeclaredField("eventStore");
        f.setAccessible(true);
        return ((EventStore) f.get(ledger)).all();
    }

    // 包装受检异常，保持测试方法签名简洁
    private List<LedgerEvent> storedEvents(LedgerService ledger) {
        try {
            return ledgerEventsOf(ledger);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
