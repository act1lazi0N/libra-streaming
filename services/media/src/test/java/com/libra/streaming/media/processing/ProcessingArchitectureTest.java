package com.libra.streaming.media.processing;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ProcessingArchitectureTest {
    @Test
    void processingApplicationAndDomainAreFrameworkFree() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.libra.streaming.media.processing");
        noClasses().that().resideInAnyPackage("..processing.application..", "..processing.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..",
                        "software.amazon..", "tools.jackson..", "java.sql..", "..infrastructure..", "..api..")
                .check(classes);
    }
}
