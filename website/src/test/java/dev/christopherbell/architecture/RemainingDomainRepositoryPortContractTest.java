package dev.christopherbell.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import dev.christopherbell.canesboxtracker.CanesBoxPriceSnapshotRepository;
import dev.christopherbell.canesboxtracker.MongoCanesBoxPriceSnapshotRepository;
import dev.christopherbell.canesboxtracker.model.CanesBoxPriceSnapshot;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.location.model.ZipCoordinate;
import dev.christopherbell.location.model.ZipCoordinateImportState;
import dev.christopherbell.location.zip.MongoZipCoordinateImportStateRepository;
import dev.christopherbell.location.zip.MongoZipCoordinateRepository;
import dev.christopherbell.location.zip.ZipCoordinateImportStateRepository;
import dev.christopherbell.location.zip.ZipCoordinateRepository;
import dev.christopherbell.sharedfolder.audit.MongoSharedFolderAuditRepository;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditEvent;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRepository;
import dev.christopherbell.sharedfolder.media.MediaJob;
import dev.christopherbell.sharedfolder.media.MediaJobRepository;
import dev.christopherbell.sharedfolder.media.MongoMediaJobRepository;
import dev.christopherbell.sharedfolder.radio.MongoSharedFolderRadioRepository;
import dev.christopherbell.sharedfolder.radio.SharedFolderRadioDocument;
import dev.christopherbell.sharedfolder.radio.SharedFolderRadioRepository;
import dev.christopherbell.sharedfolder.recycle.MongoSharedFolderRecycleRepository;
import dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleItem;
import dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleRepository;
import dev.christopherbell.sharedfolder.service.MongoSharedFolderMutationRecoveryRepository;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationRecovery;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationRecoveryRepository;
import dev.christopherbell.sharedfolder.upload.MongoSharedFolderUploadSessionRepository;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadSession;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadSessionRepository;
import dev.christopherbell.vehicle.core.MongoVehicleRepository;
import dev.christopherbell.vehicle.core.VehicleRepository;
import dev.christopherbell.vehicle.model.Vehicle;
import dev.christopherbell.vehicle.model.VehicleVinDecodeCache;
import dev.christopherbell.vehicle.nhtsa.decode.MongoVehicleVinDecodeCacheRepository;
import dev.christopherbell.vehicle.nhtsa.decode.VehicleVinDecodeCacheRepository;
import dev.christopherbell.vehicle.nhtsa.enrichment.MongoNhtsaVinImportStateRepository;
import dev.christopherbell.vehicle.nhtsa.enrichment.NhtsaVinImportStateRepository;
import dev.christopherbell.vehicle.nhtsa.model.NhtsaVinImportState;
import dev.christopherbell.vehicle.randomvin.importing.MongoRandomVinImportStateRepository;
import dev.christopherbell.vehicle.randomvin.importing.RandomVinImportStateRepository;
import dev.christopherbell.vehicle.randomvin.model.RandomVinImportState;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/** Freezes every Task 5 repository method and proves that each routes through its fixed kind. */
class RemainingDomainRepositoryPortContractTest {
  private static final List<AdapterDefinition> ADAPTERS = List.of(
      adapter(CanesBoxPriceSnapshotRepository.class, MongoCanesBoxPriceSnapshotRepository.class,
          CanesBoxPriceSnapshot.class, "findById(String)", "findTop60ByOrderByWeekStartDateDesc()",
          "save(CanesBoxPriceSnapshot)"),
      adapter(ZipCoordinateRepository.class, MongoZipCoordinateRepository.class,
          ZipCoordinate.class, "deleteAll(Iterable)", "findAllBySource(String)",
          "findById(String)", "saveAll(Iterable)"),
      adapter(ZipCoordinateImportStateRepository.class,
          MongoZipCoordinateImportStateRepository.class, ZipCoordinateImportState.class,
          "findById(String)", "save(ZipCoordinateImportState)"),
      adapter(SharedFolderAuditRepository.class, MongoSharedFolderAuditRepository.class,
          SharedFolderAuditEvent.class, "save(SharedFolderAuditEvent)",
          "search(String,String,String,String,Instant,Instant,int)"),
      adapter(MediaJobRepository.class, MongoMediaJobRepository.class, MediaJob.class,
          "cancelActive(String,String,Instant,Instant)", "countByOwnerIdAndStatusIn(String,Collection)",
          "countByStatusIn(Collection)", "deleteById(String)", "findById(String)",
          "findByOwnerIdOrderByIdAsc(String,Pageable)", "findByStatusIn(Collection)",
          "findByStatusInAndCleanupAfterLessThanEqualAndArtifactsCleanedFalseOrderByCleanupAfterAscIdAsc(Collection,Instant,Pageable)",
          "findByStatusOrderByLastAccessedAtAscIdAsc(MediaJobStatus,Pageable)",
          "findFirstByCacheKeyAndStatusInOrderByCreatedAtAsc(String,Collection)",
          "findFirstByCacheKeyAndStatusOrderByUpdatedAtDesc(String,MediaJobStatus)",
          "findFirstByDescriptorPublishedTrueAndStatusInOrderByCreatedAtAsc(Collection)",
          "findFirstByStatusAndDescriptorPublishedFalseOrderByCreatedAtAsc(MediaJobStatus)",
          "save(MediaJob)"),
      adapter(SharedFolderRadioRepository.class, MongoSharedFolderRadioRepository.class,
          SharedFolderRadioDocument.class, "findById(String)", "save(SharedFolderRadioDocument)"),
      adapter(SharedFolderRecycleRepository.class, MongoSharedFolderRecycleRepository.class,
          SharedFolderRecycleItem.class, "deleteById(String)", "findById(String)",
          "findByStateAndExpiresAtBeforeAndRetryAfterLessThanEqualOrderByExpiresAtAscIdAsc(SharedFolderRecycleState,Instant,Instant,Pageable)",
          "findByStateInAndRetryAfterLessThanEqualOrderByDeletedAtAscIdAsc(List,Instant,Pageable)",
          "findByStateOrderByDeletedAtDescIdDesc(SharedFolderRecycleState,Pageable)",
          "save(SharedFolderRecycleItem)"),
      adapter(SharedFolderMutationRecoveryRepository.class,
          MongoSharedFolderMutationRecoveryRepository.class, SharedFolderMutationRecovery.class,
          "claimExpiredOperationLease(String,String,SharedFolderMutationRecoveryState,Instant,String,Instant,Instant)",
          "deleteById(String)", "findById(String)", "findTop100ByOrderByUpdatedAtAsc()",
          "findTop100ByOwnerIdOrderByUpdatedAtAsc(String)",
          "renewOperationLease(String,String,SharedFolderMutationRecoveryState,Instant,Instant)",
          "save(SharedFolderMutationRecovery)"),
      adapter(SharedFolderUploadSessionRepository.class,
          MongoSharedFolderUploadSessionRepository.class, SharedFolderUploadSession.class,
          "claimExpiredAppendLease(String,String,long,Instant,String,Instant,Instant)",
          "claimExpiredFinalizationLease(String,String,SharedFolderUploadFinalizationState,Instant,String,Instant,Instant)",
          "countByOwnerIdAndStateIn(String,Collection)",
          "deferExpiredMaintenance(String,int,Instant,int,Instant)", "deleteById(String)",
          "expireActive(String,Instant,Instant)", "findById(String)",
          "findByOwnerIdOrderByIdAsc(String,Pageable)", "findDueForMaintenance(Instant,Pageable)",
          "renewAppendLease(String,String,long,Instant,Instant)",
          "renewFinalizationLease(String,String,SharedFolderUploadFinalizationState,Instant,Instant)",
          "save(SharedFolderUploadSession)"),
      adapter(VehicleRepository.class, MongoVehicleRepository.class, Vehicle.class,
          "delete(Vehicle)", "existsByVin(String)",
          "findAllByOrderByMakeAscModelAscYearDesc()", "findById(String)",
          "findByMakeIgnoreCase(String)", "findByNotes(String)", "findByVinIsNotNull()",
          "save(Vehicle)", "saveAll(Iterable)"),
      adapter(VehicleVinDecodeCacheRepository.class,
          MongoVehicleVinDecodeCacheRepository.class, VehicleVinDecodeCache.class,
          "findById(String)", "save(VehicleVinDecodeCache)"),
      adapter(NhtsaVinImportStateRepository.class, MongoNhtsaVinImportStateRepository.class,
          NhtsaVinImportState.class, "findById(String)", "save(NhtsaVinImportState)"),
      adapter(RandomVinImportStateRepository.class, MongoRandomVinImportStateRepository.class,
          RandomVinImportState.class, "findById(String)", "save(RandomVinImportState)"));

  @Test
  void everyRemainingPortHasExactlyOneExplicitKindScopedAdapterAndFrozenInventory() {
    assertThat(ADAPTERS).hasSize(13);
    ADAPTERS.forEach(definition -> {
      assertThat(definition.port().isInterface()).isTrue();
      assertThat(definition.adapter().getInterfaces()).contains(definition.port());
      assertThat(signatures(definition.port())).isEqualTo(definition.expectedMethods());
      assertThat(signatures(definition.adapter())).containsAll(definition.expectedMethods());
    });
  }

  @Test
  void everyFrozenMethodCrossesTheKindScopedOperationsBoundary() throws Exception {
    for (var definition : ADAPTERS) {
      var operations = operationsMock();
      var factory = mock(DomainMongoOperationsFactory.class);
      bind(factory, definition.entity(), operations);
      var repository = definition.adapter()
          .getConstructor(DomainMongoOperationsFactory.class)
          .newInstance(factory);

      var methods = List.of(definition.port().getDeclaredMethods()).stream()
          .sorted(Comparator.comparing(RemainingDomainRepositoryPortContractTest::signature))
          .toList();
      for (var method : methods) {
        int before = mockingDetails(operations).getInvocations().size();
        method.invoke(repository, arguments(method));
        var newInvocations = mockingDetails(operations).getInvocations().stream()
            .skip(before)
            .map(invocation -> invocation.getMethod().getName())
            .collect(Collectors.toSet());
        assertThat(newInvocations)
            .as("%s.%s", definition.port().getSimpleName(), signature(method))
            .contains(expectedOperation(method.getName()));
      }
    }
  }

  private static Set<String> signatures(Class<?> type) {
    return java.util.Arrays.stream(type.getDeclaredMethods())
        .map(RemainingDomainRepositoryPortContractTest::signature)
        .collect(Collectors.toSet());
  }

  private static String signature(Method method) {
    return method.getName() + java.util.Arrays.stream(method.getParameterTypes())
        .map(Class::getSimpleName)
        .collect(Collectors.joining(",", "(", ")"));
  }

  private static String expectedOperation(String methodName) {
    if (methodName.equals("save") || methodName.equals("saveAll")) return "save";
    if (methodName.equals("findById")) return "findById";
    if (methodName.startsWith("findFirst")) return "findOne";
    if (methodName.startsWith("find") || methodName.equals("search")) return "find";
    if (methodName.startsWith("count")) return "count";
    if (methodName.startsWith("exists")) return "exists";
    if (methodName.startsWith("delete")) return "remove";
    return "updateFirst";
  }

  private static Object[] arguments(Method method) {
    var types = method.getGenericParameterTypes();
    var arguments = new Object[types.length];
    for (int index = 0; index < types.length; index++) {
      arguments[index] = argument(types[index], method.getParameterTypes()[index]);
    }
    return arguments;
  }

  private static Object argument(Type genericType, Class<?> rawType) {
    if (rawType == String.class) return "value";
    if (rawType == Instant.class) return Instant.parse("2026-08-11T00:00:00Z");
    if (rawType == Pageable.class) return PageRequest.of(0, 10);
    if (rawType == int.class) return 1;
    if (rawType == long.class) return 1L;
    if (rawType.isEnum()) return rawType.getEnumConstants()[0];
    if (Collection.class.isAssignableFrom(rawType) || Iterable.class == rawType) {
      var elementType = (Class<?>) ((ParameterizedType) genericType).getActualTypeArguments()[0];
      return List.of(entityArgument(elementType));
    }
    return entityArgument(rawType);
  }

  private static Object entityArgument(Class<?> type) {
    if (type.isEnum()) return type.getEnumConstants()[0];
    var value = mock(type);
    if (value instanceof ZipCoordinate coordinate) when(coordinate.getZipCode()).thenReturn("75001");
    if (value instanceof Vehicle vehicle) when(vehicle.getId()).thenReturn("vehicle-a");
    return value;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static KindScopedMongoOperations<?> operationsMock() {
    var update = UpdateResult.acknowledged(1, 1L, null);
    return mock(KindScopedMongoOperations.class, invocation -> {
      var name = invocation.getMethod().getName();
      if (name.equals("save") || name.equals("insert")) return invocation.getArgument(0);
      var returnType = invocation.getMethod().getReturnType();
      if (returnType == Optional.class) return Optional.empty();
      if (returnType == List.class) return List.of();
      if (returnType == long.class) return 1L;
      if (returnType == boolean.class) return true;
      if (returnType == UpdateResult.class) return update;
      if (returnType == DeleteResult.class) return DeleteResult.acknowledged(1);
      if (returnType == String.class) return "test";
      return null;
    });
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void bind(
      DomainMongoOperationsFactory factory,
      Class<?> entity,
      KindScopedMongoOperations<?> operations) {
    when(factory.forType((Class) entity)).thenReturn((KindScopedMongoOperations) operations);
  }

  private static AdapterDefinition adapter(
      Class<?> port, Class<?> adapter, Class<?> entity, String... expectedMethods) {
    return new AdapterDefinition(port, adapter, entity, Set.of(expectedMethods));
  }

  private record AdapterDefinition(
      Class<?> port, Class<?> adapter, Class<?> entity, Set<String> expectedMethods) {}
}
