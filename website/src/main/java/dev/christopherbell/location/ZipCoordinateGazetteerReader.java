package dev.christopherbell.location;

import dev.christopherbell.location.model.ZipCoordinate;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Parses the bundled Census Gazetteer ZIP Code Tabulation Area coordinate data.
 */
@Component
public class ZipCoordinateGazetteerReader {
  public static final String CENSUS_SOURCE = "Census Gazetteer ZCTA";
  public static final int CENSUS_SOURCE_YEAR = 2025;

  private static final String CENSUS_RESOURCE = "location/2025_Gaz_zcta_national.txt";
  private static final int ZIP_CODE_COLUMN = 0;
  private static final int LATITUDE_COLUMN = 6;
  private static final int LONGITUDE_COLUMN = 7;

  /**
   * Reads the bundled Census ZIP coordinate source.
   *
   * @return ZIP coordinates parsed from the bundled Census dataset
   */
  public List<ZipCoordinate> readBundledCensusData() {
    return read(new ClassPathResource(CENSUS_RESOURCE));
  }

  /**
   * Reads ZIP coordinates from a Census Gazetteer resource.
   *
   * @param resource pipe-delimited Census Gazetteer source
   * @return parsed ZIP coordinates
   */
  public List<ZipCoordinate> read(Resource resource) {
    try (var reader = new BufferedReader(new InputStreamReader(
        resource.getInputStream(),
        StandardCharsets.UTF_8))) {
      return readRows(reader);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load Census ZIP coordinate data.", e);
    }
  }

  private List<ZipCoordinate> readRows(BufferedReader reader) throws IOException {
    var header = reader.readLine();
    if (header == null || !header.startsWith("GEOID|")) {
      throw new IllegalStateException("Census ZIP coordinate data is missing the Gazetteer header.");
    }

    var coordinates = new ArrayList<ZipCoordinate>();
    for (var line = reader.readLine(); line != null; line = reader.readLine()) {
      if (!line.isBlank()) {
        coordinates.add(coordinate(line));
      }
    }
    if (coordinates.isEmpty()) {
      throw new IllegalStateException("Census ZIP coordinate data has no Gazetteer rows.");
    }
    return List.copyOf(coordinates);
  }

  private ZipCoordinate coordinate(String line) {
    var columns = line.split("\\|", -1);
    if (columns.length <= LONGITUDE_COLUMN) {
      throw new IllegalStateException("Census ZIP coordinate data has a malformed Gazetteer row.");
    }

    try {
      var zipCode = columns[ZIP_CODE_COLUMN].strip();
      if (!zipCode.matches("\\d{5}")) {
        throw new IllegalStateException("Census ZIP coordinate data has an invalid ZIP code.");
      }
      return ZipCoordinate.builder()
          .zipCode(zipCode)
          .latitude(Double.parseDouble(columns[LATITUDE_COLUMN]))
          .longitude(Double.parseDouble(columns[LONGITUDE_COLUMN]))
          .source(CENSUS_SOURCE)
          .sourceYear(CENSUS_SOURCE_YEAR)
          .build();
    } catch (NumberFormatException e) {
      throw new IllegalStateException("Census ZIP coordinate data has an invalid coordinate.", e);
    }
  }
}
