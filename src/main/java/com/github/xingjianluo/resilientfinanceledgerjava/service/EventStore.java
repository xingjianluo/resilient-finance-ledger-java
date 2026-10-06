package com.github.xingjianluo.resilientfinanceledgerjava.service;

import com.github.xingjianluo.resilientfinanceledgerjava.event.LedgerEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 只追加的事件存储（内存实现，无外部消息系统/数据库）。
 * 追加即确认：事件落库成功才允许变动账户，保证重启回放可还原。
 * 存储统一为 {@link LedgerEvent}（交易事件 / 开户事件）。
 */
@Component
public class EventStore {

    private final List<LedgerEvent> events = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong(0);

    /** 追加事件，返回全局序号（从 1 开始，单调递增）。 */
    public long append(LedgerEvent event) {
        events.add(event);
        return sequence.incrementAndGet();
    }

    /** 读取全部事件（按追加顺序）。 */
    public List<LedgerEvent> all() {
        return new ArrayList<>(events);
    }
}
