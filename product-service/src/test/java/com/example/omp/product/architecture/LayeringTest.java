package com.example.omp.product.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Architecture tests, enforced by ArchUnit.
 *
 * <p>These rules would normally be checked by hand in code review. ArchUnit enforces them
 * mechanically — a violation fails the build.
 */
class LayeringTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("com.example.omp.product");
    }

    @Test
    void controllersDoNotDependOnRepositories() {
        noClasses()
                .that()
                .resideInAPackage("..web..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..repository..")
                .check(classes);
    }

    @Test
    void servicesDoNotDependOnWeb() {
        noClasses()
                .that()
                .resideInAPackage("..service..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.example.omp.product.web..")
                .check(classes);
    }

    @Test
    void entitiesDoNotDependOnSpring() {
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("org.springframework..")
                .check(classes);
    }
}
