package com.github.xingjianluo.resilientfinanceledgerjava.event;

import com.github.xingjianluo.resilientfinanceledgerjava.domain.RejectionReason;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 事件版本升级器：把旧版本事件升级到当前版本；无法兼容时明确拒绝，
 * 绝不以猜测的方式补全语义，避免产生错误账务。
 */
@Component
public class EventUpgrader {

    /**
     * 将任意受支持版本的事件升级到 {@link TransactionEvent#CURRENT_VERSION}。
     *
     * <p>兼容边界：
     * <ul>
     *   <li>V1：无 requestId 字段（以 null 表示），升级时以 transactionId 派生幂等键；</li>
     *   <li>V2：当前版本，原样通过；</li>
     *   <li>其他版本（含未来版本）：明确拒绝，原因码 UNSUPPORTED_EVENT_VERSION。</li>
     * </ul>
     *
     * @throws EventUpgradeException 版本不受支持或关键字段校验失败
     */
    public TransactionEvent upgradeToCurrent(TransactionEvent event) {
        if (event == null) {
            throw new EventUpgradeException(RejectionReason.EVENT_VALIDATION_FAILED, "event is null");
        }
        return switch (event.version()) {
            case 1 -> validate(upgradeV1toV2(event));
            case 2 -> validate(event);
            default -> throw new EventUpgradeException(
                    RejectionReason.UNSUPPORTED_EVENT_VERSION,
                    "unsupported event version: " + event.version());
        };
    }

    /** V1 -> V2：V1 没有 requestId，用 transactionId 派生确定性幂等键。 */
    private TransactionEvent upgradeV1toV2(TransactionEvent v1) {
        return new TransactionEvent(
                2,
                v1.transactionId(),
                "v1-" + v1.transactionId(),
                v1.tenantId(),
                v1.fromAccountId(),
                v1.toAccountId(),
                v1.amount(),
                v1.currency());
    }

    /** 关键字段校验：任何缺失或非法字段都拒绝，不产生错误账务。 */
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
