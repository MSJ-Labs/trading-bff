package com.msj.trading.bff;

import com.msj.trading.bff.shared.domain.ValueObject;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Boundary rules of the hexagonal layout used by the structured contexts (see CLAUDE.md), same spirit as
 * trading-core. Dependencies only point inwards: infrastructure -> application -> domain. The application layer is
 * framework-free (beans are declared in the infrastructure config).
 */
@AnalyzeClasses(packages = "com.msj.trading.bff", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainIsPureJava = classes()
            .that().resideInAPackage("..domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "..domain..", "lombok..");

    @ArchTest
    static final ArchRule applicationDependsOnDomainOnly = classes()
            .that().resideInAPackage("..application..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "java..", "..domain..", "..application..", "lombok..", "org.slf4j..");

    @ArchTest
    static final ArchRule nothingInsideDependsOnTheOutside = noClasses()
            .that().resideInAnyPackage("..domain..", "..application..")
            .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..", "..api..", "..config..");

    // A valueobject package holds true value objects only.
    @ArchTest
    static final ArchRule valueObjectPackagesHoldValueObjectsOnly = classes()
            .that().resideInAPackage("..valueobject..")
            .should().implement(ValueObject.class);
}
