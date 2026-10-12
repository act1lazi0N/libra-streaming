package com.libra.streaming.media.events;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class EventsArchitectureTest {
    @Test
    void eventsApplicationAndDomainAreFrameworkFree() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.libra.streaming.media.events");
        noClasses().that().resideInAnyPackage("..events.application..", "..events.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..",
                        "org.apache.kafka..", "tools.jackson..", "java.sql..", "..infrastructure..", "..api..")
                .check(classes);
    }

    @Test
    void eventsDoNotReachIntoProcessingOrUploads() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.libra.streaming.media");
        // The outbox table is the only coupling: transitions write rows, the relay reads them.
        noClasses().that().resideInAPackage("..media.events..")
                .should().dependOnClassesThat().resideInAnyPackage("..media.processing..", "..media.upload..")
                .check(classes);
        noClasses().that().resideInAnyPackage("..media.processing..", "..media.upload..")
                .should().dependOnClassesThat().resideInAPackage("..media.events..")
                .check(classes);
    }
}
