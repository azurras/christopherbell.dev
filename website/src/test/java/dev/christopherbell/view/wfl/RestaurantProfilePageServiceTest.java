package dev.christopherbell.view.wfl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantService;
import dev.christopherbell.whatsforlunch.restaurant.model.Address;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantDetail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class RestaurantProfilePageServiceTest {
  @Mock private RestaurantService restaurants;

  @Test
  void profileBuildsPublicVoteApprovalAndSafeRestaurantJsonLd() throws Exception {
    when(restaurants.getRestaurantById("rest/one")).thenReturn(RestaurantDetail.builder()
        .id("rest/one")
        .name("Taco </script><script>alert(1)</script>")
        .cuisine("Mexican")
        .address(Address.builder()
            .street1("100 Main St")
            .city("Austin")
            .state("TX")
            .postalCode("78701")
            .country("US")
            .latitude(30.2672)
            .longitude(-97.7431)
            .build())
        .phoneNumber("512-555-0100")
        .website("https://example.com/menu")
        .sourceAmenity("restaurant")
        .upVotes(7)
        .downVotes(1)
        .voteCount(8)
        .myFavorite(true)
        .createdBy("private-account")
        .build());

    var page = service().profile("rest/one");

    assertThat(page.canonicalUrl()).endsWith("/wfl/restaurants/rest%2Fone");
    assertThat(page.addressLine()).isEqualTo("100 Main St, Austin, TX, 78701");
    assertThat(page.description()).isEqualTo(
        "Mexican restaurant in Austin, TX. Details and member approval from What's For Lunch.");
    assertThat(page.directionsUrl()).contains("destination=30.2672", "-97.7431");
    assertThat(page.structuredDataJson()).contains("\\u003c/script\\u003e");
    assertThat(page.structuredDataJson()).doesNotContain(
        "</script>", "myVote", "myFavorite", "private-account", "createdBy");

    var json = new ObjectMapper().readTree(page.structuredDataJson());
    assertThat(json.get("@type").asText()).isEqualTo("Restaurant");
    assertThat(json.at("/aggregateRating/ratingValue").asInt()).isEqualTo(88);
    assertThat(json.at("/aggregateRating/bestRating").asInt()).isEqualTo(100);
    assertThat(json.at("/aggregateRating/worstRating").asInt()).isZero();
    assertThat(json.at("/aggregateRating/ratingCount").asInt()).isEqualTo(8);
    assertThat(json.at("/address/addressLocality").asText()).isEqualTo("Austin");
  }

  @Test
  void profileOmitsInvalidOptionalFactsAndFabricatedRating() throws Exception {
    when(restaurants.getRestaurantById("sparse")).thenReturn(RestaurantDetail.builder()
        .id("sparse")
        .name("Sparse Cafe")
        .website("javascript:alert(1)")
        .upVotes(0)
        .downVotes(0)
        .voteCount(0)
        .build());

    var page = service().profile("sparse");

    assertThat(page.hasVotes()).isFalse();
    assertThat(page.website()).isNull();
    assertThat(page.address()).isNull();
    var json = new ObjectMapper().readTree(page.structuredDataJson());
    assertThat(json.has("aggregateRating")).isFalse();
    assertThat(json.has("address")).isFalse();
    assertThat(json.has("sameAs")).isFalse();
    assertThat(json.has("servesCuisine")).isFalse();
  }

  @Test
  void profileRejectsMalformedNonzeroPublicVoteTotals() throws Exception {
    when(restaurants.getRestaurantById("malformed")).thenReturn(RestaurantDetail.builder()
        .id("malformed")
        .name("Malformed Cafe")
        .upVotes(1)
        .downVotes(1)
        .voteCount(1)
        .build());

    assertThatThrownBy(() -> service().profile("malformed"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Restaurant vote summary is invalid.");
  }

  @Test
  void invalidIdUsesTheSameNotFoundBoundaryAsMissingRestaurant() throws Exception {
    when(restaurants.getRestaurantById("bad"))
        .thenThrow(new InvalidRequestException("internal validation detail"));

    assertThatThrownBy(() -> service().profile("bad"))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessage("Restaurant not found.");
  }

  @Test
  void pageValueTypesRejectImpossibleVoteAndCoordinateStates() {
    assertThatThrownBy(() -> new RestaurantProfilePage.VoteSummary(0, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RestaurantProfilePage.Address(
        null, null, null, null, null, null, 91.0, 0.0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private RestaurantProfilePageService service() {
    return new RestaurantProfilePageService(restaurants, new ObjectMapper());
  }
}
