package io.loglens.config;

import java.util.Map;

/** Explicit allowlist: never serialize the raw configuration or plugin parameters. */
public final class ConfigSummary {
    private ConfigSummary() {}
    public static Map<String,Object> from(LogLensConfig config) {
        return Map.ofEntries(Map.entry("schemaVersion",config.schemaVersion()),Map.entry("application",config.application()),
                Map.entry("configHash",config.configHash()),Map.entry("mode",config.agent().mode().name()),
                Map.entry("restartRequired",true),Map.entry("captureLevels",config.capture().levels().stream().map(Enum::name).sorted().toList()),
                Map.entry("ringBufferSize",config.runtime().ringBufferSize()),Map.entry("overflowPolicy",config.runtime().overflowPolicy().name()),
                Map.entry("searchBackend",config.output().search().backend().name().toLowerCase(java.util.Locale.ROOT)),
                Map.entry("pipeline",config.pipeline().stream().map(n->Map.of("id",n.id(),"provider",n.provider())).toList()),
                Map.entry("redactionRules",config.redaction().messageRules().stream().map(LogLensConfig.MessageRule::id).toList()),
                Map.entry("proxyRemote",Map.of("enabled",config.proxyRemote().enabled(),"sampleIntervalMillis",config.proxyRemote().sampleIntervalMillis(),
                        "maxTrackedConnections",config.proxyRemote().maxTrackedConnections(),"maxTrackedSessions",config.proxyRemote().maxTrackedSessions())));
    }
}
