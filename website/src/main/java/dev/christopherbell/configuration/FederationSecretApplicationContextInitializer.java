package dev.christopherbell.configuration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.PropertySource;
import org.springframework.util.StringUtils;

/** Resolves the production federation key before configuration properties bind. */
public final class FederationSecretApplicationContextInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext>, Ordered {
  static final String SECRET_PROPERTY = "APP_FEDERATION_KEY_ENCRYPTION_SECRET";
  static final String SECRET_FILE_PROPERTY =
      "app.federation.key-encryption-secret-file";

  private static final String DISCOVERY_ENABLED_PROPERTY =
      "app.federation.discovery-enabled";
  private static final String PRODUCTION_SECRET_FILE =
      "C:\\ProgramData\\christopherbell.dev\\config\\federation-key-encryption-secret.bin";
  private static final int SECRET_BYTES = 32;
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Object SECRET_FILE_LOCK = new Object();

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  @Override
  public void initialize(ConfigurableApplicationContext context) {
    var environment = context.getEnvironment();
    if (!environment.acceptsProfiles(Profiles.of("prod"))
        || !environment.getProperty(DISCOVERY_ENABLED_PROPERTY, Boolean.class, false)
        || StringUtils.hasText(environment.getProperty(SECRET_PROPERTY))) {
      return;
    }

    var configuredPath = environment.getProperty(SECRET_FILE_PROPERTY);
    if (!StringUtils.hasText(configuredPath)) {
      throw invalidSecretFile(null);
    }

    byte[] secret = null;
    try {
      var allowAlternatePath = environment.acceptsProfiles(Profiles.of("deploy-smoke"));
      secret = resolveOrCreate(Path.of(configuredPath), allowAlternatePath);
      var encoded = Base64.getEncoder().encodeToString(secret);
      environment.getPropertySources().addFirst(new SecretPropertySource(encoded));
    } catch (RuntimeException | IOException failure) {
      if (failure instanceof IllegalStateException stateFailure
          && stateFailure.getMessage() != null
          && stateFailure.getMessage().contains(SECRET_FILE_PROPERTY)) {
        throw stateFailure;
      }
      throw invalidSecretFile(failure);
    } finally {
      if (secret != null) {
        Arrays.fill(secret, (byte) 0);
      }
    }
  }

  private static byte[] resolveOrCreate(
      Path configuredPath,
      boolean allowAlternatePath
  ) throws IOException {
    synchronized (SECRET_FILE_LOCK) {
      if (!configuredPath.isAbsolute()) {
        throw invalidSecretFile(null);
      }
      var path = configuredPath.normalize();
      if (!allowAlternatePath
          && !path.toString().equalsIgnoreCase(PRODUCTION_SECRET_FILE)) {
        throw invalidSecretFile(null);
      }
      requireProtectedParent(path);
      if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        return readExisting(path);
      }
      return createNew(path);
    }
  }

  private static void requireProtectedParent(Path path) throws IOException {
    var parent = path.getParent();
    if (parent == null
        || Files.isSymbolicLink(parent)
        || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
      throw invalidSecretFile(null);
    }
  }

  private static byte[] readExisting(Path path) throws IOException {
    var attributes = Files.readAttributes(
        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (Files.isSymbolicLink(path)
        || !attributes.isRegularFile()
        || attributes.size() != SECRET_BYTES) {
      throw invalidSecretFile(null);
    }
    var secret = Files.readAllBytes(path);
    if (secret.length != SECRET_BYTES) {
      Arrays.fill(secret, (byte) 0);
      throw invalidSecretFile(null);
    }
    return secret;
  }

  private static byte[] createNew(Path path) throws IOException {
    var generated = new byte[SECRET_BYTES];
    RANDOM.nextBytes(generated);
    var options = Set.<OpenOption>of(
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
        LinkOption.NOFOLLOW_LINKS);
    try (var channel = FileChannel.open(path, options)) {
      var buffer = ByteBuffer.wrap(generated);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      channel.force(true);
      return generated;
    } catch (FileAlreadyExistsException concurrentCreation) {
      Arrays.fill(generated, (byte) 0);
      return readExisting(path);
    } catch (IOException failure) {
      Arrays.fill(generated, (byte) 0);
      throw failure;
    }
  }

  private static IllegalStateException invalidSecretFile(Throwable cause) {
    return new IllegalStateException(
        "Invalid " + SECRET_FILE_PROPERTY
            + ": require an existing protected parent and one regular 32-byte file.",
        cause);
  }

  private static final class SecretPropertySource extends PropertySource<String> {
    private final String encodedSecret;

    private SecretPropertySource(String encodedSecret) {
      super("federationSecret", "redacted");
      this.encodedSecret = encodedSecret;
    }

    @Override
    public Object getProperty(String name) {
      return SECRET_PROPERTY.equals(name) ? encodedSecret : null;
    }
  }
}
