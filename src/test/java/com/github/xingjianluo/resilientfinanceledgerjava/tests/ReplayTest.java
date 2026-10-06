package com.github.xingjianluo.resilientfinanceledgerjava.tests;

import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferRequest;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferResult;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferStatus;
import com.github.xingjianluo.resilientfinanceledgerjava.event.EventUpgrader;
import com.github.xingjianluo.resilientfinanceledgerjava.event.LedgerEvent;
import com.github.xingjianluo.resilientfinanceledgerjava.service.EventStore;
import com.github.xingjianluo.resilientfinanceledgerjava.service.LedgerService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件回放：乱序、重复、中途重启均不得改变最终余额、重复生成流水或丢失已确认交易。
 * 开户与期初也是事件：重启后仅靠事件即可还原账户与余额，无需预先建户。
 */
class ReplayTest {

    @Test
    void outOfOrderAndDuplicateReplayYieldsSameBalance() {
        EventStore store = new EventStore();
        LedgerService ledger = new LedgerService(store, new EventUpgrader());
        ledger.createAccount("t1", "A", "CNY", new BigDecimal("300"));
        ledger.createAccount("t1", "B", "CNY", new BigDecimal("0"));
        ledger.createAccount("t1", "C", "CNY", new BigDecimal("0"));
        System.out.println("[输入] 开户 A=300/B=0/C=0（含开户事件），依次提交 A->B=100, B->C=60, A->C=40");

        ledger.transfer(new TransferRequest("r1", "t1", "A", "B", new BigDecimal("100"), "CNY"));
        ledger.transfer(new TransferRequest("r2", "t1", "B", "C", new BigDecimal("60"), "CNY"));
        ledger.transfer(new TransferRequest("r3", "t1", "A", "C", new BigDecimal("40"), "CNY"));
        System.out.println("[判定] 原始余额 A=" + ledger.balanceOf("t1", "A")
                + ", B=" + ledger.balanceOf("t1", "B") + ", C=" + ledger.balanceOf("t1", "C")
                + ", 事件总数=" + store.all().size());

        // 模拟重启：全新账本 + 全新事件存储，回放乱序且含重复的事件（不预先建户）
        List<LedgerEvent> shuffled = new ArrayList<>(store.all());
        shuffled.add(store.all().get(0)); // 重复事件
        Collections.shuffle(shuffled, new java.util.Random(42));
        System.out.println("[输入] 重启后回放 " + shuffled.size() + " 条事件（乱序+1条重复，含开户事件）");

        LedgerService recovered = new LedgerService(new EventStore(), new EventUpgrader());
        int applied = recovered.replay(shuffled);

        System.out.println("[判定] 新应用事件数=" + applied + "（重复事件被跳过）");
        System.out.println("[结果] 回放后 A=" + recovered.balanceOf("t1", "A")
                + ", B=" + recovered.balanceOf("t1", "B") + ", C=" + recovered.balanceOf("t1", "C")
                + ", 流水条数=" + recovered.auditTrail("t1").size());

        assertEquals(6, applied, "3 条开户 + 3 条转账全部应用，重复事件被跳过");
        assertEquals(ledger.balanceOf("t1", "A"), recovered.balanceOf("t1", "A"));
        assertEquals(ledger.balanceOf("t1", "B"), recovered.balanceOf("t1", "B"));
        assertEquals(ledger.balanceOf("t1", "C"), recovered.balanceOf("t1", "C"));
        assertEquals(4, recovered.auditTrail("t1").size(),
                "1 条期初分录 + 3 条转账分录：已确认交易不得丢失、不得重复");
        assertTrue(recovered.isBalanced("t1"));

        // 回放后重复提交同一 requestId 必须被识别为幂等命中
        TransferResult dup = recovered.transfer(
                new TransferRequest("r1", "t1", "A", "B", new BigDecimal("100"), "CNY"));
        System.out.println("[判定] 回放后重复提交 r1 -> " + dup.status());
        assertEquals(TransferStatus.DUPLICATE, dup.status());
        assertEquals(4, recovered.auditTrail("t1").size(), "回放后的重复提交不得再次入账");
    }

    @Test
    void crashMidProcessingDoesNotLoseConfirmedTransactions() {
        EventStore store = new EventStore();
        LedgerService ledger = new LedgerService(store, new EventUpgrader());
        ledger.createAccount("t1", "A", "CNY", new BigDecimal("1000"));
        ledger.createAccount("t1", "B", "CNY", new BigDecimal("0"));
        System.out.println("[输入] 开户 A=1000/B=0，连续提交 10 笔 A->B=50，模拟中途重启");

        for (int i = 0; i < 10; i++) {
            ledger.transfer(new TransferRequest("tx-" + i, "t1", "A", "B",
                    new BigDecimal("50"), "CNY"));
        }
        // “重启”：丢弃内存账本，仅保留事件存储快照
        List<LedgerEvent> snapshot = store.all();
        System.out.println("[判定] 事件存储快照条数=" + snapshot.size() + "（含 2 条开户事件）");

        LedgerService recovered = new LedgerService(new EventStore(), new EventUpgrader());
        int applied = recovered.replay(snapshot);
        System.out.println("[结果] 回放应用=" + applied + ", A=" + recovered.balanceOf("t1", "A")
                + ", B=" + recovered.balanceOf("t1", "B"));

        assertEquals(12, applied, "2 条开户 + 10 条转账：已确认事件不得丢失");
        assertEquals(0, new BigDecimal("500").compareTo(recovered.balanceOf("t1", "A")));
        assertEquals(0, new BigDecimal("500").compareTo(recovered.balanceOf("t1", "B")));
        assertEquals(11, recovered.auditTrail("t1").size(), "1 条期初分录 + 10 条转账分录");
        assertTrue(recovered.isBalanced("t1"));
    }
}
