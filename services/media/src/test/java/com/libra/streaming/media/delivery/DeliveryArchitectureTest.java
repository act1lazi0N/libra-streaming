package com.libra.streaming.media.delivery;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class DeliveryArchitectureTest {
    @Test
    void deliveryApplicationAndDomainAreFrameworkFree() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.libra.streaming.media.delivery");
        noClasses().that().resideInAnyPackage("..delivery.application..", "..delivery.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..",
                        "software.amazon..", "tools.jackson..", "java.sql..", "..infrastructure..", "..api..")
                .check(classes);
        noClasses().that().resideInAPackage("..delivery.api..")
                .should().dependOnClassesThat().resideInAPackage("..delivery.infrastructure..")
                .check(classes);
    }

    @Test
    void deliveryReadsMediaRecordsWithoutReachingIntoTheWriters() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.libra.streaming.media");
        // The READY row and the stored generation are the only coupling: processing writes them, delivery reads them.
        noClasses().that().resideInAPackage("..media.delivery..")
                .should().dependOnClassesThat().resideInAnyPackage("..media.processing..", "..media.upload..",
                        "..media.events..", "..media.storage..")
                .check(classes);
        noClasses().that().resideInAnyPackage("..media.processing..", "..media.upload..", "..media.events..")
                .should().dependOnClassesThat().resideInAPackage("..media.delivery..")
                .check(classes);
    }
}
