package dev.christopherbell.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.christopherbell.Application;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class SchedulingConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(SchedulingConfiguration.class);

  @Test
  void applicationEntryPointDoesNotEnableSchedulingUnconditionally() {
    assertThat(Application.class.getAnnotation(EnableScheduling.class)).isNull();
  }

  @Test
  void schedulingIsOwnedByPropertyControlledConfiguration() {
    assertThatCode(() -> Class.forName(
        "dev.christopherbell.configuration.SchedulingConfiguration"))
        .doesNotThrowAnyException();
  }

  @Test
  void enablesSchedulingByDefault() {
    contextRunner.run(context -> assertThat(context)
        .hasSingleBean(ScheduledAnnotationBeanPostProcessor.class));
  }

  @Test
  void disablesSchedulingForMutationFreeSmokeRuns() {
    contextRunner
        .withPropertyValues("app.scheduling.enabled=false")
        .run(context -> assertThat(context)
            .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));
  }

  @Test
  void testProfileDisablesScheduledWork() throws IOException {
    var testProfileProperties = new YamlPropertySourceLoader()
        .load("application-test", new ClassPathResource("application-test.yml"));

    contextRunner
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .addFirst(testProfileProperties.getFirst()))
        .run(context -> assertThat(context)
            .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));
  }
}
