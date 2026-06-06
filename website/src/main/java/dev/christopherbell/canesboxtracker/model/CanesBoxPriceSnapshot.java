package dev.christopherbell.canesboxtracker.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Weekly persisted Raising Canes Box Index price snapshot.
 */
@Data
@Document(collection = "canes_box_price_snapshots")
public class CanesBoxPriceSnapshot {
  @Id
  private String id;
  private String weekStartDate;
  private Instant collectedOn;
  private BigDecimal averagePrice;
  private String currency = "USD";
  private int successfulMetroCount;
  private int totalMetroCount;
  private int verifiedMetroCount;
  private int provisionalMetroCount;
  private int excludedMetroCount;
  private List<CanesBoxMetroPrice> metroPrices = new ArrayList<>();
}
