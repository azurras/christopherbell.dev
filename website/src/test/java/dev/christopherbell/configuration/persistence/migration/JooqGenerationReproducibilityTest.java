package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.tools.ToolProvider;
import org.jooq.Field;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Configuration;
import org.jooq.meta.jaxb.Database;
import org.jooq.meta.jaxb.Generate;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Jdbc;
import org.jooq.meta.jaxb.SchemaMappingType;
import org.jooq.meta.jaxb.Target;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class JooqGenerationReproducibilityTest {
  private static final String PACKAGE_NAME = "dev.christopherbell.persistence.jooq";

  @TempDir Path temporaryDirectory;

  @Test
  void twoCleanPrefixedSchemasGenerateByteIdenticalCanonicalSources() throws Exception {
    var first = generateFromCleanSchema(temporaryDirectory.resolve("first"));
    var second = generateFromCleanSchema(temporaryDirectory.resolve("second"));

    assertThat(first.sources()).isNotEmpty().isEqualTo(second.sources());
    var firstHash = canonicalSourceHash(first.sources());
    var secondHash = canonicalSourceHash(second.sources());
    assertThat(firstHash).isEqualTo(secondHash);
    assertThat(first.sources().values())
        .allMatch(source -> !source.contains(first.prefix()) && !source.contains(second.prefix()));
    assertThat(first.sources().keySet()).anyMatch(path -> path.endsWith("Identity.java"));
    assertThat(first.sources().keySet()).anyMatch(path -> path.endsWith("Restaurant.java"));
    assertGeneratedVinAndPreferenceTypes(first);
    System.out.println("Canonical jOOQ source SHA-256: " + firstHash);
  }

  private void assertGeneratedVinAndPreferenceTypes(GeneratedSources generated) throws Exception {
    var classes = temporaryDirectory.resolve("compiled-generated");
    Files.createDirectories(classes);
    var compiler = ToolProvider.getSystemJavaCompiler();
    assertThat(compiler).as("JDK compiler").isNotNull();
    try (var files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
      var sourcePaths = generated.sources().keySet().stream()
          .map(generated.root()::resolve)
          .toList();
      var units = files.getJavaFileObjectsFromPaths(sourcePaths);
      var compiled = compiler.getTask(
          null,
          files,
          null,
          List.of("-classpath", System.getProperty("java.class.path"), "-d", classes.toString()),
          null,
          units).call();
      assertThat(compiled).isTrue();
    }

    try (var loader = new URLClassLoader(
        new java.net.URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
      var vinTable = generatedTable(loader,
          "dev.christopherbell.persistence.jooq.mobility.tables.VinDecodeCache",
          "VIN_DECODE_CACHE");
      var body = (Field<?>) vinTable.getClass().getField("BODY").get(vinTable);
      assertThat(body.getDataType().getType()).isEqualTo(String.class);
      assertThat(body.getDataType().nullable()).isTrue();

      var preferenceTable = generatedTable(loader,
          "dev.christopherbell.persistence.jooq.lunch.tables.LunchPreference",
          "LUNCH_PREFERENCE");
      var radius = (Field<?>) preferenceTable.getClass().getField("RADIUS_MILES")
          .get(preferenceTable);
      assertThat(radius.getDataType().nullable()).isTrue();
    }
  }

  private static Object generatedTable(
      ClassLoader loader, String className, String singletonField) throws Exception {
    var tableClass = Class.forName(className, true, loader);
    return tableClass.getField(singletonField).get(null);
  }

  private static GeneratedSources generateFromCleanSchema(Path target) throws Exception {
    try (var database = PostgresqlSchemaTestSupport.migrate()) {
      var jdbc = database.jdbcConfiguration();
      GenerationTool.generate(new Configuration()
          .withJdbc(new Jdbc()
              .withDriver("org.postgresql.Driver")
              .withUrl(jdbc.url())
              .withUser(jdbc.username())
              .withPassword(jdbc.password()))
          .withGenerator(new Generator()
              .withDatabase(new Database()
                  .withName("org.jooq.meta.postgres.PostgresDatabase")
                  .withIncludes(".*")
                  .withExcludes("flyway_schema_history")
                  .withIncludeIndexes(true)
                  .withIncludePrimaryKeys(true)
                  .withIncludeUniqueKeys(true)
                  .withIncludeForeignKeys(true)
                  .withSchemata(PostgresqlSchemaTestSupport.DOMAINS.stream()
                      .map(domain -> new SchemaMappingType()
                          .withInputSchema(database.prefix() + domain)
                          .withOutputSchema(domain))
                      .toList()))
              .withGenerate(new Generate()
                  .withDeprecated(false)
                  .withRecords(true)
                  .withPojos(false)
                  .withDaos(false)
                  .withImplicitJoinPathsToOne(false)
                  .withImplicitJoinPathsToMany(false)
                  .withImplicitJoinPathsManyToMany(false)
                  .withGeneratedAnnotationDate(false)
                  .withGeneratedAnnotationJooqVersion(false))
              .withTarget(new Target()
                  .withPackageName(PACKAGE_NAME)
                  .withDirectory(target.toString()))));
      return new GeneratedSources(database.prefix(), target, readSources(target));
    }
  }

  private static Map<String, String> readSources(Path root) throws IOException {
    var sources = new TreeMap<String, String>();
    try (var paths = Files.walk(root)) {
      paths.filter(path -> path.getFileName().toString().endsWith(".java"))
          .sorted()
          .forEach(path -> sources.put(
              root.relativize(path).toString().replace('\\', '/'), readUtf8(path)));
    }
    return Map.copyOf(sources);
  }

  private static String readUtf8(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new IllegalStateException("Generated source could not be read: " + path, failure);
    }
  }

  private static String canonicalSourceHash(Map<String, String> sources) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      sources.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
        digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
      });
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable.", failure);
    }
  }

  private record GeneratedSources(String prefix, Path root, Map<String, String> sources) {}
}
