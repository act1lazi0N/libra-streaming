package com.libra.streaming.recommendation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:recommendation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver", "spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=false"})
class RecommendationAnalyticsApplicationTest {
    @Test
    void applicationContextLoads() {
    }
}

