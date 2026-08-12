package dev.christopherbell.location.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;

/**
 * Persisted coordinate origin for a ZIP Code Tabulation Area.
 */
@AllArgsConstructor
@Builder
@Data
@NoArgsConstructor
public class ZipCoordinate {
  @Id
  private String zipCode;

  private double latitude;
  private double longitude;
  private String source;
  private int sourceYear;

  @CreatedDate
  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
  private Instant createdOn;

  @LastModifiedDate
  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
  private Instant lastUpdatedOn;
}
