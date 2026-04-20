package com.robomart.test.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Shared ArchUnit rules for all Robo-Mart services.
 * Validates the controller → service → repository layer structure (NFR46).
 *
 * Usage in each service:
 * <pre>
 *   {@literal @}AnalyzeClasses(packages = "com.robomart.product")
 *   class ProductServiceArchTest implements RoboMartLayerArchTest {}
 * </pre>
 */
public final class RoboMartArchRules {

    private RoboMartArchRules() {}

    /**
     * Controllers must not access repositories directly — must go through service layer.
     * Enforces: controller → service → repository (no bypass).
     *
     * Exceptions:
     * - GraphQL DataLoader controllers (containing @BatchMapping methods) may access repositories
     *   for efficient batch loading — this is the standard GraphQL N+1 solution.
     * - Test classes (residing in ..unit.. or ..integration.. packages) are excluded.
     */
    public static final ArchRule CONTROLLERS_MUST_NOT_ACCESS_REPOSITORIES =
        noClasses()
            .that().resideInAPackage("..controller..")
            .and().resideOutsideOfPackages("..unit..", "..integration..", "..test..")
            .and(DescribedPredicate.describe("are not GraphQL batch-mapping controllers",
                clazz -> clazz.getMethods().stream().noneMatch(
                    m -> m.isAnnotatedWith("org.springframework.graphql.data.method.annotation.BatchMapping"))))
            .should().accessClassesThat().resideInAPackage("..repository..")
            .as("Controllers must not access repositories directly — use service layer");

    /**
     * Repositories must not access controllers or services (no reverse dependency).
     */
    public static final ArchRule REPOSITORIES_MUST_NOT_ACCESS_CONTROLLERS =
        noClasses()
            .that().resideInAPackage("..repository..")
            .should().accessClassesThat().resideInAPackage("..controller..")
            .as("Repositories must not access controllers");

    /**
     * Services must not access controllers (no circular or reverse dependency).
     */
    public static final ArchRule SERVICES_MUST_NOT_ACCESS_CONTROLLERS =
        noClasses()
            .that().resideInAPackage("..service..")
            .and().areNotAnnotatedWith("org.springframework.context.annotation.Configuration")
            .should().accessClassesThat().resideInAPackage("..controller..")
            .as("Services must not access controllers");

    /**
     * Entities must reside in the entity package (no domain models in controllers).
     * Uses allowEmptyShould(true) because services like cart-service have no JPA entities (Redis-backed).
     */
    public static final ArchRule ENTITIES_MUST_BE_IN_ENTITY_PACKAGE =
        classes()
            .that().areAnnotatedWith("jakarta.persistence.Entity")
            .should().resideInAPackage("..entity..")
            .as("JPA @Entity classes must reside in ..entity.. package")
            .allowEmptyShould(true);
}
