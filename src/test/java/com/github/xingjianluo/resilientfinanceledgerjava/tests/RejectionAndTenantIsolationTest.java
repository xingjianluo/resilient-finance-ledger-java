package com.github.xingjianluo.resilientfinanceledgerjava.tests;

import com.github.xingjianluo.resilientfinanceledgerjava.domain.RejectionReason;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferRequest;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferResult;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferStatus;
import com.github.xingjianluo.resilientfinanceledgerjava.event.EventUpgrader;
import com.github.xingjianluo.resilientfinanceledgerjava.service.EventStore;
import com.github.xingjianluo.resilientfinanceledgerjava.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 各类非法请求必须返回可区分且稳定的拒绝原因；查询与审计严格按租户隔离。
 */
class RejectionAndTenantIsolationTest {

    private LedgerService ledger;

    @BeforeEach
    void setUp() {
        ledger = new LedgerService(new EventStore(), new EventUpgrader());
        ledger.createAccount("t1", "A", "CNY", new BigDecimal("100"));
        ledger.createAccount("t1", "B", "CNY", new BigDecimal("0"));
        ledger.createAccount("t1", "USD1", "USD", new BigDecimal("100"));
        ledger.createAccount("t2", "A", "CNY", new BigDecimal("999")); // 他租户同名账户
    }

    private TransferResult submit(String requestId, String tenant, String from, String to,
                                  String amount, String currency) {
        TransferRequest req = new TransferRequest(requestId, tenant, from, to,
                amount == null ? null : new BigDecimal(amount), currency);
        TransferResult result = ledger.transfer(req);
        System.out.println("[判定] requestId=" + requestId + " -> status=" + result.status()
                + ", reason=" + result.reason());
        return result;
    }

    @Test
    void rejectionReasonsAreDistinguishable() {
        System.out.println("[输入] 余额不足 / 币种不匹配 / 租户不匹配 / 无效金额 / 账户不存在 各一例");
        assertEquals(RejectionReason.INSUFFICIENT_FUNDS,
                submit("r1", "t1", "A", "B", "1000", "CNY").reason());
        assertEquals(RejectionReason.CURRENCY_MISMATCH,
                submit("r2", "t1", "A", "USD1", "10", "CNY").reason());
        assertEquals(RejectionReason.TENANT_MISMATCH,
                submit("r3", "t2", "A", "B", "10", "CNY").reason()); // B 不属于 t2
        assertEquals(RejectionReason.INVALID_AMOUNT,
                submit("r4", "t1", "A", "B", "0", "CNY").reason());
        assertEquals(RejectionReason.INVALID_AMOUNT,
                submit("r5", "t1", "A", "B", "-5", "CNY").reason());
        assertEquals(RejectionReason.INVALID_AMOUNT,
                submit("r6", "t1", "A", "B", null, "CNY").reason());
        assertEquals(RejectionReason.ACCOUNT_NOT_FOUND,
                submit("r7", "t1", "A", "NOPE", "10", "CNY").reason());

        System.out.println("[结果] 拒绝审计条数=" + ledger.rejectionAudit("t1").size()
                + ", t1 流水条数=" + ledger.auditTrail("t1").size());
        assertEquals(6, ledger.rejectionAudit("t1").size());
        assertEquals(1, ledger.rejectionAudit("t2").size());
        assertEquals(2, ledger.auditTrail("t1").size(), "全部被拒绝，不得新增流水（仅 2 条开户期初分录）");
        assertEquals(0, new BigDecimal("100").compareTo(ledger.balanceOf("t1", "A")), "余额不得变动");
    }

    @Test
    void tenantIsolationForBalanceAndAudit() {
        System.out.println("[输入] t1 与 t2 各有账户 A；t1 内部转账 40");
        TransferResult ok = submit("ok-1", "t1", "A", "B", "40", "CNY");
        assertEquals(TransferStatus.ACCEPTED, ok.status());

        System.out.println("[判定] t1.A=" + ledger.balanceOf("t1", "A")
                + ", t2.A=" + ledger.balanceOf("t2", "A")
                + ", t1 流水=" + ledger.auditTrail("t1").size()
                + ", t2 流水=" + ledger.auditTrail("t2").size());

        assertEquals(0, new BigDecimal("60").compareTo(ledger.balanceOf("t1", "A")));
        assertEquals(0, new BigDecimal("999").compareTo(ledger.balanceOf("t2", "A")), "他租户余额互不影响");
        assertEquals(3, ledger.auditTrail("t1").size(), "t1：2 条期初分录 + 1 条转账分录");
        assertEquals(1, ledger.auditTrail("t2").size(), "t2 看不到 t1 的流水（仅自身期初分录）");
        assertThrows(IllegalArgumentException.class,
                () -> ledger.balanceOf("t2", "B"), "t2 查询 t1 的账户必须不可见");
        System.out.println("[结果] 租户隔离验证通过");
    }
}
