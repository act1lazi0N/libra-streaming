package com.libra.streaming.core.entitlement.infrastructure;

import com.libra.streaming.core.entitlement.application.EntitlementAccess;
import com.libra.streaming.core.entitlement.application.EntitlementUseCase;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EntitlementConfiguration {
    @Bean
    EntitlementUseCase entitlementUseCase(EntitlementAccess access, Clock clock) {
        return new EntitlementUseCase(access, clock);
    }
}
