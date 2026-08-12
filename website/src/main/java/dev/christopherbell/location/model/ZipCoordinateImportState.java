package dev.christopherbell.location.model;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;

/** Durable identity and outcome of the last successful ZIP coordinate dataset import. */
@AllArgsConstructor
@Builder
@Data
@NoArgsConstructor
public class ZipCoordinateImportState {
  @Id private String id;
  private String checksum;
  private String source;
  private int sourceYear;
  private Instant importedOn;
  private ZipCoordinateImportResult result;
}
