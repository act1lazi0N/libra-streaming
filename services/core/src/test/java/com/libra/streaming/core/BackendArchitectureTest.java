package com.libra.streaming.core;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class BackendArchitectureTest {
    @Test
    void migratedApplicationAndDomainAreFrameworkFree() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.libra.streaming.core");
        noClasses().that().resideInAnyPackage("..subscriptions.application..", "..events.application..",
                        "..events.domain..", "..transaction.application..", "..history.application..",
                        "..history.domain..", "..entitlement.application..", "..profiles.application..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..",
                        "tools.jackson..", "org.apache.kafka..", "java.sql..", "..infrastructure..", "..api..")
                .check(classes);
    }

    @Test
    void subscriptionApiDoesNotReachInfrastructure() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.libra.streaming.core.subscriptions");
        noClasses().that().resideInAPackage("..subscriptions.api..")
                .should().dependOnClassesThat().resideInAPackage("..subscriptions.infrastructure..")
                .check(classes);
    }

    @Test
    void historyApiDoesNotReachInfrastructure() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.libra.streaming.core.history");
        noClasses().that().resideInAPackage("..history.api..")
                .should().dependOnClassesThat().resideInAPackage("..history.infrastructure..")
                .check(classes);
    }

    @Test
    void entitlementApiDoesNotReachInfrastructure() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.libra.streaming.core.entitlement");
        noClasses().that().resideInAPackage("..entitlement.api..")
                .should().dependOnClassesThat().resideInAPackage("..entitlement.infrastructure..")
                .check(classes);
    }

    @Test
    void profileApiDoesNotReachInfrastructure() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.libra.streaming.core.profiles");
        noClasses().that().resideInAPackage("..profiles.api..")
                .should().dependOnClassesThat().resideInAPackage("..profiles.infrastructure..")
                .check(classes);
    }
}
