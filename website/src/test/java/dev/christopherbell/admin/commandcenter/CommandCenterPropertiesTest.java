package dev.christopherbell.admin.commandcenter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class CommandCenterPropertiesTest {

  private static final YamlPropertySourceLoader YAML_LOADER = new YamlPropertySourceLoader();

  @Test
  void createsOneSharedSystemInfoProvider() {
    new ApplicationContextRunner()
        .withUserConfiguration(CommandCenterConfiguration.class)
        .run(context -> assertThat(context).hasSingleBean(oshi.spi.SystemInfoProvider.class));
  }

  @Test
  void bindsSharedSamplingAndHistorySettings() throws IOException {
    CommandCenterProperties properties = bindProfile("local");

    assertThat(properties.getSampleInterval()).isEqualTo(Duration.ofSeconds(5));
    assertThat(properties.getHistoryDuration()).isEqualTo(Duration.ofMinutes(15));
    assertThat(properties.getActions().getPowerDelay()).isEqualTo(Duration.ofSeconds(60));
  }

  @Test
  void keepsLocalActionsSimulated() throws IOException {
    CommandCenterProperties properties = bindProfile("local");

    assertThat(properties.getActions().getMode())
        .isEqualTo(CommandCenterProperties.ActionMode.SIMULATED);
  }

  @Test
  void bindsFixedProductionHostPaths() throws IOException {
    CommandCenterProperties properties = bindProfile("prod");

    assertThat(properties.getLogPath())
        .isEqualTo(Path.of("C:/ProgramData/christopherbell.dev/logs/ChristopherBellDev.out.log"));
    assertThat(properties.getActions().getMode())
        .isEqualTo(CommandCenterProperties.ActionMode.WINDOWS);
    assertThat(properties.getActions().getWinSwExecutable())
        .isEqualTo(Path.of("C:/ProgramData/christopherbell.dev/service/ChristopherBellDev.exe"));
    assertThat(properties.getActions().getShutdownExecutable())
        .isEqualTo(Path.of("C:/Windows/System32/shutdown.exe"));
    assertThat(properties.getActions().getPowerDelay()).isEqualTo(Duration.ofSeconds(60));
  }

  private CommandCenterProperties bindProfile(String profile) throws IOException {
    StandardEnvironment environment = new StandardEnvironment();
    MutablePropertySources sources = environment.getPropertySources();
    addFirst(sources, load("application.yml"));
    addFirst(sources, load("application-" + profile + ".yml"));

    return Binder.get(environment)
        .bind("command-center", CommandCenterProperties.class)
        .orElseThrow(() -> new AssertionError("command-center configuration was not bound"));
  }

  private List<PropertySource<?>> load(String resourceName) throws IOException {
    return YAML_LOADER.load(resourceName, new ClassPathResource(resourceName));
  }

  private void addFirst(MutablePropertySources sources, List<PropertySource<?>> propertySources) {
    for (int index = propertySources.size() - 1; index >= 0; index--) {
      sources.addFirst(propertySources.get(index));
    }
  }
}
