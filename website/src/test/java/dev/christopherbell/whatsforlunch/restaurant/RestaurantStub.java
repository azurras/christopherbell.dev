package dev.christopherbell.whatsforlunch.restaurant;

import dev.christopherbell.whatsforlunch.restaurant.model.Address;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantCreateRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantDetail;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantUpdateRequest;

public class RestaurantStub {
  public static final String ID = "632d6450-1c80-4e2a-b329-b6b5de486414";
  public static final String ID_2 = "0a80838d-1c02-4b7e-aa3c-5f15ff357a85";
  public static final String NAME = "Pflugerville Taco House";
  public static final String STREET_1 = "104 N Railroad Ave";
  public static final String CITY = "Pflugerville";
  public static final String STATE = "TX";
  public static final String COUNTRY = "USA";
  public static final String POSTAL_CODE = "78660";
  public static final String PHONE_NUMBER = "512-969-5012";
  public static final String WEBSITE = "https://www.pflugervilletacohouse.com";
  public static final double LATITUDE = 30.4423;
  public static final double LONGITUDE = -97.6200;

  public static RestaurantCreateRequest getRestaurantCreateRequestStub() {
    return RestaurantCreateRequest.builder()
        .name(NAME)
        .address(getAddressStub())
        .phoneNumber(PHONE_NUMBER)
        .website(WEBSITE)
        .build();
  }

  public static RestaurantUpdateRequest getRestaurantUpdateRequestStub() {
    return RestaurantUpdateRequest.builder()
        .name(NAME)
        .address(getAddressStub())
        .phoneNumber(PHONE_NUMBER)
        .website(WEBSITE)
        .build();
  }

  public static Restaurant getRestaurantStub(String id) {
    return Restaurant.builder()
        .id(id)
        .name(NAME)
        .address(getAddressStub())
        .phoneNumber(PHONE_NUMBER)
        .website(WEBSITE)
        .build();
  }

  public static RestaurantDetail getRestaurantDetailStub(String id) {
    return RestaurantDetail.builder()
        .id(id)
        .name(NAME)
        .address(getAddressStub())
        .phoneNumber(PHONE_NUMBER)
        .website(WEBSITE)
        .build();
  }

  public static Address getAddressStub() {
    return Address.builder()
        .street1(STREET_1)
        .city(CITY)
        .state(STATE)
        .country(COUNTRY)
        .latitude(LATITUDE)
        .longitude(LONGITUDE)
        .postalCode(POSTAL_CODE)
        .build();
  }
}
