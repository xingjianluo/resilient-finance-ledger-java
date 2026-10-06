package com.github.xingjianluo.resilientfinanceledgerjava.event;

import com.github.xingjianluo.resilientfinanceledgerjava.domain.RejectionReason;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 事件版本升级器：把旧版本事件逐级升级到当前版本；无法兼容时明确拒绝，
 * 绝不以猜测的方式补全语义，避免产生错误账务。
 */
@Component
public class EventUpgrader {

    /**
     * 将任意受支持版本的事件升级到当前版本。
     *
     * <p>兼容边界：
     * <ul>
     *   <li>TransactionEvent V1：无 requestId 字段（以 null 表示），升级时以 transactionId 派生幂等键；</li>
     *   <li>TransactionEvent V2：无 businessRef 字段，升级时置 null（可选字段，不影响语义）；</li>
     *   <li>TransactionEvent V3：当前版本，原样通过；</li>
     *   <li>AccountOpenedEvent V1：当前版本，原样通过；</li>
     *   <li>其他版本（含未来版本）：明确拒绝，原因码 UNSUPPORTED_EVENT_VERSION。</li>
     * </ul>
     *
     * @throws EventUpgradeException 版本不受支持或关键字段校验失败
     */
    public LedgerEvent upgradeToCurrent(LedgerEvent event) {
        if (event == null) {
            throw new EventUpgradeException(RejectionReason.EVENT_VALIDATION_FAILED, "event is null");
        }
        return switch (event) {
            case TransactionEvent tx -> validate(upgradeTransaction(tx));
            case AccountOpenedEvent opened -> validate(opened);
        };
    }

    /** 交易事件逐级升级：V1 -> V2 -> V3。 */
    private TransactionEvent upgradeTransaction(TransactionEvent e) {
        return switch (e.version()) {
            case 1 -> upgradeTransaction(v1toV2(e));
            case 2 -> v2toV3(e);
            case 3 -> e;
            default -> throw new EventUpgradeException(
                    RejectionReason.UNSUPPORTED_EVENT_VERSION,
                    "unsupported transaction event version: " + e.version());
        };
    }

    /** V1 -> V2：V1 没有 requestId，用 transactionId 派生确定性幂等键。 */
    private TransactionEvent v1toV2(TransactionEvent v1) {
        return new TransactionEvent(
                2, v1.transactionId(), "v1-" + v1.transactionId(), v1.tenantId(),
                v1.fromAccountId(), v1.toAccountId(), v1.amount(), v1.currency());
    }

    /** V2 -> V3：businessRef 为新增可选字段，旧事件置 null，不猜测取值。 */
    private TransactionEvent v2toV3(TransactionEvent v2) {
        return new TransactionEvent(
                3, v2.transactionId(), v2.requestId(), v2.tenantId(),
                v2.fromAccountId(), v2.toAccountId(), v2.amount(), v2.currency(), null);
    }

    /** 开户事件版本处理：V1 为当前版本，其余明确拒绝。 */
    private AccountOpenedEvent validate(AccountOpenedEvent e) {
        if (e.version() != AccountOpenedEvent.CURRENT_VERSION) {
            throw new EventUpgradeException(RejectionReason.UNSUPPORTED_EVENT_VERSION,
                    "unsupported account-opened event version: " + e.version());
        }
        if (isBlank(e.eventId()) || isBlank(e.tenantId()) || isBlank(e.accountId()) || isBlank(e.currency())) {
            throw new EventUpgradeException(RejectionReason.EVENT_VALIDATION_FAILED,
                    "account-opened event missing required fields: " + e);
        }
        if (e.openingBalance() == null || e.openingBalance().compareTo(BigDecimal.ZERO) < 0) {
            throw new EventUpgradeException(RejectionReason.EVENT_VALIDATION_FAILED,
                    "opening balance must be >= 0: " + e);
        }
        return e;
    }

    /** 交易事件关键字段校验：任何缺失或非法字段都拒绝，不产生错误账务。 */
    private TransactionEvent validate(TransactionEvent e) {
        if (isBlank(e.transactionId()) || isBlank(e.requestId()) || isBlank(e.tenantId())
                || isBlank(e.fromAccountId()) || isBlank(e.toAccountId()) || isBlank(e.currency())) {
            throw new EventUpgradeException(RejectionReason.EVENT_VALIDATION_FAILED,
                    "event missing required fields: " + e);
        }
        if (e.amount() == null || e.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new EventUpgradeException(RejectionReason.EVENT_VALIDATION_FAILED,
                    "event amount must be positive: " + e);
        }
        return e;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
