package dev.christopherbell.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import dev.christopherbell.Application;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularMonolithArchitectureTest {
  private static final ApplicationModules MODULES = ApplicationModules.of(Application.class);
  private static final LegacyModuleDependencyRules LEGACY_RULES =
      LegacyModuleDependencyRules.production();
  private static final JavaClasses PRODUCTION_CLASSES =
      LEGACY_RULES.importProductionClasses();

  @Test
  void explicitBusinessModulesObeyDeclaredBoundaries() {
    assertThat(MODULES.getModuleByName("configuration")).isEmpty();
    MODULES.verify();
  }

  @Test
  void everyProductionAreaIsCataloged() {
    assertThat(LEGACY_RULES.unknownAreas(PRODUCTION_CLASSES)).isEmpty();
  }

  @Test
  void legacyInternalCrossAreaAccessDoesNotGrow() {
    LEGACY_RULES.frozenCrossAreaAccessRule().check(PRODUCTION_CLASSES);
  }

  @Test
  void businessDependenciesOnOrchestrationAreasDoNotGrow() {
    LEGACY_RULES.frozenOrchestrationDirectionRule().check(PRODUCTION_CLASSES);
  }
}
