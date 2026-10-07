package dev.christopherbell.location.zip;

import dev.christopherbell.location.model.ZipCoordinate;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
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
  private static final String GAZETTEER_HEADER_PREFIX = "GEOID|";
  private static final Pattern COLUMN_SEPARATOR = Pattern.compile("\\|");
  private static final Pattern FIVE_DIGIT_ZIP = Pattern.compile("\\d{5}");
  private static final int ZIP_CODE_COLUMN = 0;
  private static final int LATITUDE_COLUMN = 6;
  private static final int LONGITUDE_COLUMN = 7;

  /**
   * Reads the bundled Census ZIP coordinate source.
   *
   * @return ZIP coordinates parsed from the bundled Census dataset
   */
  public List<ZipCoordinate> readBundledCensusData() {
    return readCoordinatesFrom(new ClassPathResource(CENSUS_RESOURCE));
  }

  /**
   * Reads ZIP coordinates from a pipe-delimited Census Gazetteer resource.
   *
   * @param gazetteerResource the Gazetteer file
   * @return the parsed ZIP coordinates in file order
   * @throws IllegalStateException if the resource cannot be read, lacks the Gazetteer header, has
   *     no data rows, or has a malformed row
   */
  public List<ZipCoordinate> readCoordinatesFrom(Resource gazetteerResource) {
    try (BufferedReader gazetteerLines = new BufferedReader(new InputStreamReader(
        gazetteerResource.getInputStream(), StandardCharsets.UTF_8))) {
      return readDataRows(gazetteerLines);
    } catch (IOException readFailure) {
      throw new IllegalStateException("Failed to load Census ZIP coordinate data.", readFailure);
    }
  }

  private List<ZipCoordinate> readDataRows(BufferedReader gazetteerLines) throws IOException {
    String headerLine = gazetteerLines.readLine();
    if (headerLine == null || !headerLine.startsWith(GAZETTEER_HEADER_PREFIX)) {
      throw new IllegalStateException("Census ZIP coordinate data is missing the Gazetteer header.");
    }

    List<ZipCoordinate> coordinates = new ArrayList<>();
    for (String row = gazetteerLines.readLine(); row != null; row = gazetteerLines.readLine()) {
      if (!row.isBlank()) {
        coordinates.add(coordinateFromRow(row));
      }
    }
    if (coordinates.isEmpty()) {
      throw new IllegalStateException("Census ZIP coordinate data has no Gazetteer rows.");
    }
    return List.copyOf(coordinates);
  }

  private ZipCoordinate coordinateFromRow(String row) {
    String[] columns = COLUMN_SEPARATOR.split(row, -1);
    if (columns.length <= LONGITUDE_COLUMN) {
      throw new IllegalStateException("Census ZIP coordinate data has a malformed Gazetteer row.");
    }

    String zipCode = columns[ZIP_CODE_COLUMN].strip();
    if (!FIVE_DIGIT_ZIP.matcher(zipCode).matches()) {
      throw new IllegalStateException("Census ZIP coordinate data has an invalid ZIP code.");
    }
    try {
      return ZipCoordinate.builder()
          .zipCode(zipCode)
          .latitude(Double.parseDouble(columns[LATITUDE_COLUMN]))
          .longitude(Double.parseDouble(columns[LONGITUDE_COLUMN]))
          .source(CENSUS_SOURCE)
          .sourceYear(CENSUS_SOURCE_YEAR)
          .build();
    } catch (NumberFormatException invalidCoordinate) {
      throw new IllegalStateException(
          "Census ZIP coordinate data has an invalid coordinate.", invalidCoordinate);
    }
  }
}
