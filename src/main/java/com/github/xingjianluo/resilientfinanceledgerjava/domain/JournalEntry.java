package com.github.xingjianluo.resilientfinanceledgerjava.domain;

/**
 * 审计流水条目：记录一次借贷过账，能还原影响结论的关键状态与原因。
 *
 * @param sequence       全局事件序号（与事件日志一致，可回放对账）
 * @param transactionId  交易号（幂等键解析后的唯一交易标识）
 * @param tenantId       所属租户
 * @param accountId      受影响账户
 * @param direction      DEBIT(借/减少) 或 CREDIT(贷/增加)
 * @param amount         金额（最小货币单位）
 * @param currency       币种
 * @param balanceAfter   过账后余额（还原结论的关键状态）
 * @param reason         过账原因说明
 */
public record JournalEntry(long sequence,
                           String transactionId,
                           String tenantId,
                           String accountId,
                           Direction direction,
                           long amount,
                           String currency,
                           long balanceAfter,
                           String reason) {

    public enum Direction { DEBIT, CREDIT }

    /** 有符号金额：借为负、贷为正，用于校验总借贷守恒（总和为 0）。 */
    public long signedAmount() {
        return direction == Direction.DEBIT ? -amount : amount;
    }
}
