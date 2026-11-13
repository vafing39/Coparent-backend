package com.vynk.coparent_backend.event;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /families/{id}/events against the local MySQL database: visibility, period, Paris time, validation. */
@SpringBootTest(properties = {
        "app.auth.check-compromised-passwords=false",
        "app.auth.jwt-secret=test-only-secret-that-is-at-least-32-bytes-long"
})
@AutoConfigureMockMvc
class EventIntegrationTest {

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
    void sharedEventsAreSeenAndEditedByBothParentsPrivateOnesByTheirAuthorOnly() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        String familyId = familyOf(camille);
        String hugo = child(camille, familyId, "Hugo");
        join(camille, julien, familyId);
        String events = "/api/v1/families/" + familyId + "/events";

        String shared = id(call(camille, HttpMethod.POST, events, event("Orthophoniste", hugo, "2026-11-19",
                "\"17:00\"", "\"17:45\"", false, "SHARED"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Orthophoniste"))
                .andExpect(jsonPath("$.date").value("2026-11-19"))
                .andExpect(jsonPath("$.startTime").value("17:00:00"))
                .andExpect(jsonPath("$.endTime").value("17:45:00"))
                .andExpect(jsonPath("$.children[0].firstName").value("Hugo"))
                .andExpect(jsonPath("$.location").value("Cabinet"))
                .andExpect(jsonPath("$.reminder").value("DAY_BEFORE"))
                .andExpect(jsonPath("$.authorFirstName").value("Camille"))
                .andExpect(jsonPath("$.mine").value(true)));
        String secret = id(call(camille, HttpMethod.POST, events, event("Rendez-vous personnel", hugo,
                "2026-11-24", "null", "null", true, "PRIVATE"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allDay").value(true))
                .andExpect(jsonPath("$.startTime").doesNotExist()));
        // Stored as UTC instants: 17:00 in Paris in November is 16:00 UTC; all day = Paris midnight to midnight
        assertStored(shared, "2026-11-19 16:00:00", "2026-11-19 16:45:00");
        assertStored(secret, "2026-11-23 23:00:00", "2026-11-24 23:00:00");

        String november = events + "?from=2026-11-01&to=2026-11-30";
        call(camille, november).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(shared))
                .andExpect(jsonPath("$[1].id").value(secret));
        // The other parent never sees the private event, not even by its id
        call(julien, november)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(shared))
                .andExpect(jsonPath("$[0].mine").value(false));
        call(julien, events + "/" + secret).andExpect(status().isNotFound());
        call(julien, HttpMethod.PUT, events + "/" + secret, event("x", hugo, "2026-11-24", "null", "null", true,
                "SHARED")).andExpect(status().isNotFound());
        call(julien, HttpMethod.DELETE, events + "/" + secret, null).andExpect(status().isNotFound());

        // Both parents edit a shared event
        call(julien, HttpMethod.PUT, events + "/" + shared, event("Orthophoniste (décalé)", hugo, "2026-11-20",
                "\"18:00\"", "\"18:30\"", false, "SHARED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Orthophoniste (décalé)"))
                .andExpect(jsonPath("$.authorFirstName").value("Camille"));
        call(camille, events + "/" + shared).andExpect(jsonPath("$.date").value("2026-11-20"));

        // Period: only what touches the days asked
        call(camille, events + "?from=2026-11-21&to=2026-11-23").andExpect(jsonPath("$.length()").value(0));
        call(camille, events + "?from=2026-11-24&to=2026-11-24").andExpect(jsonPath("$.length()").value(1));

        call(julien, HttpMethod.DELETE, events + "/" + shared, null).andExpect(status().isNoContent());
        call(camille, november).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void parisTimeAcrossTheDaylightSavingChange() throws Exception {
        Account camille = verifiedUser("Camille");
        String familyId = familyOf(camille);
        String hugo = child(camille, familyId, "Hugo");
        String events = "/api/v1/families/" + familyId + "/events";

        // 29 March 2026: clocks go forward at 2 am, 10 am in Paris is 8 am UTC
        String id = id(call(camille, HttpMethod.POST, events, event("Judo", hugo, "2026-03-29", "\"10:00\"",
                "\"11:00\"", false, "SHARED")).andExpect(status().isCreated()));
        assertStored(id, "2026-03-29 08:00:00", "2026-03-29 09:00:00");
        call(camille, events + "?from=2026-03-29&to=2026-03-29")
                .andExpect(jsonPath("$[0].startTime").value("10:00:00"))
                .andExpect(jsonPath("$[0].date").value("2026-03-29"));
    }

    @Test
    void errors() throws Exception {
        Account camille = verifiedUser("Camille");
        Account other = verifiedUser("Inconnu");
        String familyId = familyOf(camille);
        String hugo = child(camille, familyId, "Hugo");
        String otherChild = child(other, familyOf(other), "Zoé");
        String events = "/api/v1/families/" + familyId + "/events";

        call(camille, HttpMethod.POST, events, event(" ", hugo, "2026-11-19", "\"17:00\"", "\"18:00\"", false,
                "SHARED")).andExpect(status().isBadRequest());
        call(camille, HttpMethod.POST, events, event("x".repeat(101), hugo, "2026-11-19", "\"17:00\"",
                "\"18:00\"", false, "SHARED")).andExpect(status().isBadRequest());
        call(camille, HttpMethod.POST, events, "{\"title\":\"Judo\",\"type\":\"ACTIVITY\",\"childIds\":[],"
                + "\"date\":\"2026-11-19\",\"allDay\":true,\"reminder\":\"NONE\",\"visibility\":\"SHARED\"}")
                .andExpect(status().isBadRequest());
        call(camille, HttpMethod.POST, events, event("Judo", otherChild, "2026-11-19", "\"17:00\"", "\"18:00\"",
                false, "SHARED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Enfant introuvable dans cette famille."));
        call(camille, HttpMethod.POST, events, event("Judo", hugo, "2026-11-19", "\"18:00\"", "\"17:00\"", false,
                "SHARED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("L'heure de fin doit suivre l'heure de début."));
        call(camille, HttpMethod.POST, events, event("Judo", hugo, "2026-11-19", "null", "null", false, "SHARED"))
                .andExpect(status().isBadRequest());
        call(camille, events + "?from=2026-01-01&to=2026-12-31").andExpect(status().isBadRequest());
        call(camille, events + "/" + UUID.randomUUID()).andExpect(status().isNotFound());

        call(other, events + "?from=2026-11-01&to=2026-11-30").andExpect(status().isNotFound());
        mvc.perform(get(events + "?from=2026-11-01&to=2026-11-30")).andExpect(status().isUnauthorized());
    }

    private static String event(String title, String childId, String date, String startTime, String endTime,
                                boolean allDay, String visibility) {
        return "{\"title\":\"" + title + "\",\"type\":\"APPOINTMENT\",\"childIds\":[\"" + childId + "\"],"
                + "\"date\":\"" + date + "\",\"allDay\":" + allDay + ",\"startTime\":" + startTime
                + ",\"endTime\":" + endTime + ",\"location\":\"Cabinet\",\"reminder\":\"DAY_BEFORE\","
                + "\"visibility\":\"" + visibility + "\"}";
    }

    private void assertStored(String eventId, String startsAt, String endsAt) {
        var row = jdbc.queryForMap("SELECT DATE_FORMAT(starts_at, '%Y-%m-%d %H:%i:%s') s, "
                + "DATE_FORMAT(ends_at, '%Y-%m-%d %H:%i:%s') e FROM events WHERE id = UUID_TO_BIN(?)", eventId);
        org.assertj.core.api.Assertions.assertThat(row.get("s")).isEqualTo(startsAt);
        org.assertj.core.api.Assertions.assertThat(row.get("e")).isEqualTo(endsAt);
    }

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String child(Account account, String familyId, String firstName) throws Exception {
        return id(call(account, HttpMethod.POST, "/api/v1/families/" + familyId + "/children",
                "{\"firstName\":\"" + firstName + "\",\"birthDate\":\"2019-03-03\"}")
                .andExpect(status().isCreated()));
    }

    // --- helpers ---

    private record Account(String id, String accessToken) {
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
