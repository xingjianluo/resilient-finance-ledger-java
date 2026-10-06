package com.github.xingjianluo.resilientfinanceledgerjava.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * 按租户的对账报告（一致快照）。
 *
 * <p>借方与贷方各自独立汇总；每个账户给出“由账务记录推出的余额”与“系统余额”的逐户核对；
 * 对不上时以 {@link Discrepancy} 指出租户、账户、差额以及涉及的具体分录。
 *
 * @param balanced 总借贷相等且无任何账户差异时为 true
 */
public record ReconciliationReport(
        String tenantId,
        BigDecimal totalDebits,
        BigDecimal totalCredits,
        boolean balanced,
        List<AccountLine> accounts,
        List<Discrepancy> discrepancies) {

    /** 单账户核对行：derivedBalance 由流水推出，systemBalance 为系统当前余额。 */
    public record AccountLine(
            String accountId,
            BigDecimal derivedBalance,
            BigDecimal systemBalance,
            BigDecimal difference,
            boolean matched) {
    }

    /** 一处对账差异：定位到租户、账户、差额与涉及的分录。 */
    public record Discrepancy(
            String tenantId,
            String accountId,
            BigDecimal derivedBalance,
            BigDecimal systemBalance,
            BigDecimal difference,
            List<LedgerEntry> involvedEntries) {
    }
}
