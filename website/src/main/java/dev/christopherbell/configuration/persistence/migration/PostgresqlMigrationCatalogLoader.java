package dev.christopherbell.configuration.persistence.migration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.reader.StreamReader;
import org.yaml.snakeyaml.scanner.ScannerImpl;
import org.yaml.snakeyaml.tokens.Token;

/** Parses the checked-in migration catalog once and rejects YAML features that hide structure. */
public final class PostgresqlMigrationCatalogLoader {
  private static final int MAX_BYTES = 1_000_000;
  private static final ObjectMapper MAPPER = new ObjectMapper(YAMLFactory.builder().build())
      .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

  /** Loads one complete catalog from a bounded stream. */
  public PostgresqlMigrationCatalog load(InputStream input) {
    Objects.requireNonNull(input, "input");
    try {
      var bytes = input.readNBytes(MAX_BYTES + 1);
      if (bytes.length > MAX_BYTES) {
        throw failure("size", null);
      }
      var yaml = new String(bytes, StandardCharsets.UTF_8);
      rejectAliases(yaml);
      return PostgresqlMigrationCatalogValidator.validate(
          MAPPER.readValue(bytes, PostgresqlMigrationCatalog.class));
    } catch (PostgresqlMigrationCatalogException failure) {
      throw failure;
    } catch (UnrecognizedPropertyException failure) {
      throw failure(failure.getPropertyName(), failure);
    } catch (StreamReadException failure) {
      var category = failure.getOriginalMessage().toLowerCase().contains("duplicate")
          ? "duplicate catalog key" : "syntax";
      throw failure(category, failure);
    } catch (JsonMappingException failure) {
      if (hasDuplicateCause(failure)) {
        throw failure("duplicate catalog key", failure);
      }
      var cause = failure.getCause();
      var path = cause instanceof IllegalArgumentException && cause.getMessage() != null
          ? cause.getMessage().replace("PostgreSQL migration catalog is invalid at ", "")
          : safePath(failure);
      throw failure(path, failure);
    } catch (IOException failure) {
      throw failure("input", failure);
    } catch (IllegalArgumentException failure) {
      var message = failure.getMessage();
      var path = message != null && message.startsWith("PostgreSQL migration catalog is invalid at ")
          ? message.replace("PostgreSQL migration catalog is invalid at ", "")
          : "validation";
      throw failure(path, failure);
    }
  }

  private static void rejectAliases(String yaml) {
    var options = new LoaderOptions();
    options.setCodePointLimit(MAX_BYTES);
    options.setNestingDepthLimit(64);
    try {
      var scanner = new ScannerImpl(new StreamReader(yaml), options);
      while (!scanner.checkToken(Token.ID.StreamEnd)) {
        var token = scanner.getToken();
        if (token.getTokenId() == Token.ID.Alias || token.getTokenId() == Token.ID.Anchor) {
          throw failure("alias or anchor", null);
        }
      }
      scanner.getToken();
    } catch (PostgresqlMigrationCatalogException failure) {
      throw failure;
    } catch (YAMLException failure) {
      throw failure("syntax", failure);
    }
  }

  private static String safePath(JsonMappingException failure) {
    var path = failure.getPath().stream()
        .map(reference -> reference.getFieldName() == null ? "[]" : reference.getFieldName())
        .reduce((left, right) -> left + '.' + right)
        .orElse("mapping");
    return path.isBlank() ? "mapping" : path;
  }

  private static boolean hasDuplicateCause(Throwable failure) {
    var current = failure;
    while (current != null) {
      if (current.getMessage() != null
          && current.getMessage().toLowerCase().contains("duplicate field")) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private static PostgresqlMigrationCatalogException failure(String path, Throwable cause) {
    return new PostgresqlMigrationCatalogException(
        "PostgreSQL migration catalog rejected " + path + '.', cause);
  }
}
