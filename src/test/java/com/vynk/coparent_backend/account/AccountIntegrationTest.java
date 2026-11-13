package com.vynk.coparent_backend.account;

import com.jayway.jsonpath.JsonPath;
import com.vynk.coparent_backend.account.service.AccountPurgeJob;
import com.vynk.coparent_backend.auth.email.EmailSender;
import com.vynk.coparent_backend.auth.service.RateLimiter;
import com.vynk.coparent_backend.user.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** « Mon compte » against the local MySQL database: password change, data export, deletion and purge. */
@SpringBootTest(properties = {
        "app.auth.check-compromised-passwords=false",
        "app.auth.jwt-secret=test-only-secret-that-is-at-least-32-bytes-long"
})
@AutoConfigureMockMvc
class AccountIntegrationTest {

    private static final String TEST_DOMAIN = "@test.coparent.invalid";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String NEW_PASSWORD = "another-long-passphrase";
    private static final String FAMILY = "{\"name\":\"Famille de Léa\",\"vacationZone\":\"A\"}";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RateLimiter rateLimiter;
    @Autowired
    private AccountPurgeJob purgeJob;
    @MockitoBean
    private EmailSender emailSender;

    /** Ids of the users created here: once purged, their email no longer ends with the test domain. */
    private final List<String> userIds = new ArrayList<>();

    @BeforeEach
    void resetRateLimits() {
        rateLimiter.reset();
    }

    @AfterEach
    void deleteTestData() {
        for (String id : userIds) {
            jdbc.update("DELETE f FROM families f JOIN family_members m ON m.family_id = f.id "
                    + "WHERE m.user_id = UUID_TO_BIN(?)", id);
        }
        for (String id : userIds) {
            jdbc.update("DELETE FROM users WHERE id = UUID_TO_BIN(?)", id);
        }
    }

    @Test
    void passwordChangeChecksTheCurrentOneAndClosesTheOtherSessions() throws Exception {
        Account camille = verifiedUser("Camille");
        String password = "/api/v1/users/me/password";

        call(camille, HttpMethod.POST, password, change("wrong-password-123", NEW_PASSWORD, NEW_PASSWORD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Mot de passe actuel incorrect."));
        call(camille, HttpMethod.POST, password, change(PASSWORD, "short", "short"))
                .andExpect(status().isBadRequest());
        call(camille, HttpMethod.POST, password, change(PASSWORD, NEW_PASSWORD, "different-passphrase"))
                .andExpect(status().isBadRequest());

        String body = call(camille, HttpMethod.POST, password, change(PASSWORD, NEW_PASSWORD, NEW_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.firstName").value("Camille"))
                .andReturn().getResponse().getContentAsString();
        String newRefresh = JsonPath.read(body, "$.refreshToken");

        // The session opened at signup is closed; the new one works
        refresh(camille.refreshToken()).andExpect(status().isUnauthorized());
        refresh(newRefresh).andExpect(status().isOk());
        login(camille.email(), PASSWORD).andExpect(status().isUnauthorized());
        login(camille.email(), NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void wrongCurrentPasswordsAreLimited() throws Exception {
        Account camille = verifiedUser("Camille");
        for (int i = 0; i < 5; i++) {
            call(camille, HttpMethod.POST, "/api/v1/users/me/password", change("wrong-password-123", NEW_PASSWORD,
                    NEW_PASSWORD)).andExpect(status().isForbidden());
        }
        call(camille, HttpMethod.POST, "/api/v1/users/me/password", change(PASSWORD, NEW_PASSWORD, NEW_PASSWORD))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void dataExportIsEmailedWithoutTheOtherParentsPrivateEvents() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        String familyId = familyOf(camille);
        join(camille, julien, familyId);
        String lea = child(camille, familyId, "Léa");
        String events = "/api/v1/families/" + familyId + "/events";
        call(julien, HttpMethod.POST, events, event("Dentiste", lea, "SHARED")).andExpect(status().isCreated());
        call(julien, HttpMethod.POST, events, event("Cadeau surprise", lea, "PRIVATE"))
                .andExpect(status().isCreated());
        call(camille, HttpMethod.POST, events, event("Réunion", lea, "PRIVATE")).andExpect(status().isCreated());
        call(julien, HttpMethod.POST, "/api/v1/families/" + familyId + "/conversations",
                "{\"subject\":\"Rentrée\",\"body\":\"Bonjour\"}").andExpect(status().isCreated());

        call(camille, HttpMethod.POST, "/api/v1/users/me/data-export", null).andExpect(status().isAccepted());

        ArgumentCaptor<byte[]> json = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> fileName = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendDataExport(any(User.class), fileName.capture(), json.capture());
        assertThat(fileName.getValue()).isEqualTo(
                "coparent-mes-donnees-" + LocalDate.now(ZoneId.of("Europe/Paris")) + ".json");
        String export = new String(json.getValue(), StandardCharsets.UTF_8);
        assertThat((String) JsonPath.read(export, "$.account.email")).isEqualTo(camille.email());
        assertThat((String) JsonPath.read(export, "$.family.name")).isEqualTo("Famille de Léa");
        List<String> titles = JsonPath.read(export, "$.events[*].title");
        assertThat(titles).containsExactlyInAnyOrder("Dentiste", "Réunion");
        assertThat((String) JsonPath.read(export, "$.conversations[0].subject")).isEqualTo("Rentrée");
        assertThat((String) JsonPath.read(export, "$.conversations[0].messages[0].senderFirstName"))
                .isEqualTo("Julien");
        assertThat((String) JsonPath.read(export, "$.conversations[0].messages[0].body")).isEqualTo("Bonjour");

        // Three per day
        call(camille, HttpMethod.POST, "/api/v1/users/me/data-export", null).andExpect(status().isAccepted());
        call(camille, HttpMethod.POST, "/api/v1/users/me/data-export", null).andExpect(status().isAccepted());
        call(camille, HttpMethod.POST, "/api/v1/users/me/data-export", null)
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void deletionDeactivatesAtOnceThenThePurgeAnonymisesAndKeepsTheFamilyForTheOtherParent() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        String familyId = familyOf(camille);
        join(camille, julien, familyId);
        String conversations = "/api/v1/families/" + familyId + "/conversations";
        String conversation = JsonPath.read(call(camille, HttpMethod.POST, conversations,
                        "{\"subject\":\"Rentrée\",\"body\":\"Bonjour Julien\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

        call(camille, HttpMethod.DELETE, "/api/v1/users/me", "{\"password\":\"wrong-password-123\"}")
                .andExpect(status().isForbidden());
        call(camille, HttpMethod.DELETE, "/api/v1/users/me", "{\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isNoContent());

        // Signed out everywhere, no sign-in
        refresh(camille.refreshToken()).andExpect(status().isUnauthorized());
        login(camille.email(), PASSWORD).andExpect(status().isUnauthorized());
        Map<String, Object> row = jdbc.queryForMap("SELECT status, "
                + "TIMESTAMPDIFF(DAY, deactivated_at, deletion_due_at) days FROM users WHERE id = UUID_TO_BIN(?)",
                camille.id());
        assertThat(row.get("status")).isEqualTo("DEACTIVATED");
        assertThat(((Number) row.get("days")).intValue()).isEqualTo(30);

        // Not due yet: nothing purged
        purgeJob.purgeDueAccounts();
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id = UUID_TO_BIN(?)", String.class,
                camille.id())).isEqualTo(camille.email());

        // 30 days later
        jdbc.update("UPDATE users SET deletion_due_at = UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE "
                + "WHERE id = UUID_TO_BIN(?)", camille.id());
        assertThat(purgeJob.purgeDueAccounts()).isGreaterThanOrEqualTo(1);
        Map<String, Object> purged = jdbc.queryForMap("SELECT email, first_name, password_hash, deletion_due_at "
                + "FROM users WHERE id = UUID_TO_BIN(?)", camille.id());
        assertThat(purged.get("email")).isEqualTo("deleted-" + camille.id() + "@deleted.invalid");
        assertThat(purged.get("first_name")).isEqualTo(AccountPurgeJob.DELETED_NAME);
        assertThat(purged.get("password_hash")).isNull();
        assertThat(purged.get("deletion_due_at")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = UUID_TO_BIN(?)",
                Integer.class, camille.id())).isZero();

        // Julien keeps the family and the messages
        call(julien, "/api/v1/families/me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[*].firstName", hasItem(AccountPurgeJob.DELETED_NAME)));
        call(julien, conversations + "/" + conversation + "/messages")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].body").value("Bonjour Julien"));
    }

    @Test
    void aFamilyWithoutAnyActiveParentIsDeletedByThePurge() throws Exception {
        Account camille = verifiedUser("Camille");
        String familyId = familyOf(camille);
        call(camille, HttpMethod.DELETE, "/api/v1/users/me", "{\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isNoContent());
        jdbc.update("UPDATE users SET deletion_due_at = UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE "
                + "WHERE id = UUID_TO_BIN(?)", camille.id());

        purgeJob.purgeDueAccounts();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM families WHERE id = UUID_TO_BIN(?)", Integer.class,
                familyId)).isZero();
        verify(emailSender, never()).sendDataExport(any(), anyString(), any());
    }

    // --- helpers ---

    private static String change(String current, String next, String confirmation) {
        return "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\",\"confirmPassword\":\""
                + confirmation + "\"}";
    }

    private static String event(String title, String childId, String visibility) {
        return "{\"title\":\"" + title + "\",\"type\":\"APPOINTMENT\",\"childIds\":[\"" + childId + "\"],"
                + "\"date\":\"" + LocalDate.now().plusDays(3) + "\",\"allDay\":true,\"startTime\":null,"
                + "\"endTime\":null,\"location\":null,\"reminder\":\"NONE\",\"visibility\":\"" + visibility + "\"}";
    }

    private record Account(String id, String email, String accessToken, String refreshToken) {
    }

    private Account verifiedUser(String firstName) throws Exception {
        String email = "user-" + UUID.randomUUID() + TEST_DOMAIN;
        String body = mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"confirmPassword\":\""
                                + PASSWORD + "\",\"firstName\":\"" + firstName + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        jdbc.update("UPDATE users SET email_verified_at = UTC_TIMESTAMP(6) WHERE email = ?", email);
        String id = JsonPath.read(body, "$.user.id");
        userIds.add(id);
        return new Account(id, email, JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.refreshToken"));
    }

    private String familyOf(Account account) throws Exception {
        return JsonPath.read(call(account, HttpMethod.POST, "/api/v1/families", FAMILY)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String child(Account account, String familyId, String firstName) throws Exception {
        return JsonPath.read(call(account, HttpMethod.POST, "/api/v1/families/" + familyId + "/children",
                "{\"firstName\":\"" + firstName + "\",\"birthDate\":\"2019-03-03\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    /** The other parent joins through an invitation. */
    private void join(Account inviter, Account invitee, String familyId) throws Exception {
        String invitation = call(inviter, HttpMethod.POST, "/api/v1/families/" + familyId + "/invitations",
                "{\"inviteeFirstName\":\"Julien\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String link = JsonPath.read(invitation, "$.link");
        String token = link.substring(link.indexOf("token=") + "token=".length());
        call(invitee, HttpMethod.POST, "/api/v1/invitations/" + token + "/accept", null).andExpect(status().isOk());
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private ResultActions call(Account account, String path) throws Exception {
        return call(account, HttpMethod.GET, path, null);
    }

    private ResultActions call(Account account, HttpMethod method, String path, String json) throws Exception {
        var builder = request(method, path).header("Authorization", "Bearer " + account.accessToken());
        if (json != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(builder);
    }
}
