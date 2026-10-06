package com.github.xingjianluo.resilientfinanceledgerjava.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * 按租户的对账报告：借方/贷方独立汇总 + 每个账户“账务记录推出余额”与系统余额的逐户核对。
 *
 * <p>读取方式：
 * <ul>
 *   <li>totalsBalanced：该租户全部流水借方合计 == 贷方合计；</li>
 *   <li>accounts 中每行给出 derivedBalance（由流水推出）与 systemBalance（系统当前余额），
 *       matched=false 时表示该账户对不上，difference = systemBalance - derivedBalance；</li>
 *   <li>对不上的账户会附 entrySequences（该账户全部相关分录序号）与
 *       divergentSequences（逐笔重算时与分录记载的过账后余额不一致的分录序号），用于定位差异；</li>
 *   <li>consistent = totalsBalanced 且全部账户 matched。</li>
 * </ul>
 */
public record ReconciliationReport(
        String tenantId,
        int entryCount,
        BigDecimal totalDebits,
        BigDecimal totalCredits,
        boolean totalsBalanced,
        List<AccountReconciliation> accounts,
        boolean consistent) {

    /**
     * 单账户核对行。
     *
     * @param entrySequences     该账户涉及的全部分录序号（差异定位线索）
     * @param divergentSequences 逐笔重算余额与分录记载的过账后余额不一致的分录序号
     */
    public record AccountReconciliation(
            String accountId,
            BigDecimal derivedBalance,
            BigDecimal systemBalance,
            BigDecimal difference,
            boolean matched,
            List<Long> entrySequences,
            List<Long> divergentSequences) {
    }
}
