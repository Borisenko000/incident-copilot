package dev.polina.copilot;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** NFR-01: ядро (domain, application) не зависит от фреймворков и инфраструктуры. */
@AnalyzeClasses(packages = "dev.polina.copilot", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule coreDoesNotDependOnFrameworks = noClasses()
            .that().resideInAnyPackage("..domain..", "..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",    // Spring, в том числе Spring AI (org.springframework.ai..)
                    "java.sql..", "javax.sql..", // JDBC
                    "chat.giga..")               // GigaChat SDK
            .because("domain и application — чистая Java; техника живёт в адаптерах (README §14)");
}
