package com.proxy.subscription;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.IDN;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
final class LogSearchService {
    record Query(String dataset, Long from, Long to, List<String> subjects, boolean unknown,
                 String node, String device, String target, boolean subdomains, Integer port,
                 String transport, String outcome, String level, String logger, String exceptionType, String trace,
                 String connection, String stream, String session, String text, String mode, boolean relevance,
                 Integer limit, String cursor) {}
    record Result(List<JsonNode> rows, JsonNode charts, String cursor) {}
    private record Search(Map<String, Object> query, List<Map<String, String>> sort, long from, long to, int limit) {}

    private static final String[] OUTPUT_FIELDS = {
            "@timestamp", "dataset", "node_id", "account_id", "device_id", "proxy_session_id", "connection_id",
            "stream_id", "event_kind", "transport", "target_host", "target_port", "uplink_bytes", "downlink_bytes",
            "reason", "level", "logger", "exception_type", "trace_id", "message"
    };
    private static final long HOUR = 3_600_000L;
    private final ObjectMapper json;
    private final HttpClient client;
    private final String origin;
    private final String prefix;
    private final String authorization;
    private final byte[] cursorKey;
    private final String issuer;
    private final Set<String> operators;
    private final boolean preview;
    private final Semaphore searches = new Semaphore(3);

    LogSearchService(ObjectMapper json, @Value("${sso.client.issuer}") String issuer,
                     @Value("${subscription.challenge-key}") String key,
                     @Value("${subscription.logs.url:}") String origin,
                     @Value("${subscription.logs.prefix:sp-log-prod}") String prefix,
                     @Value("${subscription.logs.authorization:}") String authorization,
                     @Value("${subscription.logs.operators:}") String operators,
                     @Value("${subscription.logs.insecure-canary:false}") boolean canary,
                     @Value("${subscription.logs.public-http:false}") boolean publicHttp) {
        this.json = json;
        this.issuer = issuer;
        this.cursorKey = Base64.getDecoder().decode(key);
        this.origin = origin.strip().replaceFirst("/$", "");
        this.prefix = prefix;
        this.authorization = authorization;
        this.preview = canary;
        this.operators = Set.copyOf(Arrays.stream(operators.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList());
        if (!this.origin.isEmpty()) {
            URI uri = URI.create(this.origin);
            boolean allowedCanary = canary && prefix.startsWith("sp-log-canary-") && authorization.isEmpty();
            boolean allowedPublic = publicHttp && !canary && "sp-log-prod".equals(prefix) && authorization.isEmpty() &&
                    uri.getPort() == 9200 && uri.getPath().isEmpty() && "http".equals(uri.getScheme());
            boolean allowedPath = uri.getPath().isEmpty() || !canary && "/_simpleplane/log-query".equals(uri.getPath());
            if ((canary && !allowedCanary) || (publicHttp && !allowedPublic) || !prefix.matches("sp-log-[a-z0-9][a-z0-9-]{0,39}") ||
                    uri.getHost() == null || uri.getUserInfo() != null ||
                    uri.getQuery() != null || uri.getFragment() != null || !allowedPath ||
                    !("https".equals(uri.getScheme()) && (!authorization.isBlank() || allowedCanary)
                            || allowedCanary && "http".equals(uri.getScheme()) || allowedPublic)) {
                throw new IllegalArgumentException("Invalid Elasticsearch log configuration");
            }
        }
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    boolean permitted(String actor) { return !origin.isEmpty() && operators.contains(actor); }

    boolean preview() { return preview; }

    Result search(String actor, Query input) {
        if (!permitted(actor)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        Search plan = plan(input);
        if (!searches.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        String pit = null;
        boolean retain = false;
        try {
            var body = new LinkedHashMap<String, Object>();
            body.put("query", plan.query());
            body.put("sort", plan.sort());
            body.put("size", plan.limit() + 1);
            body.put("_source", OUTPUT_FIELDS);
            body.put("track_total_hits", false);
            body.put("timeout", "2s");
            String stamp = cursorStamp(actor, plan);
            if (input.cursor() == null || input.cursor().isEmpty()) {
                String indices = indices(plan.from(), plan.to());
                JsonNode point = es("POST", "/" + indices + "/_pit?keep_alive=45s&ignore_unavailable=true", null, true);
                if (point == null) return new Result(List.of(), null, null);
                pit = point.path("id").asText();
                if (pit.isEmpty()) return new Result(List.of(), null, null);
                if (!"all".equals(input.dataset())) body.put("aggs", aggregations(input.dataset(), plan));
            } else {
                JsonNode cursor = decode(input.cursor(), stamp);
                pit = cursor.path("pit").asText();
                body.put("search_after", cursor.path("after"));
            }
            body.put("pit", Map.of("id", pit, "keep_alive", "45s"));
            JsonNode response = es("POST", "/_search?allow_partial_search_results=false", body, false);
            pit = response.path("pit_id").asText(pit);
            if (response.path("timed_out").asBoolean()) throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT);
            JsonNode items = response.path("hits").path("hits");
            var rows = new ArrayList<JsonNode>();
            for (int i = 0; i < Math.min(plan.limit(), items.size()); i++) rows.add(items.get(i).path("_source"));
            String next = null;
            if (items.size() > plan.limit()) {
                next = encode(pit, items.get(plan.limit() - 1).path("sort"), stamp);
                retain = true;
            }
            return new Result(rows, response.get("aggregations"), next);
        } catch (IOException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        } finally {
            if (pit != null && !pit.isBlank() && !retain) close(pit);
            searches.release();
        }
    }

    private Search plan(Query input) {
        if (input == null || input.from() == null || input.to() == null || input.from() < 0 ||
                input.to() <= input.from() || input.to() - input.from() > 31 * 24 * HOUR ||
                input.to() > System.currentTimeMillis() + HOUR) throw bad();
        String dataset = choice(input.dataset(), Set.of("connection", "application", "all"));
        if (dataset.isEmpty() || ("all".equals(dataset) && value(input.session(), 512).isEmpty())) throw bad();
        var filters = new ArrayList<Object>();
        var must = new ArrayList<Object>();
        var excluded = new ArrayList<Object>();
        filters.add(Map.of("range", Map.of("@timestamp", Map.of("gte", input.from(), "lt", input.to()))));
        if (!"all".equals(dataset)) term(filters, "dataset", dataset);
        if (input.subjects() != null && !input.subjects().isEmpty()) {
            if (input.subjects().size() > 10 || input.unknown()) throw bad();
            var ids = new ArrayList<String>();
            for (String subject : input.subjects()) {
                String validated = value(subject, 255);
                if (validated.isEmpty()) throw bad();
                ids.add(HexFormat.of().formatHex(DeviceKeys.digest(issuer + "\0" + validated)));
            }
            filters.add(Map.of("terms", Map.of("account_id", ids)));
        }
        if (input.unknown()) excluded.add(Map.of("exists", Map.of("field", "account_id")));
        term(filters, "node_id", value(input.node(), 96));
        term(filters, "device_id", value(input.device(), 36));
        term(filters, "transport", choice(input.transport(), Set.of("http2", "http3")));
        term(filters, "event_kind", choice(input.outcome(), Set.of("SESSION_OPENED", "SESSION_ACTIVE", "SESSION_CONNECT_FAILED", "SESSION_CLOSED")));
        term(filters, "level", choice(input.level(), Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR")));
        term(filters, "logger", value(input.logger(), 512));
        term(filters, "exception_type", value(input.exceptionType(), 512));
        term(filters, "trace_id", value(input.trace(), 128));
        term(filters, "connection_id", value(input.connection(), 512));
        term(filters, "stream_id", value(input.stream(), 80));
        term(filters, "proxy_session_id", value(input.session(), 512));
        String target = value(input.target(), 255).toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
        if (!target.isEmpty()) {
            if (target.startsWith("[") && target.endsWith("]")) target = target.substring(1, target.length() - 1);
            if (target.indexOf(':') >= 0 || target.indexOf('/') >= 0 || target.matches("[0-9]+(?:\\.[0-9]+){3}")) {
                if (!target.matches("[0-9a-f:.]+(?:/[0-9]{1,3})?")) throw bad();
                term(filters, "target_ip", target);
            } else {
                try { target = IDN.toASCII(target, IDN.USE_STD3_ASCII_RULES); }
                catch (IllegalArgumentException invalid) { throw bad(); }
                term(filters, input.subdomains() ? "domain_suffix" : "target_host", target);
            }
        }
        if (input.port() != null) {
            if (input.port() < 1 || input.port() > 65535) throw bad();
            filters.add(Map.of("term", Map.of("target_port", input.port())));
        }
        String text = value(input.text(), 200);
        String mode = choice(input.mode(), Set.of("match", "phrase", "fuzzy", "boolean"));
        if (!text.isEmpty()) {
            if (text.split("\\s+").length > 20) throw bad();
            if (Set.of("fuzzy", "boolean").contains(mode) && input.to() - input.from() > 24 * HOUR) throw bad();
            must.add(switch (mode) {
                case "phrase" -> Map.of("match_phrase", Map.of("message", text));
                case "fuzzy" -> Map.of("match", Map.of("message", Map.of("query", text, "operator", "and",
                        "fuzziness", "AUTO", "prefix_length", 2, "max_expansions", 15)));
                case "boolean" -> Map.of("simple_query_string", Map.of("query", text, "fields", List.of("message"),
                        "default_operator", "and", "flags", "AND|OR|NOT|PHRASE|ESCAPE|PRECEDENCE|WHITESPACE"));
                default -> Map.of("match", Map.of("message", Map.of("query", text, "operator", "and")));
            });
        }
        var sorting = new ArrayList<Map<String, String>>();
        if (input.relevance() && !text.isEmpty()) sorting.add(Map.of("_score", "desc"));
        sorting.add(Map.of("@timestamp", "all".equals(dataset) ? "asc" : "desc"));
        int limit = input.limit() == null ? 50 : input.limit();
        if (limit < 1 || limit > 100) throw bad();
        return new Search(Map.of("bool", Map.of("filter", filters, "must", must, "must_not", excluded)),
                sorting, input.from(), input.to(), limit);
    }

    private Map<String, Object> aggregations(String dataset, Search plan) {
        long interval = plan.to() - plan.from() <= 2 * HOUR ? 60_000 : plan.to() - plan.from() <= 24 * HOUR ? 900_000
                : plan.to() - plan.from() <= 7 * 24 * HOUR ? HOUR : 6 * HOUR;
        var histogram = Map.of("field", "@timestamp", "fixed_interval", interval + "ms", "min_doc_count", 0,
                "extended_bounds", Map.of("min", plan.from(), "max", plan.to() - 1));
        if ("application".equals(dataset)) return Map.of("trend", Map.of("date_histogram", histogram),
                "leaders", terms("template"), "nodes", terms("node_id"));
        var results = Map.of("ok", Map.of("term", Map.of("event_kind", "SESSION_ACTIVE")),
                "fail", Map.of("term", Map.of("event_kind", "SESSION_CONNECT_FAILED")));
        return Map.of("trend", Map.of("date_histogram", histogram, "aggs",
                        Map.of("results", Map.of("filters", Map.of("filters", results)))),
                "dial", Map.of("filter", Map.of("terms", Map.of("event_kind", List.of("SESSION_ACTIVE", "SESSION_CONNECT_FAILED"))),
                        "aggs", Map.of("leaders", terms("target_host"), "nodes", terms("node_id"),
                                "results", Map.of("filters", Map.of("filters", results)))),
                "closed", Map.of("filter", Map.of("term", Map.of("event_kind", "SESSION_CLOSED")),
                        "aggs", Map.of("up", Map.of("sum", Map.of("field", "uplink_bytes")),
                                "down", Map.of("sum", Map.of("field", "downlink_bytes")))));
    }

    private static Map<String, Object> terms(String field) {
        return Map.of("terms", Map.of("field", field, "size", 10));
    }

    private String indices(long from, long to) {
        var names = new ArrayList<String>();
        var day = java.time.Instant.ofEpochMilli(from).atZone(java.time.ZoneOffset.UTC).toLocalDate();
        var last = java.time.Instant.ofEpochMilli(to - 1).atZone(java.time.ZoneOffset.UTC).toLocalDate();
        for (; !day.isAfter(last); day = day.plusDays(1)) names.add(prefix + "-" + day.toString().replace('-', '.'));
        return String.join(",", names);
    }

    private JsonNode es(String method, String path, Object body, boolean allowMissing) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json");
        if (!authorization.isBlank()) request.header("Authorization", authorization);
        var reply = client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() :
                HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build(), HttpResponse.BodyHandlers.ofByteArray());
        if (reply.statusCode() == 404 && allowMissing) return null;
        if (reply.statusCode() == 404) throw new ResponseStatusException(HttpStatus.GONE);
        if (reply.statusCode() == 400) throw bad();
        if (reply.statusCode() >= 300) throw new IOException("Elasticsearch search failed");
        return json.readTree(reply.body());
    }

    private void close(String pit) {
        try { es("DELETE", "/_pit", Map.of("id", pit), true); }
        catch (IOException | ResponseStatusException ignored) { }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private String cursorStamp(String actor, Search plan) throws IOException {
        return actor + "\0" + json.writeValueAsString(plan);
    }

    private String encode(String pit, JsonNode after, String stamp) throws IOException {
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(Map.of(
                "pit", pit, "after", after, "expires", System.currentTimeMillis() + 45_000)));
        return value + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(stamp, value));
    }

    private JsonNode decode(String cursor, String stamp) {
        if (cursor.length() > 16_384) throw bad();
        String[] parts = cursor.split("\\.", -1);
        try {
            if (parts.length != 2 || !MessageDigest.isEqual(mac(stamp, parts[0]), Base64.getUrlDecoder().decode(parts[1]))) throw bad();
            JsonNode payload = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if (payload.path("expires").asLong() < System.currentTimeMillis()) throw new ResponseStatusException(HttpStatus.GONE);
            return payload;
        } catch (IllegalArgumentException | IOException invalid) { throw bad(); }
    }

    private byte[] mac(String stamp, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(cursorKey, "HmacSHA256"));
            return mac.doFinal(("simpleplane-log-cursor-v1\0" + stamp + "\0" + value).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException invalid) { throw new IllegalStateException(invalid); }
    }

    private static String value(String input, int limit) {
        if (input == null) return "";
        if (input.length() > limit || input.chars().anyMatch(Character::isISOControl)) throw bad();
        return input.strip();
    }

    private static String choice(String input, Set<String> allow) {
        String result = value(input, 50);
        if (!result.isEmpty() && !allow.contains(result)) throw bad();
        return result;
    }

    private static void term(List<Object> filters, String field, String value) {
        if (!value.isEmpty()) filters.add(Map.of("term", Map.of(field, value)));
    }

    private static ResponseStatusException bad() { return new ResponseStatusException(HttpStatus.BAD_REQUEST); }
}
