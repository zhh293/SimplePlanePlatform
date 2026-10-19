package io.loglens.config;

import io.loglens.api.LogLevel;
import io.loglens.api.NodeDefinition;
import io.loglens.api.Settings;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static io.loglens.config.LogLensConfig.*;

public final class ConfigLoader {
    private static final int MAX_CONFIG_BYTES = 1_048_576;
    private ConfigLoader() {}
    public static LogLensConfig load(Path file) throws IOException {
        Path config = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(config)) throw new IllegalArgumentException("config file missing: " + config);
        if (Files.size(config) > MAX_CONFIG_BYTES) throw new IllegalArgumentException("config file exceeds 1 MiB");
        byte[] bytes = Files.readAllBytes(config);
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(32);
        options.setCodePointLimit(MAX_CONFIG_BYTES);
        Object loaded = new Yaml(new SafeConstructor(options)).load(new String(bytes, StandardCharsets.UTF_8));
        Settings root = new Settings(Settings.asMap(loaded, "config"), "config");
        root.only("schemaVersion", "application", "agent", "capture", "runtime", "redaction", "pipeline", "output", "web", "proxyRemote");
        int version = root.integer("schemaVersion", -1, 1, 1);
        if (version != 1) throw root.error("schemaVersion", "required and must equal 1");
        String application = root.string("application", "application");
        if (!application.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}") || application.equals(".") || application.equals(".."))
            throw root.error("application", "expected safe directory name (1..80 characters)");
        Path base = config.getParent();
        Settings a = new Settings(root.map("agent"), "agent");
        a.only("mode", "consoleAppenderNames", "failurePolicy", "pluginsDirectory");
        String modeValue = a.string("mode", "observe");
        Mode mode = switch (modeValue) {
            case "observe" -> Mode.OBSERVE;
            case "replace-console" -> Mode.REPLACE_CONSOLE;
            default -> throw a.error("mode", "expected observe or replace-console");
        };
        Set<String> consoleNames = new HashSet<>(a.strings("consoleAppenderNames", List.of()));
        if (mode == Mode.REPLACE_CONSOLE && consoleNames.isEmpty()) throw a.error("consoleAppenderNames", "required for replace-console");
        Agent agent = new Agent(mode, consoleNames, a.enumeration("failurePolicy", FailurePolicy.FALLBACK_OBSERVE, FailurePolicy.class), resolve(base, a.string("pluginsDirectory", "./plugins")));
        if (Files.exists(agent.pluginsDirectory()) && !Files.isDirectory(agent.pluginsDirectory())) throw a.error("pluginsDirectory", "not a directory");
        Settings c = new Settings(root.map("capture"), "capture");
        c.only("levels", "includeLoggers", "excludeLoggers", "maxMessageChars", "maxStackFrames", "captureCaller", "maxMdcEntries", "maxMdcValueChars", "maxParameterChars", "maxParameters", "maxExceptionChars");
        Set<LogLevel> levels = new HashSet<>();
        for (String level : c.strings("levels", List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR"))) {
            try { levels.add(LogLevel.valueOf(level)); } catch (IllegalArgumentException e) { throw c.error("levels", "unknown level " + level); }
        }
        if (levels.isEmpty()) throw c.error("levels", "at least one level required");
        Capture capture = new Capture(levels, c.strings("includeLoggers", List.of()), c.strings("excludeLoggers", List.of("io.loglens")),
                c.integer("maxMessageChars", 16384, 64, 1_048_576), c.integer("maxStackFrames", 128, 0, 4096), c.bool("captureCaller", false),
                c.integer("maxMdcEntries", 64, 0, 1024), c.integer("maxMdcValueChars", 4096, 16, 65536),
                c.integer("maxParameterChars", 4096, 16, 65536), c.integer("maxParameters", 32, 0, 1024), c.integer("maxExceptionChars", 16384, 64, 1_048_576));
        Settings r = new Settings(root.map("runtime"), "runtime");
        r.only("ringBufferSize", "overflowPolicy", "tickMillis", "shutdownTimeoutMillis", "processorFailurePolicy", "slowProcessorMillis");
        int ring = r.integer("ringBufferSize", 8192, 2, 1_048_576);
        if ((ring & (ring - 1)) != 0) throw r.error("ringBufferSize", "must be a power of two");
        LogLensConfig.Runtime runtime = new LogLensConfig.Runtime(ring, r.enumeration("overflowPolicy", OverflowPolicy.DROP_NEWEST, OverflowPolicy.class),
                r.integer("tickMillis", 200, 10, 60000), r.integer("shutdownTimeoutMillis", 5000, 100, 120000),
                r.enumeration("processorFailurePolicy", ProcessorFailurePolicy.OUTPUT_FAILED, ProcessorFailurePolicy.class), r.integer("slowProcessorMillis", 50, 1, 60000));
        Settings red = new Settings(root.map("redaction"), "redaction");
        red.only("mdcKeys", "messageRules");
        List<MessageRule> rules = new ArrayList<>();
        Set<String> redactionIds = new HashSet<>();
        for (Map<String, Object> values : red.maps("messageRules")) {
            Settings rule = new Settings(values, "redaction.messageRules");
            rule.only("id", "pattern", "replacement");
            String id = rule.string("id", null);
            if (id == null || !redactionIds.add(id)) throw rule.error("id", "required and unique");
            String regex = rule.string("pattern", null);
            if (regex == null) throw rule.error("pattern", "required");
            Pattern pattern = compile(regex, "redaction rule " + id);
            String replacement = rule.string("replacement", "***");
            validateReplacement(pattern, replacement, "redaction rule " + id);
            rules.add(new MessageRule(id, pattern, replacement));
        }
        Redaction redaction = new Redaction(new HashSet<>(red.strings("mdcKeys", List.of("password", "token"))), rules);
        List<NodeDefinition> pipeline = new ArrayList<>();
        for (Map<String, Object> nodeMap : root.maps("pipeline")) {
            Settings node = new Settings(nodeMap, "pipeline");
            node.only("id", "provider", "config");
            pipeline.add(new NodeDefinition(node.string("id", null), node.string("provider", null), node.map("config")));
        }
        if (pipeline.isEmpty()) pipeline = defaultPipeline();
        validatePipeline(pipeline);
        Settings o = new Settings(root.map("output"), "output");
        o.only("directory", "text", "structured", "console", "search");
        Settings t = new Settings(o.map("text"), "output.text");
        t.only("combined", "splitByLevel", "rotateSizeMiB", "retentionDays");
        Settings s = new Settings(o.map("structured"), "output.structured");
        s.only("enabled", "rotateSizeMiB", "maxDiskMiB");
        Settings con = new Settings(o.map("console"), "output.console");
        con.only("processed");
        Settings search = new Settings(o.map("search"), "output.search");
        search.only("backend", "endpoint", "indexPrefix", "apiKeyEnv", "connectTimeoutMillis", "requestTimeoutMillis");
        String backendValue = search.string("backend", "sqlite");
        SearchBackend searchBackend = switch (backendValue) {
            case "sqlite" -> SearchBackend.SQLITE;
            case "elasticsearch" -> SearchBackend.ELASTICSEARCH;
            default -> throw search.error("backend", "expected sqlite or elasticsearch");
        };
        String endpoint = search.string("endpoint", "http://127.0.0.1:9200");
        java.net.URI endpointUri;
        try { endpointUri = java.net.URI.create(endpoint); }
        catch (IllegalArgumentException invalid) { throw search.error("endpoint", "expected an HTTP(S) URL"); }
        if (!Set.of("http", "https").contains(endpointUri.getScheme()) || endpointUri.getHost() == null || endpointUri.getUserInfo() != null
                || endpointUri.getQuery() != null || endpointUri.getFragment() != null || endpoint.length() > 2048)
            throw search.error("endpoint", "expected an HTTP(S) URL without embedded credentials, query or fragment");
        String indexPrefix = search.string("indexPrefix", "loglens");
        if (!indexPrefix.matches("[a-z0-9][a-z0-9_-]{0,39}")) throw search.error("indexPrefix", "expected lowercase index prefix (1..40 characters)");
        String apiKeyEnv = search.string("apiKeyEnv", "LOG_LENS_ELASTICSEARCH_API_KEY");
        if (!apiKeyEnv.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) throw search.error("apiKeyEnv", "expected environment variable name");
        String apiKey = searchBackend == SearchBackend.ELASTICSEARCH ? System.getenv(apiKeyEnv) : null;
        if (apiKey != null && (apiKey.length() > 4096 || apiKey.isBlank())) throw search.error("apiKeyEnv", "environment value is invalid");
        Search searchConfig = new Search(searchBackend, endpoint.replaceAll("/+\\s*$", ""), indexPrefix, apiKey,
                search.integer("connectTimeoutMillis", 1000, 100, 60000), search.integer("requestTimeoutMillis", 3000, 100, 120000));
        if (searchBackend == SearchBackend.ELASTICSEARCH && !s.bool("enabled", true)) {
            // Online query APIs require the canonical JSONL archive.
            throw search.error("backend", "elasticsearch requires structured storage");
        }
        Path outputDirectory = resolve(base, o.string("directory", "./logs"));
        if (Files.exists(outputDirectory) && !Files.isDirectory(outputDirectory)) throw o.error("directory", "not a directory");
        Output output = new Output(outputDirectory, new Text(t.bool("combined", true), t.bool("splitByLevel", true),
                t.integer("rotateSizeMiB", 100, 1, 16384), t.integer("retentionDays", 7, 1, 3650)),
                new Structured(s.bool("enabled", true), s.integer("rotateSizeMiB", 100, 1, 16384), s.integer("maxDiskMiB", 2048, 1, 1_048_576)),
                new Console(con.bool("processed", false)), searchConfig);
        Settings w = new Settings(root.map("web"), "web");
        w.only("enabled", "host", "port", "recentRecords", "clientQueueSize", "maxClients", "maxQueryLimit");
        String host = w.string("host", "127.0.0.1");
        if (!Set.of("127.0.0.1", "::1", "localhost").contains(host)) throw w.error("host", "only loopback hosts supported");
        Web web = new Web(w.bool("enabled", true), host, w.integer("port", 9527, 0, 65535),
                w.integer("recentRecords", 5000, 100, 100000), w.integer("clientQueueSize", 1000, 1, 10000),
                w.integer("maxClients", 16, 1, 128), w.integer("maxQueryLimit", 500, 1, 1000));
        if (web.enabled() && !output.structured().enabled()) throw w.error("enabled", "web requires structured storage");
        Settings pr=new Settings(root.map("proxyRemote"),"proxyRemote");
        pr.only("enabled","sampleIntervalMillis","maxTrackedConnections","maxTrackedSessions");
        ProxyRemote proxyRemote=new ProxyRemote(pr.bool("enabled",false),pr.integer("sampleIntervalMillis",1000,100,60000),
                pr.integer("maxTrackedConnections",10000,1,1000000),pr.integer("maxTrackedSessions",50000,1,2000000));
        if(proxyRemote.enabled()&&!output.structured().enabled())throw pr.error("enabled","proxy telemetry requires structured storage");
        return new LogLensConfig(version, application, config, hash(bytes), agent, capture, runtime, redaction, pipeline, output, web, proxyRemote);
    }
    private static Path resolve(Path base, String value) { return base.resolve(value).toAbsolutePath().normalize(); }
    public static List<NodeDefinition> defaultPipeline() {
        return List.of(new NodeDefinition("extract", "builtin-field-extract", Map.of()),
                new NodeDefinition("template", "builtin-template", Map.of()),
                new NodeDefinition("fold", "builtin-repeat-fold", Map.of()),
                new NodeDefinition("correlate", "builtin-correlation", Map.of()),
                new NodeDefinition("highlight", "builtin-highlight", Map.of()));
    }
    public static void validatePipeline(List<NodeDefinition> pipeline) {
        if (pipeline.size() > 64) throw new IllegalArgumentException("pipeline: maximum 64 nodes");
        Set<String> ids = new HashSet<>();
        for (NodeDefinition node : pipeline) {
            if (!ids.add(node.id())) throw new IllegalArgumentException("pipeline duplicate node id: " + node.id());
            if (!node.id().matches("[A-Za-z0-9._-]{1,80}")) throw new IllegalArgumentException("pipeline invalid node id: " + node.id());
        }
        int template = index(pipeline, "builtin-template"), fold = index(pipeline, "builtin-repeat-fold");
        if (fold >= 0 && (template < 0 || template > fold)) throw new IllegalArgumentException("pipeline: template must precede repeat-fold");
        int extract = index(pipeline, "builtin-field-extract");
        if (extract >= 0 && fold >= 0 && extract > fold) throw new IllegalArgumentException("pipeline: field-extract must precede repeat-fold");
    }
    private static int index(List<NodeDefinition> nodes, String provider) {
        for (int i = 0; i < nodes.size(); i++) if (nodes.get(i).provider().equals(provider)) return i;
        return -1;
    }
    public static Pattern compile(String regex, String description) {
        if (regex.length() > 8192) throw new IllegalArgumentException(description + ": regex too long");
        try { return Pattern.compile(regex); }
        catch (PatternSyntaxException e) { throw new IllegalArgumentException(description + ": invalid regex (offset " + e.getIndex() + ")"); }
    }
    public static void validateReplacement(Pattern pattern, String replacement, String description) {
        // Validate references even when the regex does not happen to match a probe value.
        for (int i = 0; i < replacement.length(); i++) {
            char ch = replacement.charAt(i);
            if (ch == '\\') { if (++i == replacement.length()) throw new IllegalArgumentException(description + ": dangling escape"); }
            else if (ch == '$') {
                if (++i == replacement.length()) throw new IllegalArgumentException(description + ": dangling group reference");
                char next = replacement.charAt(i);
                if (next == '{') {
                    int end = replacement.indexOf('}', i);
                    if (end < 0) throw new IllegalArgumentException(description + ": invalid named reference");
                    String name = replacement.substring(i + 1, end);
                    if (!name.matches("[A-Za-z][A-Za-z0-9]*") || !pattern.pattern().contains("(?<" + name + ">"))
                        throw new IllegalArgumentException(description + ": unknown named group");
                    i = end;
                } else if (next >= '0' && next <= '9') {
                    int group = next - '0';
                    if (group > pattern.matcher("").groupCount()) throw new IllegalArgumentException(description + ": unknown capture group");
                    while (i + 1 < replacement.length() && Character.isDigit(replacement.charAt(i + 1))) {
                        int combined = group * 10 + replacement.charAt(i + 1) - '0';
                        if (combined > pattern.matcher("").groupCount()) break;
                        group = combined;
                        i++;
                    }
                } else throw new IllegalArgumentException(description + ": invalid group reference");
            }
        }
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
