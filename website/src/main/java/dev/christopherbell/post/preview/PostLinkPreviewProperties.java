package dev.christopherbell.post.preview;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import lombok.Data;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/** Startup-validated network, metadata, and cache limits for post link previews. */
@Configuration
@ConfigurationProperties("posts.link-previews")
@Validated
@Data
public class PostLinkPreviewProperties {
  @NotNull @DurationMin(millis = 100)
  private Duration connectTimeout = Duration.ofSeconds(2);
  @NotNull @DurationMin(millis = 100)
  private Duration requestTimeout = Duration.ofSeconds(3);
  @NotNull @DurationMin(millis = 100)
  private Duration overallTimeout = Duration.ofSeconds(5);
  @Min(0) @Max(5)
  private int maxRedirects = 3;
  @Min(1024) @Max(1_048_576)
  private int maxResponseBytes = 262_144;
  @NotEmpty
  private List<String> allowedContentTypes = List.of("text/html", "application/xhtml+xml");
  @Min(1) @Max(10)
  private int maxUrlsPerPost = 3;
  @Min(1) @Max(500)
  private int maxTitleLength = 200;
  @Min(1) @Max(2_000)
  private int maxDescriptionLength = 500;
  @Min(1) @Max(4_096)
  private int maxImageUrlLength = 2_048;
  @NotNull @DurationMin(seconds = 1)
  private Duration successTtl = Duration.ofDays(7);
  @NotNull @DurationMin(seconds = 1)
  private Duration failureTtl = Duration.ofMinutes(15);

  @AssertTrue
  public boolean isRequestWithinOverallTimeout() {
    return requestTimeout == null
        || overallTimeout == null
        || requestTimeout.compareTo(overallTimeout) <= 0;
  }
}
