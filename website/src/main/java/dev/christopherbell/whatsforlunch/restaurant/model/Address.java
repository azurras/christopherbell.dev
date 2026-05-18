package dev.christopherbell.whatsforlunch.restaurant.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a physical address of a restaurant.
 */
@AllArgsConstructor
@Builder
@Data
@NoArgsConstructor
public class Address {
  private String city;
  private String county;
  private String country;
  private Double latitude;
  private Double longitude;
  private String postalCode;
  private String state;
  private String street1;
  private String street2;
}
