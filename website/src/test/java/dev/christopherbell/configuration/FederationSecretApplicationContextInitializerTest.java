package dev.christopherbell.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class FederationSecretApplicationContextInitializerTest {
  private static final String SECRET_PROPERTY = "APP_FEDERATION_KEY_ENCRYPTION_SECRET";
  private static final String SECRET_FILE_PROPERTY =
      "app.federation.key-encryption-secret-file";

  @TempDir
  Path temporaryDirectory;

  private final FederationSecretApplicationContextInitializer initializer =
      new FederationSecretApplicationContextInitializer();

  @Test
  void enabledProductionCreatesAndReusesOneStableSecret() throws IOException {
    var secretFile = protectedSecretFile();

    var first = initialize(enabledValues(secretFile), "prod", "deploy-smoke");
    var firstEncoded = first.getEnvironment().getProperty(SECRET_PROPERTY);
    var stored = Files.readAllBytes(secretFile);

    assertThat(Base64.getDecoder().decode(firstEncoded)).hasSize(32).isEqualTo(stored);

    var second = initialize(enabledValues(secretFile), "prod", "deploy-smoke");
    assertThat(second.getEnvironment().getProperty(SECRET_PROPERTY)).isEqualTo(firstEncoded);
    assertThat(Files.readAllBytes(secretFile)).isEqualTo(stored);
  }

  @Test
  void explicitSecretOverrideDoesNotTouchTheFile() throws IOException {
    var secretFile = protectedSecretFile();
    var explicit = Base64.getEncoder().encodeToString(new byte[32]);
    var values = enabledValues(secretFile);
    values.put(SECRET_PROPERTY, explicit);

    var context = initialize(values, "prod");

    assertThat(context.getEnvironment().getProperty(SECRET_PROPERTY)).isEqualTo(explicit);
    assertThat(secretFile).doesNotExist();
  }

  @Test
  void disabledAndNonProductionContextsDoNotTouchTheFile() throws IOException {
    var disabledFile = protectedSecretFile();
    var disabled = enabledValues(disabledFile);
    disabled.put("app.federation.discovery-enabled", "false");

    assertThatCode(() -> initialize(disabled, "prod")).doesNotThrowAnyException();
    assertThat(disabledFile).doesNotExist();

    var localFile = temporaryDirectory.resolve("local").resolve("secret.bin");
    Files.createDirectory(localFile.getParent());
    assertThatCode(() -> initialize(enabledValues(localFile), "local"))
        .doesNotThrowAnyException();
    assertThat(localFile).doesNotExist();
  }

  @Test
  void missingParentFailsWithoutCreatingAWeakerDirectory() {
    var secretFile = temporaryDirectory.resolve("missing").resolve("secret.bin");

    assertThatThrownBy(() -> initialize(
        enabledValues(secretFile), "prod", "deploy-smoke"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(SECRET_FILE_PROPERTY)
        .hasMessageNotContaining(Base64.getEncoder().encodeToString(new byte[32]));
    assertThat(secretFile.getParent()).doesNotExist();
  }

  @Test
  void wrongSizedExistingSecretFailsWithoutLeakingItsBytes() throws IOException {
    var secretFile = protectedSecretFile();
    var malformed = "this-value-must-never-appear".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Files.write(secretFile, malformed);

    assertThatThrownBy(() -> initialize(
        enabledValues(secretFile), "prod", "deploy-smoke"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(SECRET_FILE_PROPERTY)
        .hasMessageNotContaining(new String(malformed, java.nio.charset.StandardCharsets.UTF_8));
  }

  @Test
  void symbolicSecretFileFailsClosedWhenSupported() throws IOException {
    var secretFile = protectedSecretFile();
    var target = secretFile.resolveSibling("target.bin");
    Files.write(target, new byte[32]);
    try {
      Files.createSymbolicLink(secretFile, target);
    } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
      assumeTrue(false, "Symbolic links are unavailable to this test process");
    }

    assertThatThrownBy(() -> initialize(
        enabledValues(secretFile), "prod", "deploy-smoke"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(SECRET_FILE_PROPERTY);
  }

  @Test
  void productionRejectsAnAlternateAbsoluteSecretPath() throws IOException {
    var secretFile = protectedSecretFile().toAbsolutePath();

    assertThatThrownBy(() -> initialize(enabledValues(secretFile), "prod"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(SECRET_FILE_PROPERTY);
    assertThat(secretFile).doesNotExist();
  }

  @Test
  void concurrentInitializersUseTheSingleWinningFile() throws Exception {
    var secretFile = protectedSecretFile();
    try (var executor = Executors.newFixedThreadPool(8)) {
      var calls = new ArrayList<Callable<String>>();
      for (int index = 0; index < 8; index++) {
        calls.add(() -> initialize(enabledValues(secretFile), "prod", "deploy-smoke")
            .getEnvironment().getProperty(SECRET_PROPERTY));
      }

      var results = executor.invokeAll(calls).stream()
          .map(future -> {
            try {
              return future.get();
            } catch (Exception failure) {
              throw new IllegalStateException(failure);
            }
          })
          .toList();

      assertThat(results).containsOnly(results.getFirst());
      assertThat(Base64.getDecoder().decode(results.getFirst()))
          .isEqualTo(Files.readAllBytes(secretFile));
    }
  }

  private Path protectedSecretFile() throws IOException {
    var parent = temporaryDirectory.resolve("config-" + java.util.UUID.randomUUID());
    Files.createDirectory(parent);
    return parent.resolve("federation-secret.bin");
  }

  private static LinkedHashMap<String, String> enabledValues(Path secretFile) {
    var values = new LinkedHashMap<String, String>();
    values.put("app.federation.discovery-enabled", "true");
    values.put(SECRET_FILE_PROPERTY, secretFile.toString());
    return values;
  }

  private GenericApplicationContext initialize(
      Map<String, String> values,
      String... profiles
  ) {
    var environment = new MockEnvironment();
    environment.setActiveProfiles(profiles);
    values.forEach(environment::setProperty);
    var context = new GenericApplicationContext();
    context.setEnvironment(environment);
    initializer.initialize(context);
    return context;
  }
}
