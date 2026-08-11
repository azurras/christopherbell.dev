package dev.christopherbell.music.radio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class MusicRuntimeStateStoreTest {
  @Mock private DomainMongoOperationsFactory factory;
  @Mock private KindScopedMongoOperations<MusicRuntimeStateDocument> states;
  private MusicRuntimeStateStore store;

  @BeforeEach
  void setUp() {
    when(factory.forType(MusicRuntimeStateDocument.class)).thenReturn(states);
    store = new MusicRuntimeStateStore(factory);
  }

  @Test
  void queueAndRadioUseIndependentLegacyIdentitiesWithinOneFixedKind() {
    var queue = queue(3L, "entry-1");
    var radio = radio(8L);
    when(states.save(MusicRuntimeStateDocument.forQueue(queue)))
        .thenReturn(MusicRuntimeStateDocument.forQueue(queue(4L, "entry-1")));
    when(states.save(MusicRuntimeStateDocument.forRadio(radio)))
        .thenReturn(MusicRuntimeStateDocument.forRadio(radio(9L)));

    assertThat(store.saveQueue(queue).version()).isEqualTo(4L);
    assertThat(store.saveRadio(radio).version()).isEqualTo(9L);

    verify(states).save(MusicRuntimeStateDocument.forQueue(queue));
    verify(states).save(MusicRuntimeStateDocument.forRadio(radio));
    assertThat(MusicRuntimeStateDocument.forQueue(queue).id()).isEqualTo("queue");
    assertThat(MusicRuntimeStateDocument.forRadio(radio).id()).isEqualTo("radio");
  }

  @Test
  void readsMapOnlyTheRequestedRuntimeIdentity() {
    var queue = MusicRuntimeStateDocument.forQueue(queue(2L, "entry-1"));
    var radio = MusicRuntimeStateDocument.forRadio(radio(5L));
    when(states.findById("queue")).thenReturn(Optional.of(queue));
    when(states.findById("radio")).thenReturn(Optional.of(radio));

    assertThat(store.findQueue()).contains(queue.toQueueState());
    assertThat(store.findRadio()).contains(radio.toRadioState());
  }

  @Test
  void staleQueueFailurePropagatesWithoutWritingRadioOrFallingBack() {
    var requested = queue(4L, "stale");
    when(states.save(MusicRuntimeStateDocument.forQueue(requested)))
        .thenThrow(new OptimisticLockingFailureException("stale"));

    assertThatThrownBy(() -> store.saveQueue(requested))
        .isInstanceOf(OptimisticLockingFailureException.class);
    verify(states).save(MusicRuntimeStateDocument.forQueue(requested));
  }

  private static MusicQueueState queue(Long version, String entryId) {
    var entry = new MusicQueueState.Entry(
        entryId, "track-1", "token-1", "account-1", Instant.EPOCH);
    return new MusicQueueState(MusicQueueState.ID, List.of(entry), version);
  }

  private static MusicRadioState radio(Long version) {
    return new MusicRadioState(
        MusicRadioState.ID, 3, "track-1", "token-1", Instant.EPOCH, 90,
        MusicRadioState.Source.RADIO, null, version);
  }
}
