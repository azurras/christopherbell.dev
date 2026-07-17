package dev.christopherbell.view.content;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Serves general content pages that are not part of a tool or authenticated
 * Void workflow.
 */
@Controller
public class ContentViewController {

  /**
   * Serves the home page.
   *
   * @return {@code index.html}
   */
  @RequestMapping(value = "/")
  public String getHomePage() {
    return "index.html";
  }

  /**
   * Serves the Blog page.
   *
   * @return {@code blog.html}
   */
  @GetMapping(value = "/blog")
  public String getBlogPage() {
    return "blog.html";
  }

  /**
   * Serves the Photos page.
   *
   * @return {@code photo/photography.html}
   */
  @GetMapping(value = "/photos")
  public String getPhotosPage() {
    return "photo/photography.html";
  }

  /**
   * Serves the Back Office page shell.
   *
   * @return {@code back-office.html}
   */
  @GetMapping(value = "/back-office")
  public String getBackOfficePage() {
    return "back-office.html";
  }

  /**
   * Serves the data-free admin command-center page shell.
   *
   * @return {@code command-center.html}
   */
  @GetMapping(value = "/command-center")
  public String getCommandCenterPage() {
    return "command-center.html";
  }

  /**
   * Serves the data-free shared-folder page shell.
   *
   * @return {@code shared-folder.html}
   */
  @GetMapping(value = "/shared")
  public String getSharedFolderPage() {
    return "shared-folder.html";
  }

  /**
   * Serves the report page.
   *
   * @return {@code report.html}
   */
  @GetMapping(value = "/report")
  public String getReportPage() {
    return "report.html";
  }

  /**
   * Serves The Bell home page.
   *
   * @return {@code thebell/index.html}
   */
  @GetMapping(value = "/thebell")
  public String getTheBellHomePage(HttpServletRequest request) {
    return "thebell/index.html";
  }

  /**
   * Serves The Bell "Tony" page.
   *
   * @return {@code thebell/tony.html}
   */
  @GetMapping(value = "/thebell/tony")
  public String getTheBellTonyPage(HttpServletRequest request) {
    return "thebell/tony.html";
  }
}
