package dev.christopherbell.vehicle.randomvin.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Last recorded robots.txt policy decision for RandomVIN collection.
 */
@AllArgsConstructor
@Builder
@Data
@NoArgsConstructor
public class RandomVinRobotsPolicyState {
  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSSxxx", timezone = "UTC")
  private Instant checkedOn;

  private Boolean allowed;
  private String reason;
  private Boolean failClosed;
}
