package com.libra.streaming.media.lifecycle;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class LifecycleArchitectureTest {
    @Test
    void lifecycleApplicationAndDomainAreFrameworkFree() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.libra.streaming.media.lifecycle");
        noClasses().that().resideInAnyPackage("..lifecycle.application..", "..lifecycle.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..",
                        "software.amazon..", "tools.jackson..", "java.sql..", "..infrastructure..", "..api..",
                        "..media.storage..")
                .check(classes);
    }

    @Test
    void lifecycleNeverReachesTheOutboxRelayOrUploadApi() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.libra.streaming.media");
        noClasses().that().resideInAPackage("..media.lifecycle..")
                .should().dependOnClassesThat().resideInAnyPackage("..media.events..", "..media.upload..",
                        "..media.delivery..")
                .check(classes);
    }
}
