package dev.christopherbell.music.catalog;

import java.nio.file.Path;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** FFprobe adapter that accepts only bounded, structurally valid audio metadata. */
public final class FfprobeMusicProbe implements MusicProbe {
  private static final int MAX_TAG_LENGTH = 300;
  private static final double MAX_DURATION_SECONDS = 604_800;
  private final MusicProperties properties;
  private final MusicProcessRunner runner;
  private final ObjectMapper objectMapper;

  public FfprobeMusicProbe(
      MusicProperties properties,
      MusicProcessRunner runner,
      ObjectMapper objectMapper) {
    this.properties = properties;
    this.runner = runner;
    this.objectMapper = objectMapper;
  }

  @Override
  public MusicProbeResult probe(Path source) {
    if (source == null || !source.isAbsolute()) {
      throw new MusicProbeException("Music probe source must be absolute.");
    }
    var probeResult = runner.run(List.of(
        properties.ffprobeCommand(),
        "-v", "error",
        "-print_format", "json",
        "-show_format",
        "-show_streams",
        source.toAbsolutePath().normalize().toString()));
    if (probeResult.timedOut() || probeResult.outputTruncated() || probeResult.exitCode() != 0
        || probeResult.stderr() == null || !probeResult.stderr().isBlank()) {
      throw new MusicProbeException("FFprobe did not return a bounded successful result.");
    }
    try {
      JsonNode root = objectMapper.readTree(probeResult.stdout());
      JsonNode format = root.path("format");
      JsonNode streams = root.path("streams");
      JsonNode audio = firstStream(streams, "audio");
      if (audio == null) throw new MusicProbeException("FFprobe found no audio stream.");
      double duration = duration(format, audio);
      String codec = clean(audio.path("codec_name").asText(null));
      if (codec == null) throw new MusicProbeException("FFprobe audio codec is missing.");
      JsonNode tags = format.path("tags");
      String container = clean(format.path("format_name").asText(null));
      if (container != null && container.contains(",")) container = container.split(",", 2)[0];
      return new MusicProbeResult(
          tag(tags, "title"),
          tag(tags, "artist"),
          tag(tags, "album_artist"),
          tag(tags, "album"),
          number(tag(tags, "track"), 1, 9999),
          number(tag(tags, "disc"), 1, 999),
          tag(tags, "genre"),
          year(tag(tags, "date")),
          duration,
          codec,
          container,
          hasArtwork(streams));
    } catch (MusicProbeException failure) {
      throw failure;
    } catch (JacksonException failure) {
      throw new MusicProbeException("FFprobe JSON is malformed.", failure);
    }
  }

  private JsonNode firstStream(JsonNode streams, String type) {
    if (!streams.isArray()) return null;
    for (JsonNode stream : streams) {
      if (type.equals(stream.path("codec_type").asText())) return stream;
    }
    return null;
  }

  private boolean hasArtwork(JsonNode streams) {
    if (!streams.isArray()) return false;
    for (JsonNode stream : streams) {
      if ("video".equals(stream.path("codec_type").asText())
          && stream.path("disposition").path("attached_pic").asInt(0) == 1) return true;
    }
    return false;
  }

  private double duration(JsonNode format, JsonNode audio) {
    String rawDuration = format.path("duration").asText(null);
    if (rawDuration == null) rawDuration = audio.path("duration").asText(null);
    try {
      double parsedDurationSeconds = Double.parseDouble(rawDuration);
      if (!Double.isFinite(parsedDurationSeconds)
          || parsedDurationSeconds <= 0
          || parsedDurationSeconds > MAX_DURATION_SECONDS) {
        throw new MusicProbeException("FFprobe duration is outside the supported range.");
      }
      return parsedDurationSeconds;
    } catch (NullPointerException | NumberFormatException failure) {
      throw new MusicProbeException("FFprobe duration is invalid.", failure);
    }
  }

  private String tag(JsonNode tags, String tagName) {
    String rawTagValue = tags.path(tagName).asText(null);
    if (rawTagValue == null) {
      rawTagValue = tags.path(tagName.toUpperCase(java.util.Locale.ROOT)).asText(null);
    }
    return clean(rawTagValue);
  }

  private String clean(String rawMetadataText) {
    if (rawMetadataText == null) return null;
    var cleaned = rawMetadataText.replaceAll("[\\p{Cc}\\p{Cf}]", " ")
        .replaceAll("\\s+", " ")
        .strip();
    if (cleaned.isEmpty()) return null;
    return cleaned.length() <= MAX_TAG_LENGTH ? cleaned : cleaned.substring(0, MAX_TAG_LENGTH);
  }

  private Integer number(String rawNumberTag, int minimumValue, int maximumValue) {
    if (rawNumberTag == null) return null;
    try {
      String leadingNumberText = rawNumberTag.split("/", 2)[0].strip();
      int parsedNumber = Integer.parseInt(leadingNumberText);
      return parsedNumber >= minimumValue && parsedNumber <= maximumValue ? parsedNumber : null;
    } catch (NumberFormatException failure) {
      return null;
    }
  }

  private Integer year(String rawDateTag) {
    if (rawDateTag == null || rawDateTag.length() < 4) return null;
    String rawYearText = rawDateTag.substring(0, 4);
    return number(rawYearText, 1000, 9999);
  }
}
