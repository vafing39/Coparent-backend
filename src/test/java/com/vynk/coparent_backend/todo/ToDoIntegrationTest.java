package com.vynk.coparent_backend.todo;

import com.jayway.jsonpath.JsonPath;
import com.vynk.coparent_backend.auth.email.EmailSender;
import com.vynk.coparent_backend.auth.service.RateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /families/{id}/to-do against the local MySQL database. Items are inserted directly: no feature creates them yet. */
@SpringBootTest(properties = {
        "app.auth.check-compromised-passwords=false",
        "app.auth.jwt-secret=test-only-secret-that-is-at-least-32-bytes-long"
})
@AutoConfigureMockMvc
class ToDoIntegrationTest {

    private static final String TEST_DOMAIN = "@test.coparent.invalid";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String FAMILY = "{\"name\":\"Famille de Léa et Hugo\",\"vacationZone\":\"A\"}";

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
    void deleteTestData() {
        jdbc.update("DELETE f FROM families f JOIN family_members m ON m.family_id = f.id "
                + "JOIN users u ON u.id = m.user_id WHERE u.email LIKE ?", "%" + TEST_DOMAIN);
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + TEST_DOMAIN);
    }

    @Test
    void eachParentSeesWhatAwaitsThemAndWhatAwaitsTheOther() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        String familyId = familyOf(camille);
        join(camille, julien, familyId);
        String path = "/api/v1/families/" + familyId + "/to-do";

        call(camille, path).andExpect(status().isOk())
                .andExpect(jsonPath("$.mine.length()").value(0))
                .andExpect(jsonPath("$.theirs.length()").value(0));

        item(familyId, "EXPENSE", "Cantine de novembre · 64,20 €", julien, camille, "2026-11-10 09:00:00", null);
        item(familyId, "EXCHANGE", "Échange du week-end 21-22 nov.", julien, camille, "2026-11-09 09:00:00", null);
        item(familyId, "DEVICE", "Tablette de Hugo", camille, julien, "2026-11-08 09:00:00", null);
        item(familyId, "PENSION", "Pension d'octobre", julien, camille, "2026-10-05 09:00:00", "2026-10-06 09:00:00");

        // Oldest first; answered requests are gone
        call(camille, path)
                .andExpect(jsonPath("$.mine.length()").value(2))
                .andExpect(jsonPath("$.mine[0].kind").value("EXCHANGE"))
                .andExpect(jsonPath("$.mine[0].subject").value("Échange du week-end 21-22 nov."))
                .andExpect(jsonPath("$.mine[0].requestedBy").value("Julien"))
                .andExpect(jsonPath("$.mine[0].assignee").value("Camille"))
                .andExpect(jsonPath("$.mine[0].requestedAt").value("2026-11-09T09:00:00Z"))
                .andExpect(jsonPath("$.mine[0].lastNudgedAt").value(nullValue()))
                .andExpect(jsonPath("$.mine[1].kind").value("EXPENSE"))
                .andExpect(jsonPath("$.theirs.length()").value(1))
                .andExpect(jsonPath("$.theirs[0].kind").value("DEVICE"))
                .andExpect(jsonPath("$.theirs[0].assignee").value("Julien"));

        // The same items, seen from the other side
        call(julien, path)
                .andExpect(jsonPath("$.mine.length()").value(1))
                .andExpect(jsonPath("$.mine[0].kind").value("DEVICE"))
                .andExpect(jsonPath("$.mine[0].requestedBy").value("Camille"))
                .andExpect(jsonPath("$.theirs.length()").value(2));
    }

    @Test
    void errors() throws Exception {
        Account camille = verifiedUser("Camille");
        Account stranger = verifiedUser("Inconnu");
        String path = "/api/v1/families/" + familyOf(camille) + "/to-do";

        call(stranger, path).andExpect(status().isNotFound());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @Test
    void nudgeTheOtherParentOncePerDay() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        Account stranger = verifiedUser("Inconnu");
        String familyId = familyOf(camille);
        join(camille, julien, familyId);
        String id = item(familyId, "DEVICE", "Tablette de Hugo", camille, julien, "2026-11-08 09:00:00", null);
        String nudge = "/api/v1/families/" + familyId + "/to-do/" + id + "/nudge";

        call(camille, HttpMethod.POST, nudge, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.theirs[0].id").value(id))
                .andExpect(jsonPath("$.theirs[0].lastNudgedAt").value(notNullValue()));
        // Once a day at most
        call(camille, HttpMethod.POST, nudge, null).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").value("Une relance par jour au maximum."));
        // The other parent sees when they were nudged
        call(julien, "/api/v1/families/" + familyId + "/to-do")
                .andExpect(jsonPath("$.mine[0].lastNudgedAt").value(notNullValue()));

        // A new day: possible again
        jdbc.update("UPDATE to_do_items SET last_nudged_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 2 DAY) "
                + "WHERE id = UUID_TO_BIN(?)", id);
        call(camille, HttpMethod.POST, nudge, null).andExpect(status().isOk());

        // Errors: the assignee cannot nudge themselves; outsiders, unknown or answered items → 404
        call(julien, HttpMethod.POST, nudge, null).andExpect(status().isConflict());
        call(stranger, HttpMethod.POST, nudge, null).andExpect(status().isNotFound());
        call(camille, HttpMethod.POST, "/api/v1/families/" + familyId + "/to-do/" + UUID.randomUUID() + "/nudge",
                null).andExpect(status().isNotFound());
        jdbc.update("UPDATE to_do_items SET resolved_at = UTC_TIMESTAMP(6) WHERE id = UUID_TO_BIN(?)", id);
        call(camille, HttpMethod.POST, nudge, null).andExpect(status().isNotFound());
    }

    // --- helpers ---

    private record Account(String id, String accessToken) {
    }

    /** Inserts a request and returns its id. */
    private String item(String familyId, String kind, String subject, Account requestedBy, Account assignee,
                        String requestedAt, String resolvedAt) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO to_do_items (id, family_id, kind, subject, requested_by, assignee_id, requested_at, "
                        + "resolved_at) VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?)",
                id, familyId, kind, subject, requestedBy.id(), assignee.id(), requestedAt, resolvedAt);
        return id;
    }

    private Account verifiedUser(String firstName) throws Exception {
        String email = "user-" + UUID.randomUUID() + TEST_DOMAIN;
        String body = mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"confirmPassword\":\""
                                + PASSWORD + "\",\"firstName\":\"" + firstName + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        jdbc.update("UPDATE users SET email_verified_at = UTC_TIMESTAMP(6) WHERE email = ?", email);
        return new Account(JsonPath.read(body, "$.user.id"), JsonPath.read(body, "$.accessToken"));
    }

    private String familyOf(Account account) throws Exception {
        String body = call(account, HttpMethod.POST, "/api/v1/families", FAMILY)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
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
