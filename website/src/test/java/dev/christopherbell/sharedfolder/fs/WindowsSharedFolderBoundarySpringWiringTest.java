package dev.christopherbell.sharedfolder.fs;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.configuration.SharedFolderProperties;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.util.unit.DataSize;

class WindowsSharedFolderBoundarySpringWiringTest {

  @Test
  void disabledBoundariesAreConstructedByTheRealSpringContextWithoutNativeActivation() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(SharedFolderProperties.class,
          WindowsSharedFolderBoundarySpringWiringTest::disabledProperties);
      context.register(
          WindowsSharedFolderMutationBoundary.class,
          WindowsSharedFolderReadBoundary.class);

      context.refresh();

      assertThat(context.getBean(WindowsSharedFolderMutationBoundary.class).nativeMode()).isFalse();
      assertThat(context.getBean(WindowsSharedFolderReadBoundary.class).nativeMode()).isFalse();
      assertThat(context.getBean(WindowsSharedFolderReadBoundary.class).active()).isFalse();
    }
  }

  private static SharedFolderProperties disabledProperties() {
    return new SharedFolderProperties(
        Path.of("A:/shared-folder-disabled"),
        Path.of("A:/shared-folder-system-disabled"),
        DataSize.ofGigabytes(1),
        DataSize.ofMegabytes(1),
        DataSize.ofGigabytes(1),
        DataSize.ofGigabytes(1),
        Duration.ofDays(1),
        Duration.ofDays(1),
        false);
  }
}
