package com.libra.streaming.recommendation;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

class BackendArchitectureTest {
    @Test
    void securityConfigurationRemainsInfrastructure() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.libra.streaming.recommendation");
        classes().that().haveSimpleName("SecurityConfiguration")
                .should().resideInAPackage("..infrastructure..")
                .check(classes);
    }
}
