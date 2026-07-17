package dev.christopherbell.sharedfolder;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.sharedfolder.web.SharedFolderNoStoreFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SharedFolderNoStoreFilterTest {
  @Test
  void appliesNoStoreOnlyToTheExactVersionedSharedFolderApiPrefix() throws Exception {
    var filter = new SharedFolderNoStoreFilter();
    var protectedRequest = new MockHttpServletRequest(
        "GET", "/api/shared-folder/2026-07-17/entries");
    var protectedResponse = new MockHttpServletResponse();

    filter.doFilter(protectedRequest, protectedResponse, (request, response) -> {});

    assertThat(protectedResponse.getHeader("Cache-Control")).isEqualTo("private, no-store");

    var nearMissRequest = new MockHttpServletRequest(
        "GET", "/api/shared-folder/2026-07-17x/entries");
    var nearMissResponse = new MockHttpServletResponse();

    filter.doFilter(nearMissRequest, nearMissResponse, (request, response) -> {});

    assertThat(nearMissResponse.getHeader("Cache-Control")).isNull();
  }
}
