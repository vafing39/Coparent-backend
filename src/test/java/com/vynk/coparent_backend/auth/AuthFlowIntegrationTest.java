package com.vynk.coparent_backend.auth;

import com.jayway.jsonpath.JsonPath;
import com.vynk.coparent_backend.auth.email.EmailSender;
import com.vynk.coparent_backend.user.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.vynk.coparent_backend.auth.service.RateLimiter;

/**
 * End-to-end auth flows against the local MySQL database. Test accounts use the reserved
 * {@code .invalid} domain and are deleted after each test; other data in the database is left alone.
 */
@SpringBootTest(properties = {
        "app.auth.check-compromised-passwords=false",
        "app.auth.jwt-secret=test-only-secret-that-is-at-least-32-bytes-long"
})
@AutoConfigureMockMvc
class AuthFlowIntegrationTest {

    private static final String TEST_DOMAIN = "@test.coparent.invalid";
    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RateLimiter rateLimiter;
    @MockitoBean
    private EmailSender emailSender;

    @BeforeEach
    void resetRateLimits() {
        rateLimiter.reset();
    }

    @AfterEach
    void deleteTestUsers() {
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + TEST_DOMAIN);
    }

    @Test
    void signupThenMeThenLogin() throws Exception {
        String email = randomEmail();
        String signup = signup(email, PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.emailVerified").value(false))
                .andReturn().getResponse().getContentAsString();

        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(signup)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Julie"));

        login(email.toUpperCase(), PASSWORD).andExpect(status().isOk()); // email is case-insensitive
        login(email, "wrong-password-123").andExpect(status().isUnauthorized());
        login(randomEmail(), PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void meRequiresAValidAccessToken() throws Exception {
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void signupRejectsDuplicateEmailAndShortPassword() throws Exception {
        String email = randomEmail();
        signup(email, PASSWORD).andExpect(status().isCreated());
        signup(email, PASSWORD).andExpect(status().isConflict());
        signup(randomEmail(), "too-short").andExpect(status().isBadRequest());
        signup("not-an-email", PASSWORD).andExpect(status().isBadRequest());
        signup(randomEmail(), PASSWORD, PASSWORD + "x").andExpect(status().isBadRequest());
    }

    @Test
    void refreshRotatesAndReuseRevokesTheWholeSession() throws Exception {
        String first = signup(randomEmail(), PASSWORD).andReturn().getResponse().getContentAsString();
        String firstRefresh = JsonPath.read(first, "$.refreshToken");

        String second = refresh(firstRefresh).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String secondRefresh = JsonPath.read(second, "$.refreshToken");
        assertThat(secondRefresh).isNotEqualTo(firstRefresh);

        // Replaying the rotated token is treated as theft: it fails, and so does the latest token.
        refresh(firstRefresh).andExpect(status().isUnauthorized());
        refresh(secondRefresh).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheRefreshToken() throws Exception {
        String session = signup(randomEmail(), PASSWORD).andReturn().getResponse().getContentAsString();
        String refreshToken = JsonPath.read(session, "$.refreshToken");

        mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isNoContent());
        refresh(refreshToken).andExpect(status().isUnauthorized());
    }

    @Test
    void emailVerificationLinkVerifiesOnce() throws Exception {
        String session = signup(randomEmail(), PASSWORD).andReturn().getResponse().getContentAsString();
        String token = tokenFromLink(captureVerificationLink());

        postToken("/api/v1/auth/email/verify", token).andExpect(status().isNoContent());
        postToken("/api/v1/auth/email/verify", token).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(session)))
                .andExpect(jsonPath("$.emailVerified").value(true));
    }

    @Test
    void forgotPasswordAnswersTheSameForUnknownEmails() throws Exception {
        forgot(randomEmail()).andExpect(status().isAccepted());
        verify(emailSender, never()).sendPasswordReset(any(), any());
    }

    @Test
    void resetPasswordChangesPasswordAndSignsOutEverySession() throws Exception {
        String email = randomEmail();
        String session = signup(email, PASSWORD).andReturn().getResponse().getContentAsString();
        String oldRefresh = JsonPath.read(session, "$.refreshToken");

        forgot(email).andExpect(status().isAccepted());
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendPasswordReset(any(User.class), link.capture());
        String newPassword = "another-long-password";

        String resetToken = tokenFromLink(link.getValue());

        resetPassword(resetToken, newPassword, "different-long-password").andExpect(status().isBadRequest());
        resetPassword(resetToken, newPassword, newPassword).andExpect(status().isNoContent());

        refresh(oldRefresh).andExpect(status().isUnauthorized());
        login(email, PASSWORD).andExpect(status().isUnauthorized());
        login(email, newPassword).andExpect(status().isOk());
    }

    @Test
    void loginIsRateLimited() throws Exception {
        String email = randomEmail();
        for (int i = 0; i < 10; i++) {
            login(email, "wrong-password-123").andExpect(status().isUnauthorized());
        }
        login(email, "wrong-password-123").andExpect(status().isTooManyRequests());
    }

    // --- helpers ---

    private ResultActions signup(String email, String password) throws Exception {
        return signup(email, password, password);
    }

    private ResultActions signup(String email, String password, String confirmation) throws Exception {
        return mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password
                        + "\",\"confirmPassword\":\"" + confirmation + "\",\"firstName\":\"Julie\"}"));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"));
    }

    private ResultActions resetPassword(String token, String newPassword, String confirmation) throws Exception {
        return mvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword
                        + "\",\"confirmPassword\":\"" + confirmation + "\"}"));
    }

    private ResultActions forgot(String email) throws Exception {
        return mvc.perform(post("/api/v1/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"));
    }

    private ResultActions postToken(String path, String token) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"));
    }

    private String captureVerificationLink() {
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(emailSender, atLeastOnce()).sendEmailVerification(any(User.class), link.capture());
        return link.getValue();
    }

    private static String tokenFromLink(String link) {
        return link.substring(link.indexOf("token=") + "token=".length());
    }

    private static String bearer(String authResponseJson) {
        return "Bearer " + JsonPath.read(authResponseJson, "$.accessToken");
    }

    private static String randomEmail() {
        return "user-" + UUID.randomUUID() + TEST_DOMAIN;
    }
}
