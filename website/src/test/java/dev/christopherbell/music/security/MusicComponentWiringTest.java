package dev.christopherbell.music.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;

class MusicComponentWiringTest {

  @Test
  void recorderHasOneConstructorSoSpringInjectsTheApplicationClock() {
    var constructors = MusicAccessAuditRecorder.class.getDeclaredConstructors();

    assertThat(constructors).hasSize(1);
    assertThat(constructors[0].getParameterTypes())
        .containsExactly(MusicAccessAttemptRepository.class, Clock.class);
  }
}
