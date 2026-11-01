package br.com.espacotucuma.desktop;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureRulesTest {

    private final com.tngtech.archunit.core.domain.JavaClasses desktopClasses =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("br.com.espacotucuma.desktop");

    @Test
    void userInterfaceDoesNotDependOnPersistenceOrRemoteIntegration() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..ui..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..persistence..", "..integration..", "java.sql..", "javax.sql..");

        rule.check(desktopClasses);
    }

    @Test
    void domainDoesNotDependOnOuterLayersOrRemoteFrameworks() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..ui..", "..application..", "..persistence..", "..integration..",
                        "java.sql..", "javax.sql..", "java.net.http..", "org.springframework..", "javafx..");

        rule.check(desktopClasses);
    }

    @Test
    void applicationDoesNotDependOnPersistenceOrRemoteImplementations() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..persistence..", "..integration..", "java.sql..", "javax.sql..",
                        "java.net.http..", "org.springframework..");

        rule.check(desktopClasses);
    }
}
