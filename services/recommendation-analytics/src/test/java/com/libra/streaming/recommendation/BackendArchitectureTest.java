package com.libra.streaming.recommendation;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

class BackendArchitectureTest {
    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.libra.streaming.recommendation");

    @Test
    void securityConfigurationRemainsInfrastructure() {
        classes().that().haveSimpleName("SecurityConfiguration")
                .should().resideInAPackage("..infrastructure..")
                .check(PRODUCTION);
    }

    @Test
    void applicationAndDomainAreFrameworkFree() {
        // The rule must bite on real classes: an empty selection would pass without proving anything.
        assertThat(PRODUCTION.stream().filter(type -> type.getPackageName().matches(".*\\.(application|domain)(\\..*)?")))
                .isNotEmpty();
        noClasses().that().resideInAnyPackage("..application..", "..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..",
                        "tools.jackson..", "java.sql..", "javax.sql..", "org.apache.kafka..", "org.flywaydb..",
                        "..infrastructure..", "..api..")
                .check(PRODUCTION);
    }

    @Test
    void domainDoesNotDependOnApplication() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAPackage("..application..")
                .check(PRODUCTION);
    }

    @Test
    void apiDoesNotReachInfrastructure() {
        noClasses().that().resideInAPackage("..api..")
                .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
                .check(PRODUCTION);
    }

    @Test
    void everyFeatureClassSitsInALayer() {
        classes().that().resideInAPackage("com.libra.streaming.recommendation.*..")
                .should().resideInAnyPackage("..api..", "..application..", "..domain..", "..infrastructure..")
                .check(PRODUCTION);
    }

    @Test
    void persistenceIsJdbcOnly() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage("jakarta.persistence..", "org.hibernate..", "org.h2..")
                .check(PRODUCTION);
    }
}
