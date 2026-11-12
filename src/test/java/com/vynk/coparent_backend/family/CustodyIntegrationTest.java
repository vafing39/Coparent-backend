package com.vynk.coparent_backend.family;

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

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /families/{id}/custody against the local MySQL database (zone A, official dates). */
@SpringBootTest(properties = {
        "app.auth.check-compromised-passwords=false",
        "app.auth.jwt-secret=test-only-secret-that-is-at-least-32-bytes-long"
})
@AutoConfigureMockMvc
class CustodyIntegrationTest {

    private static final String TEST_DOMAIN = "@test.coparent.invalid";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String FAMILY = "{\"name\":\"Famille de Léa et Hugo\",\"vacationZone\":\"A\"}";
    private static final String SCHEDULE = """
            {"pattern":"ALTERNATE_WEEKS","startDate":"2026-11-13","handoverTime":"17:50",
             "startingParent":"PARENT_A","holidayFirstHalfEvenYears":"PARENT_B","summerSplit":"FORTNIGHTS"}""";

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
    void computesNightsHandoversAndHolidaysFromTheProposedSchedule() throws Exception {
        String camille = verifiedUser("Camille");
        String familyId = familyOf(camille);
        call(camille, HttpMethod.PUT, "/api/v1/families/" + familyId + "/custody-schedule", SCHEDULE)
                .andExpect(status().isOk());
        String base = "/api/v1/families/" + familyId + "/custody";

        call(camille, HttpMethod.GET, base + "?from=2026-11-12&to=2026-11-21", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleStatus").value("PROPOSED"))
                .andExpect(jsonPath("$.nights.length()").value(10))
                .andExpect(jsonPath("$.nights[0].date").value("2026-11-12"))
                .andExpect(jsonPath("$.nights[0].parent").value("PARENT_B"))
                .andExpect(jsonPath("$.nights[0].period").value("REGULAR"))
                .andExpect(jsonPath("$.nights[0].holiday").value(nullValue()))
                .andExpect(jsonPath("$.nights[1].parent").value("PARENT_A"))
                .andExpect(jsonPath("$.handovers.length()").value(2))
                .andExpect(jsonPath("$.handovers[0].at").value("2026-11-13T17:50:00"))
                .andExpect(jsonPath("$.handovers[0].from").value("PARENT_B"))
                .andExpect(jsonPath("$.handovers[0].to").value("PARENT_A"))
                .andExpect(jsonPath("$.handovers[1].at").value("2026-11-20T17:50:00"));

        // Noël 2026, zone A : PARENT_B a la 1re moitié (année paire).
        call(camille, HttpMethod.GET, base + "?from=2026-12-18&to=2026-12-18", null)
                .andExpect(jsonPath("$.nights[0].parent").value("PARENT_B"))
                .andExpect(jsonPath("$.nights[0].period").value("SHORT_HOLIDAY"))
                .andExpect(jsonPath("$.nights[0].holiday").value("Vacances de Noël"));

        // Le prochain passage est après maintenant, même hors de la période demandée.
        String body = call(camille, HttpMethod.GET, base + "?from=2020-01-01&to=2020-01-02", null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        LocalDateTime next = LocalDateTime.parse(JsonPath.read(body, "$.nextHandover.at"));
        assertThat(next).isAfter(LocalDateTime.now(ZoneId.of("Europe/Paris")));
    }

    @Test
    void errors() throws Exception {
        String camille = verifiedUser("Camille");
        String stranger = verifiedUser("Inconnu");
        String familyId = familyOf(camille);
        String base = "/api/v1/families/" + familyId + "/custody";

        // Pas encore de rythme
        call(camille, HttpMethod.GET, base + "?from=2026-11-12&to=2026-11-21", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Aucun rythme de garde n'est encore défini."));

        call(camille, HttpMethod.PUT, "/api/v1/families/" + familyId + "/custody-schedule", SCHEDULE)
                .andExpect(status().isOk());
        call(stranger, HttpMethod.GET, base + "?from=2026-11-12&to=2026-11-21", null)
                .andExpect(status().isNotFound());
        call(camille, HttpMethod.GET, base + "?from=2026-11-21&to=2026-11-12", null)
                .andExpect(status().isBadRequest());
        call(camille, HttpMethod.GET, base + "?from=2026-11-01&to=2027-02-01", null)
                .andExpect(status().isBadRequest()); // 93 jours
        call(camille, HttpMethod.GET, base + "?from=2026-11-01&to=2027-01-31", null)
                .andExpect(status().isOk()); // 92 jours
        call(camille, HttpMethod.GET, base + "?from=2026-11-01", null).andExpect(status().isBadRequest());
        call(camille, HttpMethod.GET, base + "?from=12/11/2026&to=2026-11-21", null)
                .andExpect(status().isBadRequest());
        mvc.perform(get(base + "?from=2026-11-12&to=2026-11-21")).andExpect(status().isUnauthorized());
    }

    // --- helpers ---

    private String verifiedUser(String firstName) throws Exception {
        String email = "user-" + UUID.randomUUID() + TEST_DOMAIN;
        String body = mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"confirmPassword\":\""
                                + PASSWORD + "\",\"firstName\":\"" + firstName + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        jdbc.update("UPDATE users SET email_verified_at = UTC_TIMESTAMP(6) WHERE email = ?", email);
        return JsonPath.read(body, "$.accessToken");
    }

    private String familyOf(String accessToken) throws Exception {
        String body = call(accessToken, HttpMethod.POST, "/api/v1/families", FAMILY)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private ResultActions call(String accessToken, HttpMethod method, String path, String json) throws Exception {
        var builder = request(method, path).header("Authorization", "Bearer " + accessToken);
        if (json != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(builder);
    }
}
