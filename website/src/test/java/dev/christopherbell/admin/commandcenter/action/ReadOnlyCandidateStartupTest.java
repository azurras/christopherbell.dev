package dev.christopherbell.admin.commandcenter.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

import dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleService;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationService;
import dev.christopherbell.vehicle.randomvin.importing.RandomVinImportService;
import dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportWorkflowService;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class ReadOnlyCandidateStartupTest {
  static Stream<Arguments> recoveryListeners() {
    return Stream.of(
        Arguments.of(PendingActionReconciler.class, "reconcileAtApplicationReadiness"),
        Arguments.of(SharedFolderMutationService.class, "reconcileStartup"),
        Arguments.of(SharedFolderRecycleService.class, "reconcilePending"),
        Arguments.of(RandomVinImportService.class, "removeLegacyRandomVinNotes"),
        Arguments.of(RestaurantImportWorkflowService.class, "runMissedMonthlyOpenStreetMapImport"));
  }

  @ParameterizedTest
  @MethodSource("recoveryListeners")
  <T> void smokeCandidateDoesNotDispatchMutatingStartupRecovery(Class<T> type, String method) {
    assertDispatch(type, method, true, 0);
  }

  @ParameterizedTest
  @MethodSource("recoveryListeners")
  <T> void productionStillDispatchesStartupRecovery(Class<T> type, String method) {
    assertDispatch(type, method, false, 1);
  }

  private static <T> void assertDispatch(Class<T> type, String method, boolean smoke, long calls) {
    var listener = mock(type);
    try (var context = new AnnotationConfigApplicationContext()) {
      context.getEnvironment().setActiveProfiles(smoke ? "deploy-smoke" : "prod");
      context.registerBean(type, () -> listener);
      context.refresh();
      context.publishEvent(
          new ApplicationReadyEvent(
              new SpringApplication(), new String[0], context, Duration.ZERO));
      assertThat(
              mockingDetails(listener).getInvocations().stream()
                  .filter(invocation -> invocation.getMethod().getName().equals(method))
                  .count())
          .isEqualTo(calls);
    }
  }
}
