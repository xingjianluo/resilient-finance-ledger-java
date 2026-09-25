package com.github.xingjianluo.resilientfinanceledgerjava.tests;

import com.github.xingjianluo.resilientfinanceledgerjava.domain.RejectionReason;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferEvent;
import com.github.xingjianluo.resilientfinanceledgerjava.ledger.EventStore;
import com.github.xingjianluo.resilientfinanceledgerjava.ledger.EventUpcaster;
import com.github.xingjianluo.resilientfinanceledgerjava.ledger.LedgerService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 事件回放：乱序到达、重复事件、处理中途重启、旧版本事件兼容与明确拒绝。
 */
class ReplayTest {

    private static final Logger log = LoggerFactory.getLogger(ReplayTest.class);

    private LedgerService newLedger(EventStore store) {
        return new LedgerService(store, new EventUpcaster());
    }

    @Test
    void restartRecoversConfirmedTransactions() {
        EventStore store = new EventStore();
        LedgerService first = newLedger(store);
        first.openAccount("t1", "alice", "CNY", 1000);
        first.openAccount("t1", "bob", "CNY", 0);
        first.submitTransfer("t1", "k1", "alice", "bob", 300, "CNY", "m1");
        first.submitTransfer("t1", "k2", "alice", "bob", 200, "CNY", "m2");
        log.info("[input] 已确认 2 笔转账后模拟重启（事件存储保留，内存状态丢失）");

        // 模拟重启：同一 EventStore，全新 LedgerService（构造时自动回放）
        LedgerService restarted = newLedger(store);
        // 重启后账户元数据由事件重建前需重新开户（开户非事件），余额由回放恢复
        restarted.openAccount("t1", "alice", "CNY", 1000);
        restarted.openAccount("t1", "bob", "CNY", 0);
        var replay = restarted.replayFromStore();
        log.info("[decision] 回放 applied={} skipped={} rejected={}",
                replay.applied(), replay.skippedDuplicates(), replay.rejections().size());

        assertEquals(500, restarted.balance("t1", "alice").orElseThrow());
        assertEquals(500, restarted.balance("t1", "bob").orElseThrow());
        assertEquals(0, restarted.conservationSum("t1"));
        // 已确认交易不丢失：重复提交返回原结果且不再入账
        var dup = restarted.submitTransfer("t1", "k1", "alice", "bob", 300, "CNY", "m1");
        assertTrue(dup.accepted());
        assertEquals(500, restarted.balance("t1", "alice").orElseThrow());
        log.info("[result] 重启后 alice=500 bob=500, 已确认交易不丢失、重复提交不再入账");
    }

    @Test
    void outOfOrderAndDuplicateReplayIsStable() {
        EventStore store = new EventStore();
        LedgerService ledger = newLedger(store);
        ledger.openAccount("t1", "a", "CNY", 1000);
        ledger.openAccount("t1", "b", "CNY", 0);
        ledger.submitTransfer("t1", "k1", "a", "b", 100, "CNY", null);
        ledger.submitTransfer("t1", "k2", "a", "b", 200, "CNY", null);
        ledger.submitTransfer("t1", "k3", "a", "b", 300, "CNY", null);

        List<TransferEvent> events = new ArrayList<>(store.all());
        Collections.shuffle(events, new java.util.Random(42));
        events.addAll(store.all()); // 每个事件再重复一次
        log.info("[input] {} 个事件（3 笔 x 2 份副本）随机乱序后回放", events.size());

        LedgerService fresh = newLedger(new EventStore());
        fresh.openAccount("t1", "a", "CNY", 1000);
        fresh.openAccount("t1", "b", "CNY", 0);
        var replay = fresh.replay(events);
        log.info("[decision] applied={} skippedDuplicates={} rejected={}",
                replay.applied(), replay.skippedDuplicates(), replay.rejections().size());
        assertEquals(3, replay.applied());
        assertEquals(3, replay.skippedDuplicates());
        assertEquals(400, fresh.balance("t1", "a").orElseThrow());
        assertEquals(600, fresh.balance("t1", "b").orElseThrow());
        assertEquals(0, fresh.conservationSum("t1"));
        assertEquals(2 + 6, fresh.journal("t1").size(), "乱序+重复不产生重复流水");
        log.info("[result] a=400 b=600, 流水 8 条，与顺序执行一致");
    }

    @Test
    void legacyV1EventsAreUpcastAndReplayable() {
        EventStore store = new EventStore();
        // 旧系统遗留的 v1 事件：无 tenantId、无 memo
        store.append(new TransferEvent(1, -1, "legacy-tx-1", null, "a", "b", 150, "CNY", null));
        log.info("[input] v1 旧事件: tenantId=null, a->b 150 CNY");

        LedgerService ledger = newLedger(store);
        // v1 事件升级后归入默认租户 legacy
        ledger.openAccount(EventUpcaster.LEGACY_DEFAULT_TENANT, "a", "CNY", 1000);
        ledger.openAccount(EventUpcaster.LEGACY_DEFAULT_TENANT, "b", "CNY", 0);
        var replay = ledger.replayFromStore();
        log.info("[decision] v1->v2 升级 applied={} rejected={}", replay.applied(), replay.rejections());

        assertEquals(1, replay.applied());
        assertEquals(850, ledger.balance(EventUpcaster.LEGACY_DEFAULT_TENANT, "a").orElseThrow());
        assertEquals(150, ledger.balance(EventUpcaster.LEGACY_DEFAULT_TENANT, "b").orElseThrow());
        assertEquals(0, ledger.conservationSum(EventUpcaster.LEGACY_DEFAULT_TENANT));
        log.info("[result] 旧事件在默认租户 {} 下回放成功，守恒成立", EventUpcaster.LEGACY_DEFAULT_TENANT);
    }

    @Test
    void unsupportedEventVersionIsRejectedExplicitly() {
        EventStore store = new EventStore();
        store.append(new TransferEvent(99, -1, "future-tx", "t1", "a", "b", 100, "CNY", null));
        store.append(new TransferEvent(2, -1, "ok-tx", "t1", "a", "b", 50, "CNY", null));
        log.info("[input] 一个 v99 不支持事件 + 一个 v2 正常事件");

        LedgerService ledger = newLedger(new EventStore());
        ledger.openAccount("t1", "a", "CNY", 1000);
        ledger.openAccount("t1", "b", "CNY", 0);
        var replay = ledger.replay(store.all());
        log.info("[decision] applied={} rejections={}", replay.applied(), replay.rejections());

        assertEquals(1, replay.applied(), "正常事件照常入账");
        assertEquals(1, replay.rejections().size());
        assertEquals(RejectionReason.UNSUPPORTED_EVENT_VERSION, replay.rejections().get(0).reason());
        assertEquals("future-tx", replay.rejections().get(0).transactionId());
        // 被拒绝的事件不产生任何账务影响
        assertEquals(950, ledger.balance("t1", "a").orElseThrow());
        assertEquals(50, ledger.balance("t1", "b").orElseThrow());
        assertEquals(0, ledger.conservationSum("t1"));
        log.info("[result] v99 被明确拒绝({})，v2 入账 a=950 b=50，守恒成立",
                RejectionReason.UNSUPPORTED_EVENT_VERSION);
    }
}
