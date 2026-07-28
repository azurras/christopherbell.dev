package dev.christopherbell.music.radio;

import dev.christopherbell.music.catalog.MusicCatalog;
import dev.christopherbell.music.catalog.MusicTrack;
import dev.christopherbell.music.security.MusicAccessService;
import dev.christopherbell.music.web.MusicTrackView;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Owns optimistic mutations of the one global Music queue. */
@Service
public final class MusicQueueService {
  private final MusicQueueStateRepository queues;
  private final MusicCatalog catalog;
  private final MusicAccessService access;
  private final Clock clock;

  public MusicQueueService(
      MusicQueueStateRepository queues,
      MusicCatalog catalog,
      MusicAccessService access,
      Clock clock) {
    this.queues = queues;
    this.catalog = catalog;
    this.access = access;
    this.clock = clock;
  }

  public MusicQueueView current() {
    access.requireRead();
    return view(load());
  }

  public MusicQueueView add(String trackId, long expectedVersion) {
    var account = access.requireWrite();
    MusicTrack track = ready(trackId);
    MusicQueueState current = exact(expectedVersion);
    if (current.entries().size() >= 1_000) {
      throw new ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE, "Music queue is full.");
    }
    var entries = new ArrayList<>(current.entries());
    entries.add(new MusicQueueState.Entry(
        UUID.randomUUID().toString(), track.id(), track.observedToken(),
        account.getId(), clock.instant()));
    return view(save(new MusicQueueState(MusicQueueState.ID, entries, current.version())));
  }

  public MusicQueueView remove(String entryId, long expectedVersion) {
    access.requireWrite();
    MusicQueueState current = exact(expectedVersion);
    List<MusicQueueState.Entry> entries = current.entries().stream()
        .filter(entry -> !entry.id().equals(entryId)).toList();
    if (entries.size() == current.entries().size()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Music queue item was not found.");
    }
    return view(save(new MusicQueueState(MusicQueueState.ID, entries, current.version())));
  }

  public MusicQueueView reorder(List<String> orderedIds, long expectedVersion) {
    access.requireWrite();
    MusicQueueState current = exact(expectedVersion);
    List<String> safeIds = orderedIds == null ? List.of() : List.copyOf(orderedIds);
    if (safeIds.size() != current.entries().size()
        || new HashSet<>(safeIds).size() != safeIds.size()) {
      throw invalidOrder();
    }
    var byId = new java.util.HashMap<String, MusicQueueState.Entry>();
    current.entries().forEach(entry -> byId.put(entry.id(), entry));
    List<MusicQueueState.Entry> reordered = safeIds.stream().map(byId::get).toList();
    if (reordered.stream().anyMatch(java.util.Objects::isNull)) {
      throw invalidOrder();
    }
    return view(save(new MusicQueueState(MusicQueueState.ID, reordered, current.version())));
  }

  MusicQueueState loadForRadio() {
    return load();
  }

  void consumeForRadio(String entryId) {
    if (entryId == null) {
      return;
    }
    for (int attempt = 0; attempt < 3; attempt++) {
      MusicQueueState current = load();
      List<MusicQueueState.Entry> remaining = current.entries().stream()
          .filter(entry -> !entry.id().equals(entryId)).toList();
      if (remaining.size() == current.entries().size()) {
        return;
      }
      try {
        save(new MusicQueueState(MusicQueueState.ID, remaining, current.version()));
        return;
      } catch (ResponseStatusException conflict) {
        if (conflict.getStatusCode() != HttpStatus.CONFLICT) {
          throw conflict;
        }
      }
    }
  }

  private MusicQueueState exact(long expectedVersion) {
    MusicQueueState current = load();
    if (current.publicVersion() != expectedVersion) {
      throw conflict();
    }
    return current;
  }

  private MusicQueueState load() {
    return queues.findById(MusicQueueState.ID).orElseGet(MusicQueueState::empty);
  }

  private MusicQueueState save(MusicQueueState state) {
    try {
      return queues.save(state);
    } catch (OptimisticLockingFailureException | DuplicateKeyException conflict) {
      throw conflict();
    }
  }

  private MusicQueueView view(MusicQueueState state) {
    List<MusicQueueView.Item> items = state.entries().stream().map(entry -> {
      MusicTrackView track = catalog.findReady(entry.trackId())
          .map(MusicTrackView::from).orElse(null);
      return new MusicQueueView.Item(
          entry.id(), track, entry.enqueuedByAccountId(), entry.enqueuedAt());
    }).toList();
    return new MusicQueueView(state.publicVersion(), items);
  }

  private MusicTrack ready(String trackId) {
    return catalog.findReady(trackId).orElseThrow(() ->
        new ResponseStatusException(HttpStatus.NOT_FOUND, "Music track was not found."));
  }

  private ResponseStatusException conflict() {
    return new ResponseStatusException(HttpStatus.CONFLICT, "Music queue changed. Refresh and retry.");
  }

  private ResponseStatusException invalidOrder() {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Music queue order is invalid.");
  }
}
