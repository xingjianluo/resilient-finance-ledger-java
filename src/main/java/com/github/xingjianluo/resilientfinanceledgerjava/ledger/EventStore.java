package com.github.xingjianluo.resilientfinanceledgerjava.ledger;

import com.github.xingjianluo.resilientfinanceledgerjava.domain.TransferEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 仅追加的本地事件存储（内存实现，无外部依赖）。
 * 追加时分配全局单调递增序号；读取按序号排序，天然纠正乱序。
 * 追加与已确认交易的判定在同一监视器下完成，重启后由新 LedgerService 回放恢复。
 */
public class EventStore {

    private final List<TransferEvent> events = new ArrayList<>();
    private long nextSequence = 1L;

    /** 追加事件并分配序号。若相同 transactionId 已存在，返回已有事件（幂等追加）。 */
    public synchronized TransferEvent append(TransferEvent event) {
        Optional<TransferEvent> existing = events.stream()
                .filter(e -> e.transactionId().equals(event.transactionId()))
                .findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        TransferEvent stored = event.withSequence(nextSequence++);
        events.add(stored);
        return stored;
    }

    /** 全部事件，按序号升序（乱序到达在此被重排）。 */
    public synchronized List<TransferEvent> all() {
        return events.stream()
                .sorted(Comparator.comparingLong(TransferEvent::sequence))
                .toList();
    }

    public synchronized boolean contains(String transactionId) {
        return events.stream().anyMatch(e -> e.transactionId().equals(transactionId));
    }

    public synchronized int size() {
        return events.size();
    }
}
