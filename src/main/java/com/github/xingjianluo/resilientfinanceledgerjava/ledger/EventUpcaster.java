package com.github.xingjianluo.resilientfinanceledgerjava.ledger;

import com.github.xingjianluo.resilientfinanceledgerjava.domain.RejectionReason;
import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferEvent;

/**
 * 事件版本升级器：将旧版本事件逐级升级为当前版本。
 * 兼容边界：支持 v1 -> v2（补默认租户、空 memo）。
 * v1 事件缺 tenantId 且无法确定归属租户时，明确拒绝而不是猜测入账。
 */
public class EventUpcaster {

    /** v1 事件升级时使用的默认租户（单租户时代全部数据归属）。 */
    public static final String LEGACY_DEFAULT_TENANT = "legacy";

    /**
     * 将任意受支持版本的事件升级为当前版本。
     *
     * @throws EventUpgradeException 版本不受支持或缺少升级所需关键字段时抛出
     */
    public TransferEvent toCurrentVersion(TransferEvent event) {
        TransferEvent current = event;
        if (current.schemaVersion() < 1 || current.schemaVersion() > TransferEvent.CURRENT_VERSION) {
            throw new EventUpgradeException(RejectionReason.UNSUPPORTED_EVENT_VERSION,
                    "unsupported schemaVersion=" + current.schemaVersion()
                            + " for tx=" + current.transactionId());
        }
        if (current.schemaVersion() == 1) {
            current = upgradeV1toV2(current);
        }
        return current;
    }

    private TransferEvent upgradeV1toV2(TransferEvent v1) {
        String tenantId = v1.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            // v1 无租户概念：归入明确的默认租户，保证可解释、可回放
            tenantId = LEGACY_DEFAULT_TENANT;
        }
        return v1.withTenantId(tenantId).withVersion(2);
    }

    /** 事件升级失败：携带稳定拒绝原因。 */
    public static class EventUpgradeException extends RuntimeException {
        private final RejectionReason reason;

        public EventUpgradeException(RejectionReason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public RejectionReason reason() {
            return reason;
        }
    }
}
