package io.loglens.config;

import io.loglens.api.LogLevel;
import io.loglens.api.NodeDefinition;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Loaded exactly once. Collections and nested node config values are deeply immutable. */
public record LogLensConfig(int schemaVersion, String application, Path configPath, String configHash,
                            Agent agent, Capture capture, Runtime runtime, Redaction redaction,
                            List<NodeDefinition> pipeline, Output output, Web web, ProxyRemote proxyRemote) {
    public LogLensConfig { pipeline = List.copyOf(pipeline); }
    public enum Mode { OBSERVE, REPLACE_CONSOLE }
    public enum FailurePolicy { FALLBACK_OBSERVE, STRICT }
    public enum OverflowPolicy { DROP_NEWEST, BLOCK }
    public enum ProcessorFailurePolicy { OUTPUT_FAILED, DROP }
    public record Agent(Mode mode, Set<String> consoleAppenderNames, FailurePolicy failurePolicy, Path pluginsDirectory) {
        public Agent { consoleAppenderNames = Set.copyOf(consoleAppenderNames); }
    }
    public record Capture(Set<LogLevel> levels, List<String> includeLoggers, List<String> excludeLoggers,
                          int maxMessageChars, int maxStackFrames, boolean captureCaller,
                          int maxMdcEntries, int maxMdcValueChars, int maxParameterChars,
                          int maxParameters, int maxExceptionChars) {
        public Capture { levels = Set.copyOf(levels); includeLoggers = List.copyOf(includeLoggers); excludeLoggers = List.copyOf(excludeLoggers); }
        public boolean accepts(String logger, LogLevel level) {
            return levels.contains(level) && (includeLoggers.isEmpty() || includeLoggers.stream().anyMatch(logger::startsWith))
                    && excludeLoggers.stream().noneMatch(logger::startsWith);
        }
    }
    public record Runtime(int ringBufferSize, OverflowPolicy overflowPolicy, int tickMillis,
                          int shutdownTimeoutMillis, ProcessorFailurePolicy processorFailurePolicy,
                          int slowProcessorMillis) {}
    public record Redaction(Set<String> mdcKeys, List<MessageRule> messageRules) {
        public Redaction { mdcKeys = Set.copyOf(mdcKeys); messageRules = List.copyOf(messageRules); }
    }
    public record MessageRule(String id, Pattern pattern, String replacement) {}
    public record Output(Path directory, Text text, Structured structured, Console console, Search search) {
        public Output(Path directory, Text text, Structured structured, Console console) {
            this(directory, text, structured, console, Search.sqlite());
        }
    }
    public enum SearchBackend { SQLITE, ELASTICSEARCH }
    public record Search(SearchBackend backend, String endpoint, String indexPrefix, String apiKey,
                         int connectTimeoutMillis, int requestTimeoutMillis) {
        public static Search sqlite() { return new Search(SearchBackend.SQLITE, "http://127.0.0.1:9200", "loglens", null, 1000, 3000); }
    }
    public record Text(boolean combined, boolean splitByLevel, int rotateSizeMiB, int retentionDays) {}
    public record Structured(boolean enabled, int rotateSizeMiB, int maxDiskMiB) {}
    public record Console(boolean processed) {}
    public record Web(boolean enabled, String host, int port, int recentRecords, int clientQueueSize,
                      int maxClients, int maxQueryLimit) {}
    public record ProxyRemote(boolean enabled,int sampleIntervalMillis,int maxTrackedConnections,int maxTrackedSessions) {}
}
