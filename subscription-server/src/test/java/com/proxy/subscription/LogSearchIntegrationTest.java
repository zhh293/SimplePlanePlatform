package com.proxy.subscription;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class LogSearchIntegrationTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test void disabledOrUnlistedOperatorsCannotQuery() {
        var disabled = new LogSearchService(new ObjectMapper(), "test-issuer", KEY, "", "sp-log-prod", "", "admin", false, false);
        assertFalse(disabled.permitted("admin"));
        assertThrows(ResponseStatusException.class, () -> disabled.search("admin", null));
        assertThrows(IllegalArgumentException.class, () -> new LogSearchService(new ObjectMapper(), "test-issuer",
                KEY, "http://127.0.0.1:9200", "sp-log-prod", "", "admin", true, false));
        assertThrows(IllegalArgumentException.class, () -> new LogSearchService(new ObjectMapper(), "test-issuer",
                KEY, "http://127.0.0.1:9200", "sp-log-prod", "ApiKey secret", "admin", false, true));
        assertTrue(new LogSearchService(new ObjectMapper(), "test-issuer", KEY,
                "http://127.0.0.1:9200", "sp-log-prod", "", "admin", false, true).permitted("admin"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named="SP_TEST_LOG_ES", matches=".+")
    void elasticsearchCanaryReturnsScopedResultsAndCharts() {
        var service = new LogSearchService(new ObjectMapper(), "test-issuer", KEY,
                System.getenv("SP_TEST_LOG_ES"), System.getenv("SP_TEST_LOG_PREFIX"), "", "admin", true, false);
        long to = System.currentTimeMillis() + 5_000;
        long from = to - 3_600_000L;
        var bob = service.search("admin", query("connection", from, to, List.of("bob"), "example.test", null, null, 50, null));
        assertEquals(1, bob.rows().size());
        assertEquals("SESSION_CONNECT_FAILED", bob.rows().get(0).path("event_kind").asText());
        assertEquals(1, bob.charts().path("dial").path("doc_count").asInt());
        assertEquals(1, bob.charts().path("dial").path("results").path("buckets").path("fail").path("doc_count").asInt());
        assertFalse(bob.charts().path("trend").path("buckets").isEmpty());
        assertEquals("api.example.test", bob.charts().path("dial").path("leaders").path("buckets").get(0).path("key").asText());
        var alice = service.search("admin", query("connection", from, to, List.of("alice"), "example.test", null, null, 1, null));
        assertNotNull(alice.cursor());
        var next = service.search("admin", query("connection", from, to, List.of("alice"), "example.test", null, null, 1, alice.cursor()));
        assertNotEquals(alice.rows().get(0).path("event_kind"), next.rows().get(0).path("event_kind"));
        assertNull(next.cursor());
        assertThrows(ResponseStatusException.class,
                () -> service.search("admin", query("connection", from, to, List.of("bob"), "example.test", null, null, 1, alice.cursor())));
        var all = service.search("admin", query("all", from, to, List.of(), null, "bob-1", null, 50, null));
        assertEquals(2, all.rows().size());
        var message = service.search("admin", query("application", from, to, List.of("bob"), null, null, "connection timeout", 50, null));
        assertEquals("fixture connection timeout", message.rows().get(0).path("message").asText());
    }

    private static LogSearchService.Query query(String dataset, long from, long to, List<String> subjects,
                                                 String target, String session, String text, int limit, String cursor) {
        return new LogSearchService.Query(dataset, from, to, subjects, false, null, null, target, true, null,
                null, null, null, null, null, null, null, null, session, text, "phrase", false, limit, cursor);
    }
}
