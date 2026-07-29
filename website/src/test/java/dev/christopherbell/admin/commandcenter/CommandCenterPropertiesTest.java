package dev.christopherbell.admin.commandcenter;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.admin.commandcenter.action.SimulatedCommandExecutor;
import dev.christopherbell.admin.commandcenter.action.WindowsCommandExecutor;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

class CommandCenterPropertiesTest {

  private static final YamlPropertySourceLoader YAML_LOADER = new YamlPropertySourceLoader();
  private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory()
      .getValidator();

  @Test
  void createsOneSharedSystemInfoProvider() {
    new ApplicationContextRunner()
        .withUserConfiguration(CommandCenterConfiguration.class)
        .run(context -> assertThat(context).hasSingleBean(oshi.spi.SystemInfoProvider.class));
  }

  @Test
  void providesManagedDefaultAndDedicatedSchedulers() {
    var configuration = new CommandCenterConfiguration();
    var general = configuration.taskScheduler();
    var metrics = configuration.commandCenterMetricsScheduler();
    var actions = configuration.commandCenterActionScheduler();
    try {
      assertThat(general).isNotSameAs(metrics).isNotSameAs(actions);
      assertThat(((org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler) general)
          .getThreadNamePrefix()).isEqualTo("application-scheduled-");
    } finally {
      ((org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler) general).shutdown();
      ((org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler) metrics).shutdown();
      ((org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler) actions).shutdown();
    }
  }

  @Test
  void generalScheduledWorkUsesNamedDefaultPoolInsteadOfCommandCenterPools() {
    new ApplicationContextRunner()
        .withUserConfiguration(CommandCenterConfiguration.class, SchedulingProbeConfiguration.class)
        .run(context -> {
          var probe = context.getBean(SchedulingProbe.class);
          assertThat(probe.ran.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
          assertThat(probe.threadName).startsWith("application-scheduled-");
        });
  }

  @Configuration
  @EnableScheduling
  static class SchedulingProbeConfiguration {
    @org.springframework.context.annotation.Bean SchedulingProbe schedulingProbe() {
      return new SchedulingProbe();
    }
  }

  static class SchedulingProbe {
    private final java.util.concurrent.CountDownLatch ran = new java.util.concurrent.CountDownLatch(1);
    private volatile String threadName;
    @Scheduled(fixedDelay = 60_000)
    void run() {
      threadName = Thread.currentThread().getName();
      ran.countDown();
    }
  }

  @Test
  void bindsSharedSamplingAndIndependentCpuTemperatureSettings() throws IOException {
    CommandCenterProperties properties = bindProfile("local");

    assertThat(properties.getSampleInterval()).isEqualTo(Duration.ofSeconds(5));
    assertThat(properties.getHistoryDuration()).isEqualTo(Duration.ofMinutes(15));
    assertThat(properties.getProviderTimeout()).isEqualTo(Duration.ofSeconds(2));
    assertThat(properties.getCpuTemperatureRefreshInterval()).isEqualTo(Duration.ofSeconds(30));
    assertThat(properties.getCpuTemperatureProcessTimeout()).isEqualTo(Duration.ofSeconds(20));
    assertThat(properties.getActions().getChallengeTtl()).isEqualTo(Duration.ofMinutes(2));
    assertThat(properties.getActions().getCooldown()).isEqualTo(Duration.ofMinutes(2));
    assertThat(properties.getActions().getPowerDelay()).isEqualTo(Duration.ofSeconds(60));
    assertThat(properties.getActions().getFailedAttempts()).isEqualTo(3);
    assertThat(properties.getActions().getFailedAttemptWindow())
        .isEqualTo(Duration.ofMinutes(15));
  }

  @Test
  void keepsLocalActionsSimulated() throws IOException {
    CommandCenterProperties properties = bindProfile("local");

    assertThat(properties.getActions().getMode())
        .isEqualTo(CommandCenterProperties.ActionMode.SIMULATED);
  }

  @Test
  void selectsExecutorOnlyFromTheClosedConfiguredMode() {
    var properties = new CommandCenterProperties();
    var configuration = new CommandCenterConfiguration();

    assertThat(configuration.commandExecutor(properties))
        .isInstanceOf(SimulatedCommandExecutor.class);

    properties.getActions().setMode(CommandCenterProperties.ActionMode.WINDOWS);
    assertThat(configuration.commandExecutor(properties))
        .isInstanceOf(WindowsCommandExecutor.class);
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
    assertThat(properties.isSensorLibrariesEnabled()).isTrue();
    assertThat(properties.getSensorLibraryDirectory()).isEqualTo(
        Path.of("C:/ProgramData/christopherbell.dev/config/command-center-sensors"));
  }

  @Test
  void invalidPortFailsConfigurationBindingAtStartup() {
    new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration.class)
        .withPropertyValues("command-center.production-port=0")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .rootCause()
              .hasMessageContaining("productionPort")
              .hasMessageContaining("command-center.production-port");
        });
  }

  @Test
  void validatesEveryScalarConfigurationCategory() {
    assertViolation(properties -> properties.setSampleInterval(Duration.ZERO),
        "sampleInterval");
    assertViolation(properties -> properties.setHistoryDuration(Duration.ofDays(2)),
        "historyDuration");
    assertViolation(properties -> properties.setProviderTimeout(Duration.ofMillis(99)),
        "providerTimeout");
    assertViolation(properties -> properties.setCpuTemperatureRefreshInterval(Duration.ZERO),
        "cpuTemperatureRefreshInterval");
    assertViolation(properties -> properties.setCpuTemperatureProcessTimeout(Duration.ZERO),
        "cpuTemperatureProcessTimeout");
    assertViolation(properties -> properties.setLogPath(null), "logPath");
    assertViolation(properties -> properties.setMaxLogLines(0), "maxLogLines");
    assertViolation(properties -> properties.setMaxLogBytes(1023), "maxLogBytes");
    assertViolation(properties -> properties.setProductionPort(65_536), "productionPort");
    assertViolation(properties -> properties.setProductionServiceName(" "),
        "productionServiceName");
    assertViolation(properties -> properties.setCommitIdentifier(" "), "commitIdentifier");
    assertViolation(properties -> properties.setSensorLibraryDirectory(null),
        "sensorLibraryDirectory");
  }

  @Test
  void cascadesActionAndThresholdValidation() {
    assertViolation(properties -> properties.getActions().setMode(null), "actions.mode");
    assertViolation(properties -> properties.getActions().setChallengeTtl(Duration.ofSeconds(4)),
        "actions.challengeTtl");
    assertViolation(properties -> properties.getActions().setCooldown(Duration.ZERO),
        "actions.cooldown");
    assertViolation(properties -> properties.getActions().setPowerDelay(Duration.ofSeconds(601)),
        "actions.powerDelay");
    assertViolation(properties -> properties.getActions().setCommandResultTimeout(
        Duration.ofSeconds(31)), "actions.commandResultTimeout");
    assertViolation(properties -> properties.getActions().setFailedAttempts(11),
        "actions.failedAttempts");
    assertViolation(properties -> properties.getActions().setFailedAttemptWindow(
        Duration.ofSeconds(59)), "actions.failedAttemptWindow");
    assertViolation(properties -> properties.getActions().setMaxChallengesPerActor(0),
        "actions.maxChallengesPerActor");
    assertViolation(properties -> properties.getActions().setMaxChallengesTotal(1025),
        "actions.maxChallengesTotal");
    assertViolation(properties -> properties.getThresholds().setCpuWarningPercent(101),
        "thresholds.cpuWarningPercent");
    assertViolation(properties -> properties.getThresholds()
        .setCpuTemperatureWarningCelsius(151), "thresholds.cpuTemperatureWarningCelsius");
    assertViolation(properties -> properties.getThresholds()
        .setGpuTemperatureWarningCelsius(0), "thresholds.gpuTemperatureWarningCelsius");
    assertViolation(properties -> properties.getThresholds().setDiskFreeWarningPercent(-1),
        "thresholds.diskFreeWarningPercent");
  }

  @Test
  void rejectsInvalidCrossFieldRelationshipsAndWindowsPaths() {
    assertViolation(properties -> properties.setHistoryDuration(Duration.ofSeconds(4)),
        "historyWindowValid");
    assertViolation(properties -> properties.setProviderTimeout(Duration.ofSeconds(6)),
        "providerTimeoutWithinSampleInterval");
    assertViolation(properties -> properties.setCpuTemperatureProcessTimeout(
        Duration.ofSeconds(31)), "cpuProcessTimeoutWithinRefreshInterval");
    assertViolation(properties -> properties.getActions().setPowerDelay(
        Duration.ofMillis(1500)), "actions.powerDelayUsesWholeSeconds");
    assertViolation(properties -> {
      properties.getActions().setMaxChallengesPerActor(9);
      properties.getActions().setMaxChallengesTotal(8);
    }, "actions.challengeLimitsOrdered");
    assertViolation(properties -> {
      properties.getActions().setMode(CommandCenterProperties.ActionMode.WINDOWS);
      properties.getActions().setWinSwExecutable(Path.of("service.exe"));
      properties.getActions().setShutdownExecutable(Path.of("shutdown.exe"));
    }, "actions.windowsExecutablePathsAbsolute");
  }

  @Test
  void shippedProfilesAndSimulatedRelativeDefaultsAreValid() throws IOException {
    assertThat(VALIDATOR.validate(new CommandCenterProperties())).isEmpty();
    assertThat(VALIDATOR.validate(bindProfile("local"))).isEmpty();
    assertThat(VALIDATOR.validate(bindProfile("prod"))).isEmpty();
  }

  @Test
  void recognizesFixedWindowsDrivePathsIndependentlyOfTheBuildHost() {
    assertThat(CommandCenterProperties.Actions.isAbsoluteExecutablePath(
        "C:/ProgramData/christopherbell.dev/service/ChristopherBellDev.exe")).isTrue();
    assertThat(CommandCenterProperties.Actions.isAbsoluteExecutablePath(
        "C:\\Windows\\System32\\shutdown.exe")).isTrue();
    assertThat(CommandCenterProperties.Actions.isAbsoluteExecutablePath("shutdown.exe")).isFalse();
    assertThat(CommandCenterProperties.Actions.isAbsoluteExecutablePath("C:shutdown.exe")).isFalse();
  }

  private static void assertViolation(
      Consumer<CommandCenterProperties> mutation, String expectedPath) {
    var properties = new CommandCenterProperties();
    mutation.accept(properties);

    Set<ConstraintViolation<CommandCenterProperties>> violations = VALIDATOR.validate(properties);

    assertThat(violations)
        .extracting(violation -> violation.getPropertyPath().toString())
        .contains(expectedPath);
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

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(CommandCenterProperties.class)
  static class PropertiesConfiguration {}
}
