package io.loglens.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {
    @TempDir Path directory;
    private Path yaml(String contents) throws Exception {
        Path file = directory.resolve("loglens.yaml");
        Files.writeString(file, contents);
        return file;
    }
    @Test void loadsDefaultsAndPathsRelativeToYaml() throws Exception {
        LogLensConfig config = ConfigLoader.load(yaml("schemaVersion: 1\napplication: demo\n"));
        assertEquals(directory.resolve("logs"), config.output().directory());
        assertEquals(directory.resolve("plugins"), config.agent().pluginsDirectory());
        assertEquals(8192, config.runtime().ringBufferSize());
        assertEquals(5, config.pipeline().size());
        assertFalse(config.proxyRemote().enabled());
        assertEquals(1000,config.proxyRemote().sampleIntervalMillis());
        assertEquals(LogLensConfig.SearchBackend.SQLITE, config.output().search().backend());
        assertEquals(64, config.configHash().length());
    }
    @Test void selectsElasticsearchWithoutChangingTheSqliteDefault() throws Exception {
        LogLensConfig config=ConfigLoader.load(yaml("schemaVersion: 1\napplication: demo\noutput:\n  search:\n    backend: elasticsearch\n    endpoint: https://search.example.test:9243\n    indexPrefix: demo_logs\n    apiKeyEnv: LOG_LENS_TEST_KEY\n    connectTimeoutMillis: 500\n    requestTimeoutMillis: 5000\n"));
        assertEquals(LogLensConfig.SearchBackend.ELASTICSEARCH,config.output().search().backend());
        assertEquals("https://search.example.test:9243",config.output().search().endpoint());
        assertEquals("demo_logs",config.output().search().indexPrefix());
        assertEquals(500,config.output().search().connectTimeoutMillis());
        assertNull(config.output().search().apiKey());
        String summary=ConfigSummary.from(config).toString();
        assertFalse(summary.contains("search.example.test"));
        assertFalse(summary.contains("LOG_LENS_TEST_KEY"));
    }
    @Test void validatesElasticsearchSettingsOnlyWithinSupportedBounds() throws Exception {
        for(String section:List.of("backend: solr", "endpoint: 'file:///tmp/es'", "endpoint: 'http://user:pass@localhost:9200'",
                "indexPrefix: UpperCase", "apiKeyEnv: BAD-NAME", "requestTimeoutMillis: 1")) {
            String settings=section.startsWith("backend:")?section:"backend: elasticsearch\n"+section;
            assertThrows(IllegalArgumentException.class,()->ConfigLoader.load(yaml("schemaVersion: 1\noutput:\n  search:\n    "+settings.replace("\n","\n    ")+"\n")),section);
        }
        assertThrows(IllegalArgumentException.class,()->ConfigLoader.load(yaml("schemaVersion: 1\noutput:\n  structured:\n    enabled: false\n  search:\n    backend: elasticsearch\n")));
    }
    @Test void proxyRemoteTelemetryIsOptInAndRequiresStructuredStorage() throws Exception {
        LogLensConfig enabled=ConfigLoader.load(yaml("schemaVersion: 1\nproxyRemote:\n  enabled: true\n  sampleIntervalMillis: 250\n  maxTrackedConnections: 64\n  maxTrackedSessions: 512\n"));
        assertTrue(enabled.proxyRemote().enabled());
        assertEquals(250,enabled.proxyRemote().sampleIntervalMillis());
        assertEquals(64,enabled.proxyRemote().maxTrackedConnections());
        assertEquals(512,enabled.proxyRemote().maxTrackedSessions());
        assertThrows(IllegalArgumentException.class,()->ConfigLoader.load(yaml("schemaVersion: 1\nproxyRemote:\n  enabled: true\noutput:\n  structured:\n    enabled: false\n")));
        assertThrows(IllegalArgumentException.class,()->ConfigLoader.load(yaml("schemaVersion: 1\nproxyRemote:\n  sampleIntervalMillis: 10\n")));
    }    @Test void fileChangesAndDeletionDoNotMutateLoadedConfiguration() throws Exception {
        Path file = yaml("schemaVersion: 1\napplication: first\n");
        LogLensConfig loaded = ConfigLoader.load(file);
        Files.writeString(file, "schemaVersion: 1\napplication: second\nruntime:\n  ringBufferSize: 16\n");
        LogLensConfig restarted = ConfigLoader.load(file);
        Files.delete(file);
        assertEquals("first", loaded.application());
        assertEquals(8192, loaded.runtime().ringBufferSize());
        assertEquals("second", restarted.application());
        assertNotEquals(loaded.configHash(), restarted.configHash());
    }
    @Test void rejectsUnknownFieldsDuplicateKeysAndUnsafeYaml() throws Exception {
        for (String body : List.of("schemaVersion: 1\nunknown: true\n", "schemaVersion: 1\nweb:\n  typo: true\n",
                "schemaVersion: 1\nschemaVersion: 1\n", "!!java.lang.ProcessBuilder {}", "schemaVersion: 1\nagent:\n  configWatch: true\n")) {
            assertThrows(Exception.class, () -> ConfigLoader.load(yaml(body)), body);
        }
    }
    @Test void rejectsInvalidVersionTypesRangesPathsAndModes() throws Exception {
        for (String suffix : List.of("schemaVersion: 2", "runtime:\n  ringBufferSize: 3", "runtime:\n  tickMillis: -1",
                "runtime:\n  ringBufferSize: 8192.5", "application: ../escape", "agent:\n  mode: replace-console",
                "web:\n  host: 0.0.0.0", "web:\n  port: 70000", "capture:\n  levels: [FATAL]", "capture:\n  captureCaller: yesplease",
                "output:\n  structured:\n    enabled: false")) {
            String prefix = suffix.startsWith("schemaVersion") ? "" : "schemaVersion: 1\n";
            assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(yaml(prefix + suffix + "\n")), suffix);
        }
    }
    @Test void rejectsMissingSchemaAndMissingFile() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(yaml("application: demo\n")));
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(directory.resolve("missing.yaml")));
    }
    @Test void validatesPipelineIdAndOrder() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(yaml("schemaVersion: 1\npipeline:\n  - {id: same, provider: custom}\n  - {id: same, provider: other}\n")));
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(yaml("schemaVersion: 1\npipeline:\n  - {id: fold, provider: builtin-repeat-fold}\n  - {id: template, provider: builtin-template}\n")));
    }
    @Test void compilesRedactionAndSummaryDoesNotExposeSecrets() throws Exception {
        LogLensConfig config = ConfigLoader.load(yaml("schemaVersion: 1\nredaction:\n  messageRules:\n    - id: token\n      pattern: '(token=)[^\\s,]+'\n      replacement: '$1***'\n"));
        var rule = config.redaction().messageRules().get(0);
        assertEquals("token=***", rule.pattern().matcher("token=private").replaceAll(rule.replacement()));
        String summary = ConfigSummary.from(config).toString();
        assertFalse(summary.contains("pattern"));
        assertFalse(summary.contains("replacement"));
        assertTrue(summary.contains(config.configHash()));
    }
    @Test void validatesReplacementEvenWhenPatternCannotMatchProbe() {
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.validateReplacement(Pattern.compile("(token=).+"), "$2", "test"));
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.validateReplacement(Pattern.compile("x"), "$", "test"));
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.validateReplacement(Pattern.compile("(?<value>x)"), "${missing}", "test"));
        assertDoesNotThrow(() -> ConfigLoader.validateReplacement(Pattern.compile("(?<value>x)"), "${value}", "test"));
    }
    @Test void rejectsInvalidRegexWithoutEchoingSensitivePattern() throws Exception {
        Exception error = assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(yaml("schemaVersion: 1\nredaction:\n  messageRules:\n    - {id: bad, pattern: 'private-secret[', replacement: '***'}\n")));
        assertFalse(error.getMessage().contains("private-secret"));
    }
    @Test void nestedNodeConfigIsImmutable() throws Exception {
        LogLensConfig config = ConfigLoader.load(yaml("schemaVersion: 1\npipeline:\n  - id: node\n    provider: custom\n    config:\n      rules:\n        - field: orderId\n"));
        Map<String, Object> values = config.pipeline().get(0).config();
        assertThrows(UnsupportedOperationException.class, () -> values.put("x", true));
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) values.get("rules")).clear());
    }
}
