package io.loglens.storage;

import io.loglens.config.LogLensConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

/** Selects the online query-index strategy once per session. */
public final class SearchIndexFactory {
    private SearchIndexFactory() {}
    public static SearchIndex open(LogLensConfig.Search settings, Path sessionDirectory, String application, String sessionId) throws IOException, SQLException {
        return switch (settings.backend()) {
            case SQLITE -> new SqliteIndex(sessionDirectory, sessionDirectory.resolve("index.sqlite"), true);
            case ELASTICSEARCH -> new ElasticsearchIndex(sessionDirectory, application, sessionId, settings);
        };
    }
}
