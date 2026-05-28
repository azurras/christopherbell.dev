package dev.christopherbell.view.tools;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves standalone tool pages.
 */
@Controller
public class ToolsViewController {

  /**
   * Serves the VIN decoder tool.
   *
   * @return {@code vin-decoder.html}
   */
  @GetMapping(value = "/vin-decoder")
  public String getVinDecoderPage() {
    return "vin-decoder.html";
  }

  /**
   * Serves the ZIP coordinate lookup tool.
   *
   * @return {@code zip-coordinates.html}
   */
  @GetMapping(value = "/zip-coordinates")
  public String getZipCoordinatesPage() {
    return "zip-coordinates.html";
  }
}
