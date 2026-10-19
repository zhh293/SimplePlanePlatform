package io.loglens.storage;

import io.loglens.api.*;
import org.sqlite.JDBC;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** The index contains query fields and byte locations; JSONL remains authoritative. */
public final class SqliteIndex implements SearchIndex {
    private final Path directory;
    private final Path indexFile;
    private final Connection writer;
    public SqliteIndex(Path directory, Path indexFile, boolean writable) throws SQLException {
        this.directory = directory.toAbsolutePath().normalize(); this.indexFile = indexFile.toAbsolutePath().normalize();
        writer = writable ? connect(false) : null;
        if (writable) {
            try (Statement s = writer.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA synchronous=NORMAL");
                s.execute("CREATE TABLE IF NOT EXISTS captured(seq INTEGER PRIMARY KEY, id TEXT UNIQUE NOT NULL, segment TEXT NOT NULL, offset INTEGER NOT NULL, length INTEGER NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS records(seq INTEGER PRIMARY KEY, id TEXT UNIQUE NOT NULL, level TEXT, time INTEGER, logger TEXT, message TEXT, traceId TEXT, orderId TEXT, segment TEXT NOT NULL, offset INTEGER NOT NULL, length INTEGER NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS tags(record_seq INTEGER NOT NULL, tag TEXT NOT NULL, PRIMARY KEY(record_seq,tag))");
                s.execute("CREATE TABLE IF NOT EXISTS source_groups(capture_seq INTEGER NOT NULL, group_key TEXT NOT NULL, segment TEXT NOT NULL, PRIMARY KEY(capture_seq,group_key))");
                s.execute("CREATE TABLE IF NOT EXISTS telemetry(seq INTEGER PRIMARY KEY, kind TEXT NOT NULL, connection_id TEXT, stream_id TEXT, peer TEXT, transport TEXT, target TEXT, status TEXT, time INTEGER NOT NULL, segment TEXT NOT NULL, offset INTEGER NOT NULL, length INTEGER NOT NULL)");
                s.execute("CREATE INDEX IF NOT EXISTS ix_telemetry_time ON telemetry(time,seq)");
                s.execute("CREATE INDEX IF NOT EXISTS ix_telemetry_connection ON telemetry(connection_id,seq)");
                s.execute("CREATE INDEX IF NOT EXISTS ix_telemetry_peer ON telemetry(peer,seq)");
                s.execute("CREATE INDEX IF NOT EXISTS ix_telemetry_target ON telemetry(target,seq)");
                s.execute("CREATE INDEX IF NOT EXISTS ix_groups ON source_groups(group_key,capture_seq)");
                s.execute("CREATE INDEX IF NOT EXISTS ix_level ON records(level,seq)");
                s.execute("CREATE INDEX IF NOT EXISTS ix_logger ON records(logger,seq)");
                s.execute("CREATE INDEX IF NOT EXISTS ix_trace ON records(traceId,seq)");
                s.execute("CREATE INDEX IF NOT EXISTS ix_order ON records(orderId,seq)");
            }
        }
    }
    private Connection connect(boolean readOnly) throws SQLException {
        String url = "jdbc:sqlite:" + indexFile.toString();
        Properties properties = new Properties();
        if (readOnly) properties.setProperty("open_mode", "1");
        Connection connection = new JDBC().connect(url, properties);
        try (Statement s = connection.createStatement()) { s.execute("PRAGMA busy_timeout=3000"); if (readOnly) s.execute("PRAGMA query_only=ON"); }
        return connection;
    }
    public void captured(CapturedLog event, SegmentWriter.Pointer pointer) throws SQLException {
        try (PreparedStatement s = writer.prepareStatement("INSERT OR REPLACE INTO captured VALUES(?,?,?,?,?)")) {
            s.setLong(1, event.captureSequence()); s.setString(2, event.eventId()); pointer(s, 3, pointer); s.executeUpdate();
        }
    }
    public void telemetry(TelemetryEvent event, SegmentWriter.Pointer pointer) throws SQLException {
        try (PreparedStatement s=writer.prepareStatement("INSERT OR REPLACE INTO telemetry VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
            s.setLong(1,event.captureSequence()); s.setString(2,event.kind().name()); s.setString(3,event.connectionId());
            s.setString(4,event.streamId()); s.setString(5,event.peerAddress()); s.setString(6,event.transport());
            s.setString(7,event.targetHost()); s.setString(8,event.status()); s.setLong(9,event.eventTime());
            pointer(s,10,pointer); s.executeUpdate();
        }
    }
    private static void pointer(PreparedStatement s, int first, SegmentWriter.Pointer pointer) throws SQLException {
        s.setString(first, pointer.segment()); s.setLong(first + 1, pointer.offset()); s.setInt(first + 2, pointer.length());
    }
    public void processed(ProcessedRecord record, SegmentWriter.Pointer pointer) throws SQLException {
        writer.setAutoCommit(false);
        try {
            try (PreparedStatement s = writer.prepareStatement("INSERT OR REPLACE INTO records VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                s.setLong(1, record.recordSequence()); s.setString(2, record.recordId()); s.setString(3, record.level().name());
                s.setLong(4, record.firstSeen()); s.setString(5, record.logger()); s.setString(6, record.message());
                s.setString(7, record.fields().getOrDefault("traceId", record.mdc().get("traceId")));
                s.setString(8, record.fields().getOrDefault("orderId", record.mdc().get("orderId"))); pointer(s, 9, pointer); s.executeUpdate();
            }
            try (PreparedStatement s = writer.prepareStatement("INSERT OR REPLACE INTO tags VALUES(?,?)")) {
                for (String tag : record.tags()) { s.setLong(1, record.recordSequence()); s.setString(2, tag); s.addBatch(); }
                s.executeBatch();
            }
            writer.commit();
        } catch (SQLException e) { writer.rollback(); throw e; }
        finally { writer.setAutoCommit(true); }
    }
    public void associate(long sequence, List<String> by, List<String> values, String segment) throws SQLException {
        try (PreparedStatement s = writer.prepareStatement("INSERT OR IGNORE INTO source_groups VALUES(?,?,?)")) {
            s.setLong(1, sequence); s.setString(2, GroupKey.encode(by, values)); s.setString(3, segment); s.executeUpdate();
        }
    }
    public void removeSegment(String filename) throws SQLException {
        writer.setAutoCommit(false);
        try {
            for (String query : List.of("DELETE FROM telemetry WHERE segment=?", "DELETE FROM tags WHERE record_seq IN (SELECT seq FROM records WHERE segment=?)", "DELETE FROM source_groups WHERE segment=?", "DELETE FROM source_groups WHERE capture_seq IN (SELECT seq FROM captured WHERE segment=?)", "DELETE FROM records WHERE segment=?", "DELETE FROM captured WHERE segment=?")) {
                try (PreparedStatement s = writer.prepareStatement(query)) { s.setString(1, filename); s.executeUpdate(); }
            }
            writer.commit();
        } catch (SQLException e) { writer.rollback(); throw e; }
        finally { writer.setAutoCommit(true); }
    }
    public RecordRepository.Page<ProcessedRecord> records(RecordQuery query) {
        StringBuilder sql = new StringBuilder("SELECT seq,segment,offset,length FROM records r WHERE seq>? AND seq<=?");
        List<Object> parameters = new ArrayList<>(List.of(query.after(), query.through()));
        filter(sql, parameters, "level", query.level()); filter(sql, parameters, "logger", query.logger());
        filter(sql, parameters, "traceId", query.traceId()); filter(sql, parameters, "orderId", query.orderId());
        if (query.tag() != null && !query.tag().isBlank()) { sql.append(" AND EXISTS(SELECT 1 FROM tags t WHERE t.record_seq=r.seq AND t.tag=?)"); parameters.add(query.tag()); }
        if (query.search() != null && !query.search().isBlank()) { sql.append(" AND instr(message,?)>0"); parameters.add(query.search()); }
        sql.append(" ORDER BY seq LIMIT ?"); parameters.add(query.limit() + 1);
        return readPage(sql.toString(), parameters, query.limit(), query.after(), ProcessedRecord.class);
    }
    private static void filter(StringBuilder sql, List<Object> parameters, String field, String value) {
        if (value != null && !value.isBlank()) { sql.append(" AND ").append(field).append("=?"); parameters.add(value); }
    }
    private <T> RecordRepository.Page<T> readPage(String sql, List<Object> parameters, int limit, long after, Class<T> type) {
        try (Connection connection = connect(true); PreparedStatement s = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.size(); i++) s.setObject(i + 1, parameters.get(i));
            List<SegmentReader.Location> locations = new ArrayList<>(); long next = after; boolean more = false;
            try (ResultSet result = s.executeQuery()) {
                while (result.next()) {
                    if (locations.size() == limit) { more = true; break; }
                    locations.add(new SegmentReader.Location(result.getString("segment"), result.getLong("offset"), result.getInt("length")));
                    next = result.getLong("seq");
                }
            }
            return new RecordRepository.Page<>(SegmentReader.readMany(directory, locations, type), next, more);
        } catch (SQLException | IOException e) { throw new StorageException("query failed", e); }
    }
    public RecordRepository.Page<TelemetryEvent> telemetry(TelemetryQuery query) {
        StringBuilder sql=new StringBuilder("SELECT seq,segment,offset,length FROM telemetry WHERE seq>? AND seq<=?");
        List<Object> values=new ArrayList<>(List.of(query.after(),query.through()));
        filter(sql,values,"kind",query.kind()); filter(sql,values,"connection_id",query.connectionId());
        filter(sql,values,"stream_id",query.streamId()); if(query.peerAddress()!=null&&!query.peerAddress().isBlank()){sql.append(" AND instr(peer,?)>0");values.add(query.peerAddress());}
        filter(sql,values,"transport",query.transport()); filter(sql,values,"status",query.status());
        if(query.target()!=null&&!query.target().isBlank()){sql.append(" AND instr(target,?)>0");values.add(query.target());}
        if(query.fromTime()>0){sql.append(" AND time>=?");values.add(query.fromTime());}
        sql.append(" AND time<=?");values.add(query.toTime());
        sql.append(" ORDER BY seq LIMIT ?");values.add(query.limit()+1);
        return readPage(sql.toString(),values,query.limit(),query.after(),TelemetryEvent.class);
    }
    public long telemetryWatermark(){try(Connection c=connect(true);Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COALESCE(MAX(seq),0) FROM telemetry")){r.next();return r.getLong(1);}catch(SQLException e){throw new StorageException("telemetry watermark query failed",e);}}
    public ProcessedRecord record(String id) {
        try (Connection connection = connect(true); PreparedStatement s = connection.prepareStatement("SELECT segment,offset,length FROM records WHERE id=?")) {
            s.setString(1, id);
            try (ResultSet r = s.executeQuery()) { return r.next() ? row(r.getString(1), r.getLong(2), r.getInt(3), ProcessedRecord.class) : null; }
        } catch (SQLException | IOException e) { throw new StorageException("record query failed", e); }
    }
    public RecordRepository.Page<CapturedLog> captured(long after, int limit) {
        if (after < 0 || limit < 1 || limit > 1000) throw new IllegalArgumentException("captured cursor/limit");
        return readPage("SELECT seq,segment,offset,length FROM captured WHERE seq>? ORDER BY seq LIMIT ?", List.of(after, limit + 1), limit, after, CapturedLog.class);
    }
    public RecordRepository.Sources sources(ProcessedRecord record, long after, int limit) {
        if (after < 0 || limit < 1 || limit > 1000) throw new IllegalArgumentException("source cursor/limit");
        SourceRefs refs = record.sourceRefs();
        String sql = "SELECT c.seq,c.segment,c.offset,c.length FROM captured c WHERE c.seq>? AND c.seq>=? AND c.seq<=?";
        List<Object> values = new ArrayList<>(List.of(after, refs.firstCaptureSequence(), refs.lastCaptureSequence()));
        if (!refs.groupBy().isEmpty()) { sql += " AND EXISTS(SELECT 1 FROM source_groups g WHERE g.capture_seq=c.seq AND g.group_key=?)"; values.add(GroupKey.encode(refs.groupBy(), refs.groupValues())); }
        String countSql = sql.replace("c.seq>? AND ", "").replace("SELECT c.seq,c.segment,c.offset,c.length", "SELECT count(*)");
        boolean expired;
        try (Connection connection = connect(true); PreparedStatement s = connection.prepareStatement(countSql)) {
            for (int i = 1; i < values.size(); i++) s.setObject(i, values.get(i));
            try (ResultSet r = s.executeQuery()) { r.next(); expired = r.getLong(1) < record.repeatCount(); }
        } catch (SQLException e) { throw new StorageException("source count query failed", e); }
        sql += " ORDER BY c.seq LIMIT ?"; values.add(limit + 1);
        return new RecordRepository.Sources(readPage(sql, values, limit, after, CapturedLog.class), expired);
    }
    public long watermark() {
        try (Connection connection = connect(true); Statement s = connection.createStatement(); ResultSet result = s.executeQuery("SELECT COALESCE(MAX(seq),0) FROM records")) { result.next(); return result.getLong(1); }
        catch (SQLException e) { throw new StorageException("watermark query failed", e); }
    }
    private <T> T row(String segment, long offset, int length, Class<T> type) throws IOException {
        return SegmentReader.read(directory, segment, offset, length, type);
    }
    public void flush() throws SQLException { if (writer != null) try (Statement s = writer.createStatement()) { s.execute("PRAGMA wal_checkpoint(PASSIVE)"); } }
    @Override public void close() throws SQLException { if (writer != null) writer.close(); }
}
