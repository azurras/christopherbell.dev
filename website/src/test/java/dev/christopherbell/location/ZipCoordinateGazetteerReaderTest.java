package dev.christopherbell.location;

import dev.christopherbell.location.zip.ZipCoordinateGazetteerReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ZIP coordinate Gazetteer reader")
class ZipCoordinateGazetteerReaderTest {
  private static final String GAZETTEER_SAMPLE = """
      GEOID|GEOIDFQ|ALAND|AWATER|ALAND_SQMI|AWATER_SQMI|INTPTLAT|INTPTLONG
      78701|860Z200US78701|1|0|1|0|30.271128|-97.743699
      70112|860Z200US70112|1|0|1|0|29.956439|-90.074284
      """;

  private final ZipCoordinateGazetteerReader reader = new ZipCoordinateGazetteerReader();

  @Test
  void readsCensusZipCoordinates() {
    var coordinates = reader.read(resource(GAZETTEER_SAMPLE));

    assertEquals(2, coordinates.size());
    assertEquals("78701", coordinates.getFirst().getZipCode());
    assertEquals(30.271128, coordinates.getFirst().getLatitude());
    assertEquals(-97.743699, coordinates.getFirst().getLongitude());
    assertEquals("Census Gazetteer ZCTA", coordinates.getFirst().getSource());
    assertEquals(2025, coordinates.getFirst().getSourceYear());
  }

  @Test
  void rejectsMalformedRowsBeforeImport() {
    assertThrows(
        IllegalStateException.class,
        () -> reader.read(resource("""
            GEOID|GEOIDFQ|ALAND|AWATER|ALAND_SQMI|AWATER_SQMI|INTPTLAT|INTPTLONG
            78701|860Z200US78701|1|0|1|0|not-a-latitude|-97.743699
            """)));
  }

  @Test
  void rejectsInvalidZipCodeRows() {
    assertThrows(
        IllegalStateException.class,
        () -> reader.read(resource("""
            GEOID|GEOIDFQ|ALAND|AWATER|ALAND_SQMI|AWATER_SQMI|INTPTLAT|INTPTLONG
            7870|860Z200US7870|1|0|1|0|30.271128|-97.743699
            """)));
  }

  @Test
  void rejectsEmptyGazetteerDatasets() {
    assertThrows(
        IllegalStateException.class,
        () -> reader.read(resource("""
            GEOID|GEOIDFQ|ALAND|AWATER|ALAND_SQMI|AWATER_SQMI|INTPTLAT|INTPTLONG
            """)));
  }

  private ByteArrayResource resource(String body) {
    return new ByteArrayResource(body.getBytes(StandardCharsets.UTF_8));
  }
}
