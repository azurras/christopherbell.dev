package dev.christopherbell.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.Application;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularMonolithArchitectureTest {
  private static final ApplicationModules MODULES = ApplicationModules.of(Application.class);

  @Test
  void explicitBusinessModulesObeyDeclaredBoundaries() {
    assertThat(MODULES.getModuleByName("configuration")).isEmpty();
    MODULES.verify();
  }
}
