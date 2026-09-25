package com.github.xingjianluo.resilientfinanceledgerjava.domain;

/**
 * 转账提交结果：状态 + 稳定原因码 + 交易号（成功或幂等命中时返回）。
 */
public record TransferResult(TransferStatus status, RejectionReason reason, String transactionId) {

    public static TransferResult accepted(String transactionId) {
        return new TransferResult(TransferStatus.ACCEPTED, null, transactionId);
    }

    public static TransferResult duplicate(String transactionId) {
        return new TransferResult(TransferStatus.DUPLICATE, null, transactionId);
    }

    public static TransferResult rejected(RejectionReason reason) {
        return new TransferResult(TransferStatus.REJECTED, reason, null);
    }
}
