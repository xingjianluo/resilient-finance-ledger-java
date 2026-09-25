package com.github.xingjianluo.resilientfinanceledgerjava.api;

import com.github.xingjianluo.resilientfinanceledgerjava.ledger.EventStore;
import com.github.xingjianluo.resilientfinanceledgerjava.ledger.EventUpcaster;
import com.github.xingjianluo.resilientfinanceledgerjava.ledger.LedgerService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 本地装配：全部内存实现，无外部消息系统、数据库或真实凭据。
 */
@Configuration
public class LedgerConfig {

    @Bean
    public EventStore eventStore() {
        return new EventStore();
    }

    @Bean
    public EventUpcaster eventUpcaster() {
        return new EventUpcaster();
    }

    @Bean
    public LedgerService ledgerService(EventStore eventStore, EventUpcaster upcaster) {
        return new LedgerService(eventStore, upcaster);
    }
}
