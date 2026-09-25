package com.github.xingjianluo.resilientfinanceledgerjava.domain;

/**
 * 转账提交结果：成功时携带交易号，失败时携带稳定且可区分的拒绝原因。
 * 幂等重复提交返回与首次完全相同的结果。
 */
public record TransferResult(boolean accepted,
                             String transactionId,
                             RejectionReason rejectionReason,
                             String detail) {

    public static TransferResult ok(String transactionId) {
        return new TransferResult(true, transactionId, null, "ACCEPTED");
    }

    public static TransferResult rejected(RejectionReason reason, String detail) {
        return new TransferResult(false, null, reason, detail);
    }
}
