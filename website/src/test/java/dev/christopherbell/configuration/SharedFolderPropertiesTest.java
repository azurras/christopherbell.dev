package dev.christopherbell.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.unit.DataSize;

class SharedFolderPropertiesTest {
  private static final YamlPropertySourceLoader YAML_LOADER = new YamlPropertySourceLoader();

  @Test
  void bindsDefaultLimitsAndRetentionWindows() throws IOException {
    SharedFolderProperties properties = bindProfile(null);

    assertThat(properties.maxUpload()).isEqualTo(DataSize.ofGigabytes(10));
    assertThat(properties.uploadChunk()).isEqualTo(DataSize.ofMegabytes(8));
    assertThat(properties.minimumFreeSpace()).isEqualTo(DataSize.ofGigabytes(100));
    assertThat(properties.transcodeCacheLimit()).isEqualTo(DataSize.ofGigabytes(250));
    assertThat(properties.recycleRetention()).isEqualTo(Duration.ofDays(30));
    assertThat(properties.auditRetention()).isEqualTo(Duration.ofDays(180));
    assertThat(properties.enabled()).isFalse();
  }

  @Test
  void localAndTestProfilesUseBuildOwnedRoots() throws IOException {
    assertThat(bindProfile("local").root())
        .isEqualTo(Path.of("build/shared-folder/local/shared"));
    assertThat(bindProfile("local").systemRoot())
        .isEqualTo(Path.of("build/shared-folder/local/system"));
    assertThat(bindProfile("test").root())
        .isEqualTo(Path.of("build/shared-folder/test/shared"));
    assertThat(bindProfile("test").systemRoot())
        .isEqualTo(Path.of("build/shared-folder/test/system"));
  }

  @Test
  void productionProfileDefaultsToDedicatedWindowsRoots() throws IOException {
    SharedFolderProperties properties = bindProfile("prod");

    assertThat(properties.root()).isEqualTo(Path.of("A:/Shared"));
    assertThat(properties.systemRoot()).isEqualTo(Path.of("A:/Shared-System"));
  }

  private SharedFolderProperties bindProfile(String profile) throws IOException {
    StandardEnvironment environment = new StandardEnvironment();
    MutablePropertySources sources = environment.getPropertySources();
    addFirst(sources, load("application.yml"));
    if (profile != null) {
      addFirst(sources, load("application-" + profile + ".yml"));
    }

    return Binder.get(environment)
        .bind("app.shared-folder", SharedFolderProperties.class)
        .orElseThrow(() -> new AssertionError("shared-folder configuration was not bound"));
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
