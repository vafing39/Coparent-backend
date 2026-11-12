package com.vynk.coparent_backend.bag;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /families/{id}/bag against the local MySQL database: a list shared by both parents, ticks per handover. */
@SpringBootTest(properties = {
        "app.auth.check-compromised-passwords=false",
        "app.auth.jwt-secret=test-only-secret-that-is-at-least-32-bytes-long"
})
@AutoConfigureMockMvc
class BagIntegrationTest {

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
    void bothParentsShareTheListAndTicksBelongToTheNextHandover() throws Exception {
        String camille = verifiedUser("Camille");
        String julien = verifiedUser("Julien");
        String familyId = familyOf(camille);
        String lea = childOf(camille, familyId, "Léa", "2018-03-03");
        String hugo = childOf(camille, familyId, "Hugo", "2020-05-12");
        call(camille, HttpMethod.PUT, "/api/v1/families/" + familyId + "/custody-schedule", SCHEDULE)
                .andExpect(status().isOk());
        join(camille, julien, familyId);
        String bag = "/api/v1/families/" + familyId + "/bag";

        // Empty bag: every child, no item
        call(julien, HttpMethod.GET, bag, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handoverAt").value(notNullValue()))
                .andExpect(jsonPath("$.checkedCount").value(0))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.children.length()").value(2))
                .andExpect(jsonPath("$.children[0].firstName").value("Léa"))
                .andExpect(jsonPath("$.children[0].items.length()").value(0));

        // Camille adds, the label is trimmed
        String body = call(camille, HttpMethod.POST, bag + "/items", item(hugo, "  Kimono "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.children[1].items[0].label").value("Kimono"))
                .andExpect(jsonPath("$.children[1].items[0].checked").value(false))
                .andReturn().getResponse().getContentAsString();
        String kimono = JsonPath.read(body, "$.children[1].items[0].id");
        call(camille, HttpMethod.POST, bag + "/items", item(hugo, "Doudou")).andExpect(status().isCreated());
        call(julien, HttpMethod.POST, bag + "/items", item(lea, "Cahier de liaison"))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.children[1].items[1].label").value("Doudou")); // order of addition

        // Julien ticks, Camille sees who did; ticking twice changes nothing
        call(julien, HttpMethod.PUT, bag + "/items/" + kimono + "/check", null).andExpect(status().isOk());
        call(julien, HttpMethod.PUT, bag + "/items/" + kimono + "/check", null).andExpect(status().isOk());
        call(camille, HttpMethod.GET, bag, null)
                .andExpect(jsonPath("$.checkedCount").value(1))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.children[1].items[0].checked").value(true))
                .andExpect(jsonPath("$.children[1].items[0].checkedBy").value("Julien"))
                .andExpect(jsonPath("$.children[1].items[1].checkedBy").value(nullValue()));

        // Unticking, twice too
        call(camille, HttpMethod.DELETE, bag + "/items/" + kimono + "/check", null)
                .andExpect(jsonPath("$.checkedCount").value(0));
        call(camille, HttpMethod.DELETE, bag + "/items/" + kimono + "/check", null).andExpect(status().isOk());

        // A tick saved for another handover does not count: ticks reset after each handover
        jdbc.update("INSERT INTO bag_checks (item_id, handover_at) VALUES (UUID_TO_BIN(?), '2020-01-03 16:50:00')",
                kimono);
        call(camille, HttpMethod.GET, bag, null)
                .andExpect(jsonPath("$.checkedCount").value(0))
                .andExpect(jsonPath("$.children[1].items[0].checked").value(false));

        // Deleting removes the item and its ticks
        call(julien, HttpMethod.PUT, bag + "/items/" + kimono + "/check", null)
                .andExpect(jsonPath("$.checkedCount").value(1));
        call(camille, HttpMethod.DELETE, bag + "/items/" + kimono, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.checkedCount").value(0));
        Integer ticks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bag_checks WHERE item_id = UUID_TO_BIN(?)", Integer.class, kimono);
        assertThat(ticks).isZero();
        call(camille, HttpMethod.DELETE, bag + "/items/" + kimono, null).andExpect(status().isNotFound());
    }

    @Test
    void errors() throws Exception {
        String camille = verifiedUser("Camille");
        String stranger = verifiedUser("Inconnu");
        String familyId = familyOf(camille);
        String lea = childOf(camille, familyId, "Léa", "2018-03-03");
        String otherFamily = familyOf(stranger);
        String otherChild = childOf(stranger, otherFamily, "Zoé", "2019-01-01");
        String bag = "/api/v1/families/" + familyId + "/bag";

        // No schedule yet: the list works, ticking does not
        String body = call(camille, HttpMethod.POST, bag + "/items", item(lea, "Tenue de sport"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.handoverAt").value(nullValue()))
                .andReturn().getResponse().getContentAsString();
        String itemId = JsonPath.read(body, "$.children[0].items[0].id");
        call(camille, HttpMethod.PUT, bag + "/items/" + itemId + "/check", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Aucun passage de garde n'est prévu."));

        // Label
        call(camille, HttpMethod.POST, bag + "/items", item(lea, "   ")).andExpect(status().isBadRequest());
        call(camille, HttpMethod.POST, bag + "/items", item(lea, "x".repeat(101))).andExpect(status().isBadRequest());
        call(camille, HttpMethod.POST, bag + "/items", "{\"label\":\"Doudou\"}").andExpect(status().isBadRequest());

        // Someone else's child or item, someone else's family
        call(camille, HttpMethod.POST, bag + "/items", item(otherChild, "Doudou")).andExpect(status().isNotFound());
        call(stranger, HttpMethod.GET, bag, null).andExpect(status().isNotFound());
        call(stranger, HttpMethod.DELETE, bag + "/items/" + itemId, null).andExpect(status().isNotFound());
        call(stranger, HttpMethod.DELETE, "/api/v1/families/" + otherFamily + "/bag/items/" + itemId, null)
                .andExpect(status().isNotFound());
        call(stranger, HttpMethod.PUT, "/api/v1/families/" + otherFamily + "/bag/items/" + itemId + "/check", null)
                .andExpect(status().isNotFound());
        mvc.perform(get(bag)).andExpect(status().isUnauthorized());
    }

    // --- helpers ---

    private static String item(String childId, String label) {
        return "{\"childId\":\"" + childId + "\",\"label\":\"" + label + "\"}";
    }

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

    private String childOf(String accessToken, String familyId, String firstName, String birthDate) throws Exception {
        String body = call(accessToken, HttpMethod.POST, "/api/v1/families/" + familyId + "/children",
                "{\"firstName\":\"" + firstName + "\",\"birthDate\":\"" + birthDate + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /** The other parent joins through an invitation. */
    private void join(String inviter, String invitee, String familyId) throws Exception {
        String invitation = call(inviter, HttpMethod.POST, "/api/v1/families/" + familyId + "/invitations",
                "{\"inviteeFirstName\":\"Julien\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String link = JsonPath.read(invitation, "$.link");
        String token = link.substring(link.indexOf("token=") + "token=".length());
        call(invitee, HttpMethod.POST, "/api/v1/invitations/" + token + "/accept", null)
                .andExpect(status().isOk());
    }

    private ResultActions call(String accessToken, HttpMethod method, String path, String json) throws Exception {
        var builder = request(method, path).header("Authorization", "Bearer " + accessToken);
        if (json != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(builder);
    }
}
