package com.mafe.incidentai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "n8n.webhook.assistance-url=http://127.0.0.1:1/webhook/analyze-incident",
        "n8n.webhook.connect-timeout-ms=500",
        "spring.jpa.show-sql=false",
        "spring.datasource.url=jdbc:h2:mem:abnormal_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
})
@WithMockUser(username = "sarah.jenkins@enterprise.io", roles = "ADMIN")
class AbnormalIncreaseTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private LocalDateTime currentEnd;
    private LocalDateTime currentStart;
    private LocalDateTime previousStart;

    @BeforeEach
    void clearIncidents() {
        jdbcTemplate.update("DELETE FROM ratings");
        jdbcTemplate.update("DELETE FROM response_sources");
        jdbcTemplate.update("DELETE FROM ai_responses");
        jdbcTemplate.update("DELETE FROM ai_queries");
        jdbcTemplate.update("DELETE FROM incidents");

        currentEnd = LocalDate.now().atStartOfDay();
        currentStart = currentEnd.minusDays(7);
        previousStart = currentStart.minusDays(7);
    }

    private void insertIncident(long categoryId, LocalDateTime createdAt) {
        Timestamp ts = Timestamp.valueOf(createdAt);
        jdbcTemplate.update(
                "INSERT INTO incidents (title, description, priority, status, user_id, category_id, created_at, updated_at) " +
                        "VALUES (?, ?, 'HIGH', 'OPEN', 5, ?, ?, ?)",
                "Incident test category " + categoryId,
                "Detailed incident description for automated period verification",
                categoryId,
                ts,
                ts
        );
    }

    private Map<Long, JsonNode> fetchAbnormalIncreasesByCategory() throws Exception {
        String body = mvc.perform(get("/api/v1/analytics/abnormal-increases"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode array = mapper.readTree(body);
        Map<Long, JsonNode> byId = new HashMap<>();
        for (JsonNode item : array) {
            byId.put(item.get("categoryId").asLong(), item);
        }
        return byId;
    }

    @Test
    void detectsCategoryWithIncreaseAboveFiftyPercent() throws Exception {
        insertIncident(1L, previousStart.plusDays(1));
        insertIncident(1L, previousStart.plusDays(3));

        insertIncident(1L, currentStart.plusDays(1));
        insertIncident(1L, currentStart.plusDays(2));
        insertIncident(1L, currentStart.plusDays(3));
        insertIncident(1L, currentStart.plusDays(4));
        insertIncident(1L, currentStart.plusDays(5));

        insertIncident(5L, previousStart.plusDays(2));
        insertIncident(5L, previousStart.plusDays(4));
        insertIncident(5L, currentStart.plusDays(1));
        insertIncident(5L, currentStart.plusDays(2));
        insertIncident(5L, currentStart.plusDays(3));

        Map<Long, JsonNode> results = fetchAbnormalIncreasesByCategory();

        JsonNode cat1 = results.get(1L);
        assertThat(cat1.get("currentPeriodCount").asLong()).isEqualTo(5L);
        assertThat(cat1.get("previousPeriodCount").asLong()).isEqualTo(2L);
        assertThat(cat1.get("variationPercentage").asDouble()).isEqualTo(150.0);
        assertThat(cat1.get("alert").asBoolean()).isTrue();

        JsonNode cat5 = results.get(5L);
        assertThat(cat5.get("currentPeriodCount").asLong()).isEqualTo(3L);
        assertThat(cat5.get("previousPeriodCount").asLong()).isEqualTo(2L);
        assertThat(cat5.get("variationPercentage").asDouble()).isEqualTo(50.0);
        assertThat(cat5.get("alert").asBoolean()).isTrue();
    }

    @Test
    void evaluatesCategoryWithIncidentDecreaseWithoutAlert() throws Exception {
        insertIncident(2L, previousStart.plusDays(1));
        insertIncident(2L, previousStart.plusDays(2));
        insertIncident(2L, previousStart.plusDays(4));
        insertIncident(2L, previousStart.plusDays(6));

        insertIncident(2L, currentStart.plusDays(2));

        Map<Long, JsonNode> results = fetchAbnormalIncreasesByCategory();

        JsonNode cat2 = results.get(2L);
        assertThat(cat2.get("currentPeriodCount").asLong()).isEqualTo(1L);
        assertThat(cat2.get("previousPeriodCount").asLong()).isEqualTo(4L);
        assertThat(cat2.get("variationPercentage").asDouble()).isEqualTo(-75.0);
        assertThat(cat2.get("alert").asBoolean()).isFalse();
    }

    @Test
    void handlesCategoryWithZeroIncidentsInPreviousPeriodWithoutDivisionByZero() throws Exception {
        insertIncident(3L, currentStart.plusDays(1));
        insertIncident(3L, currentStart.plusDays(4));

        Map<Long, JsonNode> results = fetchAbnormalIncreasesByCategory();

        JsonNode cat3 = results.get(3L);
        assertThat(cat3.get("currentPeriodCount").asLong()).isEqualTo(2L);
        assertThat(cat3.get("previousPeriodCount").asLong()).isEqualTo(0L);
        assertThat(cat3.get("variationPercentage").asDouble()).isEqualTo(100.0);
        assertThat(cat3.get("alert").asBoolean()).isTrue();

        JsonNode cat4 = results.get(4L);
        assertThat(cat4.get("currentPeriodCount").asLong()).isEqualTo(0L);
        assertThat(cat4.get("previousPeriodCount").asLong()).isEqualTo(0L);
        assertThat(cat4.get("variationPercentage").asDouble()).isEqualTo(0.0);
        assertThat(cat4.get("alert").asBoolean()).isFalse();
    }

    @Test
    void endpointResultsMatchDirectSqlQueriesAndExcludeIncompleteCurrentDay() throws Exception {
        insertIncident(1L, previousStart.minusDays(1));
        insertIncident(1L, previousStart.plusHours(12));
        insertIncident(1L, previousStart.plusDays(5));
        insertIncident(1L, currentStart.plusHours(6));
        insertIncident(1L, currentStart.plusDays(2));
        insertIncident(1L, currentStart.plusDays(6).plusHours(23));
        insertIncident(1L, currentEnd.plusMinutes(5));

        insertIncident(2L, previousStart.plusDays(2));
        insertIncident(2L, previousStart.plusDays(3));
        insertIncident(2L, currentStart.plusDays(3));

        insertIncident(6L, currentStart.plusDays(4));

        Map<Long, JsonNode> endpointResults = fetchAbnormalIncreasesByCategory();

        String sql = """
                SELECT c.id,
                       COUNT(CASE WHEN i.created_at >= ? AND i.created_at < ? THEN 1 END) AS current_count,
                       COUNT(CASE WHEN i.created_at >= ? AND i.created_at < ? THEN 1 END) AS previous_count
                FROM categories c
                LEFT JOIN incidents i ON i.category_id = c.id
                GROUP BY c.id
                """;

        jdbcTemplate.query(
                sql,
                rs -> {
                    long categoryId = rs.getLong("id");
                    long sqlCurrent = rs.getLong("current_count");
                    long sqlPrevious = rs.getLong("previous_count");
                    double expectedVariation = sqlPrevious == 0L
                            ? (sqlCurrent > 0L ? 100.0 : 0.0)
                            : Math.round(((sqlCurrent - sqlPrevious) * 100.0 / sqlPrevious) * 100.0) / 100.0;
                    boolean expectedAlert = expectedVariation >= 50.0;

                    JsonNode endpointItem = endpointResults.get(categoryId);
                    assertThat(endpointItem).isNotNull();
                    assertThat(endpointItem.get("currentPeriodCount").asLong()).isEqualTo(sqlCurrent);
                    assertThat(endpointItem.get("previousPeriodCount").asLong()).isEqualTo(sqlPrevious);
                    assertThat(endpointItem.get("variationPercentage").asDouble()).isEqualTo(expectedVariation);
                    assertThat(endpointItem.get("alert").asBoolean()).isEqualTo(expectedAlert);
                },
                Timestamp.valueOf(currentStart),
                Timestamp.valueOf(currentEnd),
                Timestamp.valueOf(previousStart),
                Timestamp.valueOf(currentStart)
        );
    }
}
