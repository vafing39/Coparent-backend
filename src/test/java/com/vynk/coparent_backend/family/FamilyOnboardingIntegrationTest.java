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

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Onboarding steps 2 to 5 against the local MySQL database. Test accounts use the reserved {@code .invalid}
 * domain; their families are deleted with them (created_by is SET NULL, so families are deleted explicitly).
 */
@SpringBootTest(properties = {
        "app.auth.check-compromised-passwords=false",
        "app.auth.jwt-secret=test-only-secret-that-is-at-least-32-bytes-long"
})
@AutoConfigureMockMvc
class FamilyOnboardingIntegrationTest {

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
    void fullOnboardingThenInvitationAccepted() throws Exception {
        String camille = verifiedUser("Camille");
        String julien = verifiedUser("Julien");

        // Step 2
        String created = call(camille, HttpMethod.POST, "/api/v1/families", FAMILY)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.myRole").value("PARENT_A"))
                .andExpect(jsonPath("$.solo").value(true))
                .andReturn().getResponse().getContentAsString();
        String familyId = JsonPath.read(created, "$.id");
        call(camille, HttpMethod.GET, "/api/v1/families/me", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vacationZone").value("A"));
        call(camille, HttpMethod.PUT, "/api/v1/families/" + familyId,
                "{\"name\":\"Famille Martin\",\"vacationZone\":\"C\"}")
                .andExpect(jsonPath("$.name").value("Famille Martin"))
                .andExpect(jsonPath("$.vacationZone").value("C"));

        // Step 3
        LocalDate leaBirth = LocalDate.now().minusYears(8).minusDays(3);
        call(camille, HttpMethod.POST, "/api/v1/families/" + familyId + "/children",
                "{\"firstName\":\"Léa\",\"birthDate\":\"" + leaBirth + "\"}")
                .andExpect(status().isCreated());
        String hugo = call(camille, HttpMethod.POST, "/api/v1/families/" + familyId + "/children",
                "{\"firstName\":\"Hugo\",\"birthDate\":\"2021-08-21\"}")
                .andReturn().getResponse().getContentAsString();
        String hugoId = JsonPath.read(hugo, "$.id");
        call(camille, HttpMethod.PUT, "/api/v1/families/" + familyId + "/children/" + hugoId,
                "{\"firstName\":\"Hugo\",\"birthDate\":\"2021-08-22\"}")
                .andExpect(jsonPath("$.birthDate").value("2021-08-22"));

        // Step 4: the Paris wall-clock time comes back unchanged
        call(camille, HttpMethod.PUT, "/api/v1/families/" + familyId + "/custody-schedule", SCHEDULE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handoverTime").value("17:50:00"))
                .andExpect(jsonPath("$.startDate").value("2026-11-13"))
                .andExpect(jsonPath("$.status").value("PROPOSED"));
        // ... and is stored as such, not converted to UTC (17:50 would become 16:50)
        String stored = jdbc.queryForObject(
                "SELECT TIME_FORMAT(handover_time, '%H:%i:%s') FROM custody_schedules WHERE family_id = UUID_TO_BIN(?)",
                String.class, familyId);
        assertThat(stored).isEqualTo("17:50:00");

        // Step 5
        String invitation = call(camille, HttpMethod.POST, "/api/v1/families/" + familyId + "/invitations",
                "{\"inviteeFirstName\":\"Julien\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = tokenOf(invitation);

        // Public preview: first names and ages, no family name, no birth dates
        mvc.perform(get("/api/v1/invitations/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inviterFirstName").value("Camille"))
                .andExpect(jsonPath("$.children[0].firstName").value("Léa")) // oldest first
                .andExpect(jsonPath("$.children[0].age").value(8))
                .andExpect(jsonPath("$.children[1].firstName").value("Hugo"))
                .andExpect(jsonPath("$.children[0].birthDate").doesNotExist())
                .andExpect(jsonPath("$.name").doesNotExist())
                .andExpect(jsonPath("$.custodySchedule.pattern").value("ALTERNATE_WEEKS"));

        call(julien, HttpMethod.POST, "/api/v1/invitations/" + token + "/accept", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myRole").value("PARENT_B"))
                .andExpect(jsonPath("$.solo").value(false))
                .andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.children.length()").value(2))
                .andExpect(jsonPath("$.custodySchedule.status").value("PROPOSED"));

        call(camille, HttpMethod.GET, "/api/v1/families/me", null).andExpect(jsonPath("$.solo").value(false));
        // Single use
        mvc.perform(get("/api/v1/invitations/" + token)).andExpect(status().isNotFound());
    }

    @Test
    void familyRequiresVerifiedEmailAndOnlyOneFamily() throws Exception {
        String unverified = user("Zoé");
        call(unverified, HttpMethod.POST, "/api/v1/families", FAMILY).andExpect(status().isForbidden());
        call(unverified, HttpMethod.GET, "/api/v1/families/me", null).andExpect(status().isNotFound());

        String camille = verifiedUser("Camille");
        call(camille, HttpMethod.POST, "/api/v1/families", FAMILY).andExpect(status().isCreated());
        call(camille, HttpMethod.POST, "/api/v1/families", FAMILY).andExpect(status().isConflict());
        call(camille, HttpMethod.POST, "/api/v1/families", "{\"name\":\" \",\"vacationZone\":\"A\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonMembersGetNotFoundEverywhere() throws Exception {
        String camille = verifiedUser("Camille");
        String stranger = verifiedUser("Inconnu");
        String familyId = familyOf(camille);
        String child = call(camille, HttpMethod.POST, "/api/v1/families/" + familyId + "/children",
                "{\"firstName\":\"Léa\",\"birthDate\":\"2018-03-03\"}").andReturn().getResponse().getContentAsString();
        String childId = JsonPath.read(child, "$.id");
        String base = "/api/v1/families/" + familyId;

        call(stranger, HttpMethod.PUT, base, FAMILY).andExpect(status().isNotFound());
        call(stranger, HttpMethod.POST, base + "/children", "{\"firstName\":\"X\",\"birthDate\":\"2020-01-01\"}")
                .andExpect(status().isNotFound());
        call(stranger, HttpMethod.PUT, base + "/children/" + childId,
                "{\"firstName\":\"X\",\"birthDate\":\"2020-01-01\"}").andExpect(status().isNotFound());
        call(stranger, HttpMethod.DELETE, base + "/children/" + childId, null).andExpect(status().isNotFound());
        call(stranger, HttpMethod.PUT, base + "/custody-schedule", SCHEDULE).andExpect(status().isNotFound());
        call(stranger, HttpMethod.POST, base + "/invitations", "{\"inviteeFirstName\":\"X\"}")
                .andExpect(status().isNotFound());
        // Same answer as a family that does not exist
        call(stranger, HttpMethod.PUT, "/api/v1/families/" + UUID.randomUUID(), FAMILY)
                .andExpect(status().isNotFound());

        // A child of another family cannot be reached through one's own family
        String strangerFamily = familyOf(stranger);
        call(stranger, HttpMethod.DELETE, "/api/v1/families/" + strangerFamily + "/children/" + childId, null)
                .andExpect(status().isNotFound());
        call(camille, HttpMethod.DELETE, base + "/children/" + childId, null).andExpect(status().isNoContent());

        call(stranger, HttpMethod.GET, "/api/v1/families/me", null)
                .andExpect(jsonPath("$.children.length()").value(0));
        mvc.perform(post(base + "/children")).andExpect(status().isUnauthorized());
    }

    @Test
    void customRhythmNeedsASequenceAndAProposalIsReplaced() throws Exception {
        String camille = verifiedUser("Camille");
        String path = "/api/v1/families/" + familyOf(camille) + "/custody-schedule";

        call(camille, HttpMethod.PUT, path, SCHEDULE.replace("ALTERNATE_WEEKS", "CUSTOM"))
                .andExpect(status().isBadRequest());
        call(camille, HttpMethod.PUT, path, SCHEDULE.replace("\"pattern\":\"ALTERNATE_WEEKS\"",
                        "\"pattern\":\"ALTERNATE_WEEKS\",\"customSequence\":\"AB\""))
                .andExpect(status().isBadRequest());
        call(camille, HttpMethod.PUT, path, SCHEDULE.replace("\"pattern\":\"ALTERNATE_WEEKS\"",
                        "\"pattern\":\"CUSTOM\",\"customSequence\":\"AAXB\""))
                .andExpect(status().isBadRequest());

        String first = call(camille, HttpMethod.PUT, path, SCHEDULE).andReturn().getResponse().getContentAsString();
        String second = call(camille, HttpMethod.PUT, path, SCHEDULE.replace("\"pattern\":\"ALTERNATE_WEEKS\"",
                        "\"pattern\":\"CUSTOM\",\"customSequence\":\"AABBAAABBAABBB\""))
                .andExpect(jsonPath("$.pattern").value("CUSTOM"))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(second, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
    }

    @Test
    void invitationRules() throws Exception {
        String camille = verifiedUser("Camille");
        String julien = verifiedUser("Julien");
        String third = verifiedUser("Tiers");
        String familyId = familyOf(camille);
        String invitePath = "/api/v1/families/" + familyId + "/invitations";
        String invite = "{\"inviteeFirstName\":\"Julien\"}";

        String oldToken = tokenOf(call(camille, HttpMethod.POST, invitePath, invite)
                .andReturn().getResponse().getContentAsString());
        String token = tokenOf(call(camille, HttpMethod.POST, invitePath, invite)
                .andReturn().getResponse().getContentAsString());
        mvc.perform(get("/api/v1/invitations/" + oldToken)).andExpect(status().isNotFound()); // renewed

        call(camille, HttpMethod.POST, "/api/v1/invitations/" + token + "/accept", null)
                .andExpect(status().isConflict()); // own invitation
        call(user("Pas vérifié"), HttpMethod.POST, "/api/v1/invitations/" + token + "/accept", null)
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/invitations/" + token + "/accept")).andExpect(status().isUnauthorized());

        call(julien, HttpMethod.POST, "/api/v1/invitations/" + token + "/accept", null).andExpect(status().isOk());
        call(third, HttpMethod.POST, "/api/v1/invitations/" + token + "/accept", null)
                .andExpect(status().isNotFound()); // already used
        call(camille, HttpMethod.POST, invitePath, invite).andExpect(status().isConflict()); // two parents
    }

    // --- helpers ---

    /** Signs up and returns the access token. */
    private String user(String firstName) throws Exception {
        String email = "user-" + UUID.randomUUID() + TEST_DOMAIN;
        String body = mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"confirmPassword\":\""
                                + PASSWORD + "\",\"firstName\":\"" + firstName + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private String verifiedUser(String firstName) throws Exception {
        String token = user(firstName);
        jdbc.update("UPDATE users SET email_verified_at = UTC_TIMESTAMP(6) WHERE email_verified_at IS NULL "
                + "AND email LIKE ?", "%" + TEST_DOMAIN);
        return token;
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

    private static String tokenOf(String invitationJson) {
        String link = JsonPath.read(invitationJson, "$.link");
        return link.substring(link.indexOf("token=") + "token=".length());
    }
}
