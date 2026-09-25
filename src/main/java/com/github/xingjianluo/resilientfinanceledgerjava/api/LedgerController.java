package com.github.xingjianluo.resilientfinanceledgerjava.api;

import com.github.xingjianluo.resilientfinanceledgerjava.domain.Account;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.JournalEntry;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferResult;
import com.github.xingjianluo.resilientfinanceledgerjava.ledger.LedgerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST 入口：账户、转账（幂等）、余额、审计流水、守恒校验与回放。
 * 所有端点按租户隔离，租户只能访问自身数据。
 */
@RestController
@RequestMapping("/api/tenants/{tenantId}")
public class LedgerController {

    private final LedgerService ledgerService;

    public LedgerController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    public record OpenAccountRequest(String accountId, String currency, long initialBalance) {}

    public record TransferRequest(String idempotencyKey, String fromAccountId, String toAccountId,
                                  long amount, String currency, String memo) {}

    @PostMapping("/accounts")
    public Map<String, Object> openAccount(@PathVariable String tenantId,
                                           @RequestBody OpenAccountRequest request) {
        Account account = ledgerService.openAccount(
                tenantId, request.accountId(), request.currency(), request.initialBalance());
        return Map.of("tenantId", account.tenantId(), "accountId", account.accountId(),
                "currency", account.currency(), "balance", account.balance());
    }

    @PostMapping("/transfers")
    public ResponseEntity<TransferResult> transfer(@PathVariable String tenantId,
                                                   @RequestBody TransferRequest request) {
        TransferResult result = ledgerService.submitTransfer(tenantId, request.idempotencyKey(),
                request.fromAccountId(), request.toAccountId(),
                request.amount(), request.currency(), request.memo());
        return result.accepted()
                ? ResponseEntity.ok(result)
                : ResponseEntity.unprocessableEntity().body(result);
    }

    @GetMapping("/accounts/{accountId}/balance")
    public ResponseEntity<Map<String, Object>> balance(@PathVariable String tenantId,
                                                       @PathVariable String accountId) {
        return ledgerService.balance(tenantId, accountId)
                .<ResponseEntity<Map<String, Object>>>map(b -> ResponseEntity.ok(
                        Map.of("tenantId", tenantId, "accountId", accountId, "balance", b)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/journal")
    public List<JournalEntry> journal(@PathVariable String tenantId) {
        return ledgerService.journal(tenantId);
    }

    /** 守恒校验：total 必须为 0（总借贷相等）。 */
    @GetMapping("/conservation")
    public Map<String, Object> conservation(@PathVariable String tenantId) {
        long sum = ledgerService.conservationSum(tenantId);
        return Map.of("tenantId", tenantId, "signedSum", sum, "balanced", sum == 0);
    }

    /** 从事件存储回放（演示重启恢复 / 乱序重排 / 旧版本升级）。 */
    @PostMapping("/replay")
    public LedgerService.ReplayResult replay(@PathVariable String tenantId) {
        return ledgerService.replayFromStore();
    }
}
