package dev.christopherbell.music.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MusicComponentWiringTest {

  @Test
  void componentWithTestClockConstructorDesignatesItsProductionConstructor() {
    assertThat(Arrays.stream(MusicAccessAuditRecorder.class.getDeclaredConstructors())
        .filter(constructor -> constructor.isAnnotationPresent(Autowired.class)))
        .hasSize(1);
  }
}
