package com.github.xingjianluo.resilientfinanceledgerjava.api;

import com.github.xingjianluo.resilientfinanceledgerjava.domain.LedgerEntry;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.RejectionAudit;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferRequest;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferResult;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferStatus;
import com.github.xingjianluo.resilientfinanceledgerjava.event.EventUpgradeException;
import com.github.xingjianluo.resilientfinanceledgerjava.event.TransactionEvent;
import com.github.xingjianluo.resilientfinanceledgerjava.service.LedgerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 复式记账 REST API。所有端点按租户隔离。
 */
@RestController
@RequestMapping("/api")
public class LedgerController {

    private final LedgerService ledgerService;

    public LedgerController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @PostMapping("/accounts")
    public ResponseEntity<?> createAccount(@RequestBody ApiDtos.CreateAccountRequest req) {
        try {
            ledgerService.createAccount(req.tenantId(), req.accountId(), req.currency(), req.initialBalance());
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ApiDtos.ErrorResponse(null, e.getMessage()));
        }
    }

    @PostMapping("/transfers")
    public ResponseEntity<ApiDtos.TransferHttpResponse> transfer(@RequestBody ApiDtos.TransferHttpRequest req) {
        TransferResult result = ledgerService.transfer(new TransferRequest(
                req.requestId(), req.tenantId(), req.fromAccountId(), req.toAccountId(),
                req.amount(), req.currency()));
        ApiDtos.TransferHttpResponse body = new ApiDtos.TransferHttpResponse(
                result.status(), result.reason(), result.transactionId());
        // 拒绝以 422 返回稳定原因码；成功/幂等命中返回 200
        return result.status() == TransferStatus.REJECTED
                ? ResponseEntity.unprocessableEntity().body(body)
                : ResponseEntity.ok(body);
    }

    @GetMapping("/tenants/{tenantId}/accounts/{accountId}/balance")
    public ResponseEntity<?> balance(@PathVariable String tenantId, @PathVariable String accountId) {
        try {
            return ResponseEntity.ok(new ApiDtos.BalanceResponse(
                    tenantId, accountId, ledgerService.balanceOf(tenantId, accountId)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ApiDtos.ErrorResponse(null, e.getMessage()));
        }
    }

    @GetMapping("/tenants/{tenantId}/audit")
    public List<LedgerEntry> audit(@PathVariable String tenantId) {
        return ledgerService.auditTrail(tenantId);
    }

    @GetMapping("/tenants/{tenantId}/rejections")
    public List<RejectionAudit> rejections(@PathVariable String tenantId) {
        return ledgerService.rejectionAudit(tenantId);
    }

    @GetMapping("/tenants/{tenantId}/balanced")
    public boolean balanced(@PathVariable String tenantId) {
        return ledgerService.isBalanced(tenantId);
    }

    /** 事件回放：支持乱序、重复与旧版本事件；不兼容事件明确拒绝（422 + 稳定原因码）。 */
    @PostMapping("/replay")
    public ResponseEntity<?> replay(@RequestBody List<TransactionEvent> events) {
        try {
            int applied = ledgerService.replay(events);
            return ResponseEntity.ok(new ApiDtos.ReplayResponse(applied, events.size()));
        } catch (EventUpgradeException e) {
            return ResponseEntity.unprocessableEntity()
                    .body(new ApiDtos.ErrorResponse(e.getReason(), e.getMessage()));
        }
    }
}
