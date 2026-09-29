package com.libra.streaming.core;

import com.libra.streaming.core.entitlement.infrastructure.EntitlementService;
import com.libra.streaming.core.history.infrastructure.HistoryService;
import com.libra.streaming.core.profiles.infrastructure.ProfileService;
import com.libra.streaming.core.subscriptions.infrastructure.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:core;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver", "spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=false"})
class CoreApplicationTest {
    @Autowired ProfileService profiles;
    @Autowired HistoryService history;
    @Autowired EntitlementService entitlements;
    @Autowired SubscriptionService subscriptions;
    @org.springframework.test.context.DynamicPropertySource
    static void identity(org.springframework.test.context.DynamicPropertyRegistry registry) {
        TestIdentityProperties.register(registry);
    }
    @Test
    void applicationContextLoads() {
        org.assertj.core.api.Assertions.assertThat(profiles).isNotNull();
        org.assertj.core.api.Assertions.assertThat(history).isNotNull();
        org.assertj.core.api.Assertions.assertThat(entitlements).isNotNull();
        org.assertj.core.api.Assertions.assertThat(subscriptions).isNotNull();
    }

    @Test
    void nestedHistoryAndProfileWritesStillRequireACallerTransaction() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> profiles.initialize(java.util.UUID.randomUUID(), "Main"))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> history.opened(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                        java.util.UUID.randomUUID(), 1000))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }
}

