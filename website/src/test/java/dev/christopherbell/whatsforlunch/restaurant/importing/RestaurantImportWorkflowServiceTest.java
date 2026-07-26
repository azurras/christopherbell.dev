package dev.christopherbell.whatsforlunch.restaurant.importing;

import dev.christopherbell.configuration.mongo.lease.MongoLeaseService;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantImportStateRepository;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantService;
import dev.christopherbell.whatsforlunch.restaurant.config.WflProperties;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantImportResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RestaurantImportWorkflowServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");

  @Mock private MongoLeaseService leases;
  @Mock private PermissionService permissionService;
  @Mock private RestaurantImportPreviewStore previews;
  @Mock private RestaurantImportStateRepository states;
  @Mock private RestaurantService restaurantService;

  private RestaurantImportWorkflowService workflow;

  @BeforeEach
  void setUp() {
    workflow = new RestaurantImportWorkflowService(
        Clock.fixed(NOW, ZoneOffset.UTC),
        leases,
        permissionService,
        previews,
        states,
        restaurantService,
        new WflProperties());
  }

  @Test
  void previewStoresOnlyBoundedMetadataForTheCurrentOperator() throws Exception {
    when(permissionService.getSelfId()).thenReturn("operator-1");
    when(restaurantService.prepareConfiguredMetroImport()).thenReturn(snapshot("checksum-a"));

    var result = workflow.previewOpenStreetMapImport();

    assertEquals("checksum-a", result.checksum());
    assertEquals(2, result.counts().fetched());
    verify(previews).save(any(RestaurantImportPreviewDocument.class));
  }

  @Test
  void applyRejectsAStaleRemoteSnapshotBeforeWriting() throws Exception {
    when(permissionService.getSelfId()).thenReturn("operator-1");
    when(leases.tryAcquire(eq(RestaurantImportWorkflowService.LEASE_NAME), any(), eq(NOW), any()))
        .thenReturn(true);
    when(previews.claim(eq("token-1"), eq("operator-1"), eq(NOW)))
        .thenReturn(Optional.of(preview("checksum-a")));
    when(restaurantService.prepareConfiguredMetroImport()).thenReturn(snapshot("checksum-b"));
    var failure = assertThrows(
        ResponseStatusException.class,
        () -> workflow.applyOpenStreetMapImport("token-1"));

    assertEquals(409, failure.getStatusCode().value());
    verify(restaurantService, never()).applyPreparedImport(any(), any());
    verify(leases).release(eq(RestaurantImportWorkflowService.LEASE_NAME), any());
    verify(states, times(2)).save(any());
  }

  @Test
  void contentionReturnsSkippedLockedWithoutFetchingOrWriting() throws Exception {
    when(permissionService.getSelfId()).thenReturn("operator-1");
    when(leases.tryAcquire(eq(RestaurantImportWorkflowService.LEASE_NAME), any(), eq(NOW), any()))
        .thenReturn(false);

    var result = workflow.applyOpenStreetMapImport("token-1");

    assertEquals(RestaurantImportRunStatus.SKIPPED_LOCKED, result.status());
    verify(restaurantService, never()).prepareConfiguredMetroImport();
    verify(previews, never()).claim(any(), any(), any());
    verify(states).save(any());
  }

  @Test
  void successfulApplyPersistsCountsAndReleasesTheExactLeaseOwner() throws Exception {
    when(permissionService.getSelfId()).thenReturn("operator-1");
    when(leases.tryAcquire(eq(RestaurantImportWorkflowService.LEASE_NAME), any(), eq(NOW), any()))
        .thenReturn(true);
    when(previews.claim(eq("token-1"), eq("operator-1"), eq(NOW)))
        .thenReturn(Optional.of(preview("checksum-a")));
    var snapshot = snapshot("checksum-a");
    when(restaurantService.prepareConfiguredMetroImport()).thenReturn(snapshot);
    when(leases.renew(eq(RestaurantImportWorkflowService.LEASE_NAME), any(), eq(NOW), any()))
        .thenReturn(true);
    when(restaurantService.applyPreparedImport(eq(snapshot), any())).thenReturn(result());

    var outcome = workflow.applyOpenStreetMapImport("token-1");

    assertEquals(RestaurantImportRunStatus.SUCCEEDED, outcome.status());
    assertEquals(1, outcome.result().imported());
    verify(states, times(2)).save(any());
    verify(leases).release(eq(RestaurantImportWorkflowService.LEASE_NAME), any());
  }

  @Test
  void longApplyRenewsLeaseAgainBeforeLaterWrites() throws Exception {
    var advancingClock = org.mockito.Mockito.mock(Clock.class);
    when(advancingClock.instant()).thenReturn(
        NOW,
        NOW,
        NOW.plusSeconds(61),
        NOW.plusSeconds(61),
        NOW.plusSeconds(62),
        NOW.plusSeconds(62));
    when(advancingClock.withZone(any())).thenReturn(advancingClock);
    when(advancingClock.getZone()).thenReturn(ZoneOffset.UTC);
    var advancingWorkflow = new RestaurantImportWorkflowService(
        advancingClock,
        leases,
        permissionService,
        previews,
        states,
        restaurantService,
        new WflProperties());
    when(permissionService.getSelfId()).thenReturn("operator-1");
    when(leases.tryAcquire(eq(RestaurantImportWorkflowService.LEASE_NAME), any(), eq(NOW), any()))
        .thenReturn(true);
    when(previews.claim(eq("token-1"), eq("operator-1"), eq(NOW)))
        .thenReturn(Optional.of(preview("checksum-a")));
    var snapshot = snapshot("checksum-a");
    when(restaurantService.prepareConfiguredMetroImport()).thenReturn(snapshot);
    when(leases.renew(eq(RestaurantImportWorkflowService.LEASE_NAME), any(), any(), any()))
        .thenReturn(true);
    when(restaurantService.applyPreparedImport(eq(snapshot), any())).thenAnswer(invocation -> {
      var guard = invocation.getArgument(1, RestaurantImportLeaseGuard.class);
      guard.verifyHeld();
      guard.verifyHeld();
      return result();
    });

    var outcome = advancingWorkflow.applyOpenStreetMapImport("token-1");

    assertEquals(RestaurantImportRunStatus.SUCCEEDED, outcome.status());
    verify(leases, times(2)).renew(eq(RestaurantImportWorkflowService.LEASE_NAME), any(), any(), any());
  }

  @Test
  void publicFreshnessExcludesOperatorDetailsAndReportsConfiguredCoverage() {
    when(states.findById(eq(RestaurantImportWorkflowService.STATE_ID)))
        .thenReturn(Optional.of(dev.christopherbell.whatsforlunch.restaurant.model.RestaurantImportState.builder()
            .id(RestaurantImportWorkflowService.STATE_ID)
            .lastCompletedOn(NOW.minusSeconds(60))
            .actorAccountId("private-operator")
            .lastErrorCategory("private-error")
            .build()));

    var freshness = workflow.getPublicFreshness();

    assertEquals("OpenStreetMap", freshness.source());
    assertEquals(true, freshness.current());
    org.junit.jupiter.api.Assertions.assertTrue(freshness.cityCoverage().contains("Austin, TX"));
  }

  private RestaurantImportSnapshot snapshot(String checksum) {
    var counts = new RestaurantImportPreviewCounts(2, 1, 0, 0, 1, 0);
    return new RestaurantImportSnapshot(checksum, List.of(), counts, List.of("New Cafe"));
  }

  private RestaurantImportPreviewDocument preview(String checksum) {
    return RestaurantImportPreviewDocument.builder()
        .id("token-1")
        .actorAccountId("operator-1")
        .checksum(checksum)
        .createdOn(NOW.minusSeconds(1))
        .expiresOn(NOW.plusSeconds(60))
        .counts(new RestaurantImportPreviewCounts(2, 1, 0, 0, 1, 0))
        .build();
  }

  private RestaurantImportResult result() {
    return RestaurantImportResult.builder()
        .source("openstreetmap")
        .fetched(2)
        .imported(1)
        .updated(0)
        .skippedExisting(1)
        .skippedInvalid(0)
        .build();
  }
}
