package dev.christopherbell.sharedfolder.media;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Strict worker handoff schema. There is intentionally no command or extra-argument field. */
public record MediaWorkerJobDescriptor(
    int schemaVersion,
    String jobId,
    String cacheId,
    Path sourcePath,
    Path partialOutputPath,
    Path readyOutputPath,
    Path statusPath,
    Path cancellationPath,
    long sourceSize,
    Instant sourceModifiedAt,
    MediaOutputProfile profile,
    Instant deadline,
    long maxOutputBytes,
    long initialBufferBytes) {

  Map<String, Object> asMap() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("schemaVersion", schemaVersion);
    values.put("jobId", jobId);
    values.put("cacheId", cacheId);
    values.put("sourcePath", sourcePath.toString());
    values.put("partialOutputPath", partialOutputPath.toString());
    values.put("readyOutputPath", readyOutputPath.toString());
    values.put("statusPath", statusPath.toString());
    values.put("cancellationPath", cancellationPath.toString());
    values.put("sourceSize", sourceSize);
    values.put("sourceModifiedAt", sourceModifiedAt.toString());
    values.put("profile", profile.name());
    values.put("deadline", deadline.toString());
    values.put("maxOutputBytes", maxOutputBytes);
    values.put("initialBufferBytes", initialBufferBytes);
    return Map.copyOf(values);
  }
}
