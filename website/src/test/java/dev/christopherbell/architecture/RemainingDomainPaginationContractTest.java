package dev.christopherbell.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.sharedfolder.media.MediaJob;
import dev.christopherbell.sharedfolder.media.MongoMediaJobRepository;
import dev.christopherbell.sharedfolder.recycle.MongoSharedFolderRecycleRepository;
import dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleItem;
import dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleState;
import dev.christopherbell.sharedfolder.upload.MongoSharedFolderUploadSessionRepository;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadSession;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Query;

class RemainingDomainPaginationContractTest {
  private static final int PAGE_SIZE = 10;

  @ParameterizedTest
  @ValueSource(ints = {PAGE_SIZE - 1, PAGE_SIZE, PAGE_SIZE + 1})
  void mediaSliceUsesOneLookAheadRowAndReportsExactHasNext(int returnedRows) {
    var harness = harness(MediaJob.class, jobs(returnedRows));
    var repository = new MongoMediaJobRepository(harness.factory());
    var page = PageRequest.of(2, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt"));

    var result = repository.findByOwnerIdOrderByIdAsc("owner-a", page);

    assertThat(result.getContent()).hasSize(Math.min(returnedRows, PAGE_SIZE));
    assertThat(result.hasNext()).isEqualTo(returnedRows > PAGE_SIZE);
    assertQuery(harness.operations(), 20, 11,
        new Document("id", 1).append("createdAt", -1));
  }

  @Test
  void recycleSliceUsesTheSharedLookAheadAndDynamicSortContract() {
    var harness = harness(SharedFolderRecycleItem.class, recycleItems(PAGE_SIZE + 1));
    var repository = new MongoSharedFolderRecycleRepository(harness.factory());
    var page = PageRequest.of(1, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "expiresAt"));

    var result = repository.findByStateOrderByDeletedAtDescIdDesc(
        SharedFolderRecycleState.RECYCLED, page);

    assertThat(result.getContent()).hasSize(PAGE_SIZE);
    assertThat(result.hasNext()).isTrue();
    assertQuery(harness.operations(), 10, 11,
        new Document("deletedAt", -1).append("id", -1).append("expiresAt", 1));
  }

  @Test
  void uploadSliceUsesTheSharedLookAheadAndDynamicSortContract() {
    var harness = harness(SharedFolderUploadSession.class, uploads(PAGE_SIZE + 1));
    var repository = new MongoSharedFolderUploadSessionRepository(harness.factory());
    var page = PageRequest.of(3, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "updatedAt"));

    var result = repository.findDueForMaintenance(
        Instant.parse("2026-08-11T00:00:00Z"), page);

    assertThat(result.getContent()).hasSize(PAGE_SIZE);
    assertThat(result.hasNext()).isTrue();
    assertQuery(harness.operations(), 30, 11, new Document("updatedAt", -1));
  }

  private static void assertQuery(
      KindScopedMongoOperations<?> operations, long skip, int limit, Document sort) {
    var query = ArgumentCaptor.forClass(Query.class);
    var page = ArgumentCaptor.forClass(Pageable.class);
    org.mockito.Mockito.verify(operations).find(query.capture(), page.capture());
    assertThat(page.getValue().isUnpaged()).isTrue();
    assertThat(query.getValue().getSkip()).isEqualTo(skip);
    assertThat(query.getValue().getLimit()).isEqualTo(limit);
    assertThat(query.getValue().getSortObject()).isEqualTo(sort);
  }

  private static List<MediaJob> jobs(int count) {
    return IntStream.range(0, count).mapToObj(ignored -> mock(MediaJob.class)).toList();
  }

  private static List<SharedFolderRecycleItem> recycleItems(int count) {
    return IntStream.range(0, count)
        .mapToObj(ignored -> mock(SharedFolderRecycleItem.class))
        .toList();
  }

  private static List<SharedFolderUploadSession> uploads(int count) {
    return IntStream.range(0, count)
        .mapToObj(ignored -> mock(SharedFolderUploadSession.class))
        .toList();
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static <T> Harness<T> harness(Class<T> type, List<T> values) {
    var operations = (KindScopedMongoOperations<T>) mock(KindScopedMongoOperations.class);
    when(operations.find(any(Query.class), any(Pageable.class))).thenReturn(values);
    var factory = mock(DomainMongoOperationsFactory.class);
    when(factory.forType((Class) type)).thenReturn((KindScopedMongoOperations) operations);
    return new Harness<>(factory, operations);
  }

  private record Harness<T>(
      DomainMongoOperationsFactory factory, KindScopedMongoOperations<T> operations) {}
}
