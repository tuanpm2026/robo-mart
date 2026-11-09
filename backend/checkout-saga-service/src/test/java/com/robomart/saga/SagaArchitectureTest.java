package com.robomart.saga;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import org.junit.jupiter.api.Test;

class SagaArchitectureTest {
    @Test
    void orchestratorDoesNotDependOnParticipantPersistenceOrImplementation() {
        var classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("com.robomart.saga");
        noClasses()
                .that()
                .resideInAPackage("com.robomart.saga..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.robomart.order..",
                        "com.robomart.payment..",
                        "com.robomart.inventory..")
                .check(classes);
    }
}
