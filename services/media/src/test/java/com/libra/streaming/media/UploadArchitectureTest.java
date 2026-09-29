package com.libra.streaming.media;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class UploadArchitectureTest {
    @Test
    void uploadApplicationAndDomainAreFrameworkFree() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.libra.streaming.media.upload");
        noClasses().that().resideInAnyPackage("..upload.application..", "..upload.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..",
                        "software.amazon..", "tools.jackson..", "java.sql..", "..infrastructure..", "..api..")
                .check(classes);
    }

    @Test
    void uploadApiDoesNotReachInfrastructure() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.libra.streaming.media.upload");
        noClasses().that().resideInAPackage("..upload.api..")
                .should().dependOnClassesThat().resideInAPackage("..upload.infrastructure..")
                .check(classes);
    }
}
