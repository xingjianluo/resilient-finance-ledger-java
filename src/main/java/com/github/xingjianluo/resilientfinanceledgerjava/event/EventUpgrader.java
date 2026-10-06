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
     * 将任意受支持的账本事件升级到当前版本并校验。
     *
     * @throws EventUpgradeException 事件类型/版本不受支持或关键字段校验失败
     */
    public LedgerEvent upgradeToCurrent(LedgerEvent event) {
        if (event == null) {
            throw new EventUpgradeException(RejectionReason.EVENT_VALIDATION_FAILED, "event is null");
        }
        if (event instanceof TransactionEvent transactionEvent) {
            return upgradeToCurrent(transactionEvent);
        }
        if (event instanceof AccountOpenedEvent openedEvent) {
            return upgradeOpening(openedEvent);
        }
        // 密封接口下理论上不可达；防御未知事件类型
        throw new EventUpgradeException(RejectionReason.UNSUPPORTED_EVENT_VERSION,
                "unknown event type: " + event.getClass().getName());
    }

    /**
     * 将任意受支持版本的交易事件升级到 {@link TransactionEvent#CURRENT_VERSION}。
     *
     * <p>兼容边界：
     * <ul>
     *   <li>V1：无 requestId 字段（以 null 表示），升级时以 transactionId 派生幂等键；</li>
     *   <li>V2：有 requestId、无 businessReference，升级时补 null；</li>
     *   <li>V3：当前版本，原样通过；</li>
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
            case 1 -> validateTransaction(upgradeV2toV3(upgradeV1toV2(event)));
            case 2 -> validateTransaction(upgradeV2toV3(event));
            case 3 -> validateTransaction(event);
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

    /** V2 -> V3：补充可选业务参考信息（缺省 null，绝不猜测内容）。 */
    private TransactionEvent upgradeV2toV3(TransactionEvent v2) {
        return new TransactionEvent(
                3,
                v2.transactionId(),
                v2.requestId(),
                v2.tenantId(),
                v2.fromAccountId(),
                v2.toAccountId(),
                v2.amount(),
                v2.currency(),
                v2.businessReference());
    }

    /**
     * 开户事件升级：当前仅 V1，原样通过并校验；未来版本明确拒绝。
     */
    private AccountOpenedEvent upgradeOpening(AccountOpenedEvent event) {
        if (event.version() != AccountOpenedEvent.CURRENT_VERSION) {
            throw new EventUpgradeException(RejectionReason.UNSUPPORTED_EVENT_VERSION,
                    "unsupported account-opened event version: " + event.version());
        }
        if (isBlank(event.tenantId()) || isBlank(event.accountId()) || isBlank(event.currency())) {
            throw new EventUpgradeException(RejectionReason.EVENT_VALIDATION_FAILED,
                    "account-opened event missing required fields: " + event);
        }
        if (event.initialBalance() == null || event.initialBalance().compareTo(BigDecimal.ZERO) < 0) {
            throw new EventUpgradeException(RejectionReason.EVENT_VALIDATION_FAILED,
                    "account-opened initial balance must be >= 0: " + event);
        }
        return event;
    }

    /** 关键字段校验：任何缺失或非法字段都拒绝，不产生错误账务。 */
    private TransactionEvent validateTransaction(TransactionEvent e) {
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
