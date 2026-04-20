package com.robomart.test.arch;

import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Interface-based ArchUnit test that services implement.
 * Each service creates a test class annotated with @AnalyzeClasses and implements this interface.
 *
 * Example:
 * <pre>
 *   {@literal @}AnalyzeClasses(packages = "com.robomart.product")
 *   class ProductServiceArchTest implements RoboMartLayerArchTest {}
 * </pre>
 *
 * The @ArchTest fields from this interface are inherited and executed automatically.
 */
public interface RoboMartLayerArchTest {

    @ArchTest
    ArchRule controllers_must_not_access_repositories =
        RoboMartArchRules.CONTROLLERS_MUST_NOT_ACCESS_REPOSITORIES;

    @ArchTest
    ArchRule repositories_must_not_access_controllers =
        RoboMartArchRules.REPOSITORIES_MUST_NOT_ACCESS_CONTROLLERS;

    @ArchTest
    ArchRule services_must_not_access_controllers =
        RoboMartArchRules.SERVICES_MUST_NOT_ACCESS_CONTROLLERS;

    @ArchTest
    ArchRule entities_must_be_in_entity_package =
        RoboMartArchRules.ENTITIES_MUST_BE_IN_ENTITY_PACKAGE;
}
