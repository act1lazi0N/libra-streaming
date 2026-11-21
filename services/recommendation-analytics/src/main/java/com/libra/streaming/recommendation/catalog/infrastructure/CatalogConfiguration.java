package com.libra.streaming.recommendation.catalog.infrastructure;

import com.libra.streaming.recommendation.catalog.application.CatalogProjection;
import com.libra.streaming.recommendation.catalog.application.CatalogStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class CatalogConfiguration {
    @Bean
    CatalogStore catalogStore(JdbcTemplate jdbc, ObjectMapper mapper) { return new JdbcCatalogStore(jdbc, mapper); }

    @Bean
    CatalogProjection catalogProjection(CatalogStore store) { return new CatalogProjection(store); }
}
