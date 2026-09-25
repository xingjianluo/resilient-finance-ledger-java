package com.github.xingjianluo.resilientfinanceledgerjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REST 端到端：建户、转账、幂等、拒绝原因码、租户隔离查询、回放。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerHttpApiTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @SuppressWarnings("unchecked")
    @Test
    void httpApiEndToEnd() {
        System.out.println("[输入] HTTP 建户 t1:A=100, t1:B=0, t2:A=500");
        rest.postForEntity(url("/api/accounts"), Map.of(
                "tenantId", "t1", "accountId", "A", "currency", "CNY",
                "initialBalance", new BigDecimal("100")), Void.class);
        rest.postForEntity(url("/api/accounts"), Map.of(
                "tenantId", "t1", "accountId", "B", "currency", "CNY",
                "initialBalance", BigDecimal.ZERO), Void.class);
        rest.postForEntity(url("/api/accounts"), Map.of(
                "tenantId", "t2", "accountId", "A", "currency", "CNY",
                "initialBalance", new BigDecimal("500")), Void.class);

        Map<String, Object> transfer = Map.of(
                "requestId", "h1", "tenantId", "t1",
                "fromAccountId", "A", "toAccountId", "B",
                "amount", new BigDecimal("30"), "currency", "CNY");
        ResponseEntity<Map> r1 = rest.postForEntity(url("/api/transfers"), transfer, Map.class);
        ResponseEntity<Map> r2 = rest.postForEntity(url("/api/transfers"), transfer, Map.class);
        ResponseEntity<Map> r3 = rest.postForEntity(url("/api/transfers"),
                Map.of("requestId", "h2", "tenantId", "t1", "fromAccountId", "A",
                        "toAccountId", "B", "amount", new BigDecimal("9999"), "currency", "CNY"), Map.class);

        System.out.println("[判定] 首次=" + r1.getBody().get("status")
                + ", 重复=" + r2.getBody().get("status")
                + ", 透支提交 http=" + r3.getStatusCode() + " reason=" + r3.getBody().get("reason"));
        assertEquals(HttpStatus.OK, r1.getStatusCode());
        assertEquals("ACCEPTED", r1.getBody().get("status"));
        assertEquals("DUPLICATE", r2.getBody().get("status"));
        assertEquals(r1.getBody().get("transactionId"), r2.getBody().get("transactionId"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, r3.getStatusCode());
        assertEquals("INSUFFICIENT_FUNDS", r3.getBody().get("reason"));

        Map<?, ?> balance = rest.getForObject(url("/api/tenants/t1/accounts/A/balance"), Map.class);
        Map<?, ?> t2balance = rest.getForObject(url("/api/tenants/t2/accounts/A/balance"), Map.class);
        List<?> audit = rest.getForObject(url("/api/tenants/t1/audit"), List.class);
        Boolean balanced = rest.getForObject(url("/api/tenants/t1/balanced"), Boolean.class);
        System.out.println("[结果] t1.A=" + balance.get("balance") + ", t2.A=" + t2balance.get("balance")
                + ", t1 流水=" + audit.size() + ", 借贷守恒=" + balanced);

        assertEquals(new BigDecimal("70"), new BigDecimal(balance.get("balance").toString()));
        assertEquals(new BigDecimal("500"), new BigDecimal(t2balance.get("balance").toString()));
        assertEquals(1, audit.size());
        assertTrue(balanced);

        // V1 旧事件回放（requestId 为 null，模拟旧格式）
        Map<String, Object> v1Event = new java.util.HashMap<>();
        v1Event.put("version", 1);
        v1Event.put("transactionId", "legacy-h");
        v1Event.put("requestId", null);
        v1Event.put("tenantId", "t1");
        v1Event.put("fromAccountId", "A");
        v1Event.put("toAccountId", "B");
        v1Event.put("amount", new BigDecimal("10"));
        v1Event.put("currency", "CNY");
        Map<?, ?> replayResp = rest.postForObject(url("/api/replay"),
                new HttpEntity<>(List.of(v1Event)), Map.class);
        System.out.println("[结果] V1 回放响应=" + replayResp);
        assertEquals(1, replayResp.get("applied"));
    }
}
