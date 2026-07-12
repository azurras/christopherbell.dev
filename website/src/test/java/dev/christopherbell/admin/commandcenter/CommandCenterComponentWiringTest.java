package dev.christopherbell.admin.commandcenter;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.admin.commandcenter.action.CommandCenterActionService;
import dev.christopherbell.admin.commandcenter.metrics.ApplicationHostMetricsProvider;
import dev.christopherbell.admin.commandcenter.metrics.CommandCenterMetricsService;
import dev.christopherbell.admin.commandcenter.metrics.LibreHardwareCpuTemperatureClient;
import dev.christopherbell.admin.commandcenter.metrics.LibreHardwareCpuTemperatureProvider;
import dev.christopherbell.admin.commandcenter.metrics.NvidiaMetricsProvider;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CommandCenterComponentWiringTest {
  @Test
  void componentsWithTestConstructorsDesignateTheirProductionInjectionConstructor() {
    var components = List.of(
        ApplicationHostMetricsProvider.class,
        CommandCenterActionService.class,
        CommandCenterMetricsService.class,
        LibreHardwareCpuTemperatureClient.class,
        LibreHardwareCpuTemperatureProvider.class,
        NvidiaMetricsProvider.class);

    for (var component : components) {
      assertThat(component.getDeclaredConstructors())
          .as(component.getSimpleName())
          .anyMatch(constructor -> constructor.isAnnotationPresent(Autowired.class));
    }
  }
}
