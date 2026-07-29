package dev.christopherbell.whatsforlunch.restaurant.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

@ExtendWith(MockitoExtension.class)
class WhatsForLunchSessionMembershipsTest {
  @Mock private MongoTemplate mongo;

  @Test
  void joinIfCapacityRemains_whenAtomicUpdateSucceeds_returnsJoined() {
    var memberships = new WhatsForLunchSessionMemberships(mongo);
    when(mongo.findAndModify(any(), any(), any(), org.mockito.ArgumentMatchers.eq(WhatsForLunchSession.class)))
        .thenReturn(WhatsForLunchSession.builder().id("session-1").build());

    var outcome = memberships.joinIfCapacityRemains("session-1", "friend-id", "friend", 21);

    assertEquals(SessionJoinOutcome.JOINED, outcome);
    verify(mongo).findAndModify(any(), any(), any(), org.mockito.ArgumentMatchers.eq(WhatsForLunchSession.class));
  }
}
