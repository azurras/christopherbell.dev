package dev.christopherbell.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LegacyModuleDependencyRulesTest {
  private static final String FIXTURE_ROOT = "dev.christopherbell.architecture.fixture";
  private static final LegacyModuleDependencyRules RULES =
      new LegacyModuleDependencyRules(
          FIXTURE_ROOT, Set.of("alpha", "beta", "ops"), Set.of("ops"));
  private static final JavaClasses FIXTURES =
      new ClassFileImporter().importPackages(FIXTURE_ROOT);

  @Test
  void rejectsInternalCrossAreaAccessButAllowsPublishedApis() {
    var details = RULES.crossAreaAccessRule().evaluate(FIXTURES).getFailureReport().getDetails();

    assertThat(details)
        .anyMatch(detail -> detail.contains("alpha -> beta")
            && detail.contains("BetaInternalDependency"))
        .noneMatch(detail -> detail.contains("BetaApiContract"));
  }

  @Test
  void rejectsInternalPackagesBelowPublishedApi() {
    var details = RULES.crossAreaAccessRule().evaluate(FIXTURES).getFailureReport().getDetails();

    assertThat(details)
        .anyMatch(detail -> detail.contains("alpha -> beta")
            && detail.contains("Secret"));
  }

  @Test
  void allowsPublishedOrchestrationApiAcrossAreas() {
    var details = RULES.crossAreaAccessRule().evaluate(FIXTURES).getFailureReport().getDetails();

    assertThat(details)
        .noneMatch(detail -> detail.contains("OrchestrationDependency"));
  }

  @Test
  void rejectsBusinessDependenciesOnPublishedOrchestrationApis() {
    var details =
        RULES.orchestrationDirectionRule().evaluate(FIXTURES).getFailureReport().getDetails();

    assertThat(details)
        .anyMatch(detail -> detail.contains("alpha -> ops")
            && detail.contains("OrchestrationDependency"));
  }

  @Test
  void treatsPermissionAsAccountOwnership() {
    var rules = new LegacyModuleDependencyRules(
        "dev.christopherbell", Set.of("account", "permission"), Set.of());

    assertThat(rules.areaOf("dev.christopherbell.permission.jwt"))
        .contains("account");
  }

  @Test
  void reportsUncataloguedTopLevelAreas() {
    var rules = new LegacyModuleDependencyRules(
        FIXTURE_ROOT, Set.of("alpha", "beta"), Set.of());

    assertThat(rules.unknownAreas(FIXTURES)).containsExactly("ops");
  }
}
