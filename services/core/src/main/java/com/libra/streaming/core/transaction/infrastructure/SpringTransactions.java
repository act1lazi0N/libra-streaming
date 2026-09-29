package com.libra.streaming.core.transaction.infrastructure;

import com.libra.streaming.core.transaction.application.TransactionPort;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class SpringTransactions implements TransactionPort {
    private final TransactionTemplate required;

    public SpringTransactions(PlatformTransactionManager manager) {
        this.required = new TransactionTemplate(manager);
    }

    @Override
    public <T> T required(Supplier<T> operation) {
        return required.execute(ignored -> operation.get());
    }
}
