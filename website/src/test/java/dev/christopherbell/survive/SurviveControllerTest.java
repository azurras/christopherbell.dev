package dev.christopherbell.survive;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.christopherbell.account.api.LoginTokensFixture;
import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.configuration.security.SecurityConfig;
import dev.christopherbell.configuration.security.BrowserAuthenticationCookies;
import dev.christopherbell.configuration.security.browser.BrowserSessionService;
import dev.christopherbell.configuration.security.browser.InteractiveBrowserRequest;
import dev.christopherbell.libs.api.controller.ControllerExceptionHandler;
import dev.christopherbell.permission.PermissionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@WebMvcTest(SurviveController.class)
@Import({SurviveService.class, SecurityConfig.class,
    LoginTokensFixture.TestConfigurationWithLoginTokens.class, BrowserAuthenticationCookies.class,
    InteractiveBrowserRequest.class, ControllerExceptionHandler.class})
class SurviveControllerTest {
  @org.springframework.boot.test.context.TestConfiguration
  static class Storage {
    @org.springframework.context.annotation.Bean
    dev.christopherbell.survive.persistence.SurviveWorldRepository worlds() {
      return new InMemorySurviveWorldRepository();
    }
  }
  @Autowired private MockMvc mvc;
  @Autowired private org.springframework.web.context.WebApplicationContext context;

  @org.junit.jupiter.api.BeforeEach
  void useTheSecurityChainWithoutRegisteringItsFilterBeansTwice() {
    mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
        .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
  }
  @MockitoBean(name = "permissionService") private PermissionService permissionService;
  @MockitoBean private AccountRepository accounts;
  @MockitoBean private BrowserSessionService browserSessions;

  @Test
  void accountSurvivorResumesWithoutGuestCookieAndRepeatedJoinCannotReplaceIt() throws Exception {
    var account = dev.christopherbell.account.model.Account.builder().id("saved-owner")
        .role(dev.christopherbell.account.model.Role.USER).status(dev.christopherbell.account.model.AccountStatus.ACTIVE)
        .permissions(java.util.Set.of()).build();
    org.mockito.Mockito.when(accounts.findById("saved-owner")).thenReturn(java.util.Optional.of(account));
    String credential = "Bearer " + LoginTokensFixture.localDevelopmentLoginTokens().issueFor(account);
    var joined = mvc.perform(post("/api/survive/v1/game").header("Authorization", credential).with(csrf())
        .contentType("application/json").content("{\"name\":\"Saved survivor\"}"))
        .andExpect(status().isOk()).andReturn();
    org.mockito.Mockito.verify(accounts).findById("saved-owner");
    org.junit.jupiter.api.Assertions.assertTrue(joined.getResponse().getContentAsString().contains("\"saved\":true"));
    var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(joined.getResponse().getContentAsString());
    mvc.perform(get("/api/survive/v1/game").header("Authorization", credential))
        .andExpect(status().isOk()).andExpect(jsonPath("$.survivorId").value(json.get("survivorId").asText()))
        .andExpect(jsonPath("$.saved").value(true));
    mvc.perform(post("/api/survive/v1/game").header("Authorization", credential).with(csrf())
        .contentType("application/json").content("{\"name\":\"Replacement attempt\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Saved survivor"));
  }

  @Test
  void anonymousGiftsKeepCsrfAndValidateTheRequestBeforeMutation() throws Exception {
    String gift = "{\"recipientId\":\"missing\",\"resource\":\"WOOD\",\"quantity\":1,\"revision\":0}";
    mvc.perform(post("/api/survive/v1/gifts").contentType("application/json").content(gift))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/survive/v1/gifts").with(csrf()).contentType("application/json").content(gift))
        .andExpect(status().isNotFound());
    for (var body : new String[] {"{}", gift.replace("WOOD", "STONE"),
        gift.replace("\"quantity\":1", "\"quantity\":0"), gift.replace("\"quantity\":1", "\"quantity\":11"),
        gift.replace("\"revision\":0", "\"revision\":-1")}) {
      mvc.perform(post("/api/survive/v1/gifts").with(csrf()).contentType("application/json").content(body))
          .andExpect(status().isBadRequest());
    }
  }

  @Test
  void anonymousJoinNeedsCsrfAndUsesPrivateCookieWithoutExposingToken() throws Exception {
    mvc.perform(get("/api/survive/v1/game"))
        .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(post("/api/survive/v1/game").contentType("application/json")
        .content("{\"name\":\"Chris\"}"))
        .andExpect(status().isForbidden());
    var joined = mvc.perform(post("/api/survive/v1/game").with(csrf()).secure(true)
        .contentType("application/json").content("{\"name\":\"Chris\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().string("Set-Cookie", allOf(containsString("HttpOnly"),
            containsString("Secure"), containsString("SameSite=Strict"), containsString("Path=/api/survive/v1"))))
        .andExpect(jsonPath("$.health").value(10))
        .andExpect(jsonPath("$.token").doesNotExist()).andReturn();
    var cookie = joined.getResponse().getCookie(SurviveController.COOKIE_NAME);
    mvc.perform(get("/api/survive/v1/game").cookie(cookie))
        .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Chris"));
    mvc.perform(post("/api/survive/v1/actions").cookie(cookie).with(csrf())
        .contentType("application/json").content("{\"action\":\"GATHER\",\"revision\":0}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
    mvc.perform(post("/api/survive/v1/actions").cookie(cookie).with(csrf())
        .contentType("application/json").content("{\"action\":\"GATHER\",\"revision\":0}"))
        .andExpect(status().isConflict());
  }

  @Test
  void malformedAndUnavailableActionsCannotChangeState() throws Exception {
    for (var body : new String[] {"{}", "{\"action\":\"CHEAT\",\"revision\":0}",
        "{\"action\":\"GATHER\",\"revision\":-1}", "{\"action\":\"GATHER\"}"}) {
      mvc.perform(post("/api/survive/v1/actions").with(csrf())
          .contentType("application/json").content(body)).andExpect(status().isBadRequest());
    }
    mvc.perform(post("/api/survive/v1/actions").with(csrf())
        .cookie(new Cookie(SurviveController.COOKIE_NAME, "invented"))
        .contentType("application/json").content("{\"action\":\"GATHER\",\"revision\":0}"))
        .andExpect(status().isNotFound());
    mvc.perform(post("/api/survive/v1/game").with(csrf())
        .contentType("application/json").content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest());
  }
}
