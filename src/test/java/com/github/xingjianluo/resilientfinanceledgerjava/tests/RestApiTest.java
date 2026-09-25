package com.github.xingjianluo.resilientfinanceledgerjava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REST 端到端：开户 -> 转账（含幂等重复）-> 余额 -> 流水 -> 守恒校验。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RestApiTest {

    private static final Logger log = LoggerFactory.getLogger(RestApiTest.class);

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String tenant, String path) {
        return "http://localhost:" + port + "/api/tenants/" + tenant + path;
    }

    @Test
    void endToEndOverHttp() {
        rest.postForEntity(url("acme", "/accounts"),
                Map.of("accountId", "cash", "currency", "CNY", "initialBalance", 1000), Map.class);
        rest.postForEntity(url("acme", "/accounts"),
                Map.of("accountId", "revenue", "currency", "CNY", "initialBalance", 0), Map.class);
        log.info("[input] acme: cash=1000, revenue=0; 转账 cash->revenue 250 (幂等键 order-1)");

        Map<String, Object> transfer = Map.of("idempotencyKey", "order-1", "fromAccountId", "cash",
                "toAccountId", "revenue", "amount", 250, "currency", "CNY", "memo", "order payment");
        var first = rest.postForEntity(url("acme", "/transfers"), transfer, Map.class);
        var second = rest.postForEntity(url("acme", "/transfers"), transfer, Map.class);
        log.info("[decision] first={} second={} (重复提交返回相同结果)", first.getBody(), second.getBody());
        assertTrue(first.getStatusCode().is2xxSuccessful());
        assertEquals(first.getBody().get("transactionId"), second.getBody().get("transactionId"));

        var balance = rest.getForEntity(url("acme", "/accounts/cash/balance"), Map.class);
        log.info("[result] cash 余额={}", balance.getBody().get("balance"));
        assertEquals(750, ((Number) balance.getBody().get("balance")).intValue());

        var conservation = rest.getForEntity(url("acme", "/conservation"), Map.class);
        log.info("[result] 守恒校验={}", conservation.getBody());
        assertEquals(Boolean.TRUE, conservation.getBody().get("balanced"));

        // 拒绝原因可区分：无效金额
        var rejected = rest.postForEntity(url("acme", "/transfers"),
                Map.of("idempotencyKey", "bad-1", "fromAccountId", "cash", "toAccountId", "revenue",
                        "amount", -5, "currency", "CNY", "memo", ""), Map.class);
        log.info("[result] 无效金额拒绝: status={} reason={}",
                rejected.getStatusCode(), rejected.getBody().get("rejectionReason"));
        assertEquals("INVALID_AMOUNT", rejected.getBody().get("rejectionReason"));

        // 租户隔离：其他租户查不到 acme 的账户
        var foreign = rest.getForEntity(url("other", "/accounts/cash/balance"), String.class);
        log.info("[result] 跨租户查询 status={}", foreign.getStatusCode());
        assertTrue(foreign.getStatusCode().is4xxClientError());
    }
}
