package io.loglens.storage;

import com.fasterxml.jackson.databind.JsonNode;
import io.loglens.api.CapturedLog;
import io.loglens.api.GroupKey;
import io.loglens.api.ProcessedRecord;
import io.loglens.api.TelemetryEvent;
import io.loglens.config.LogLensConfig;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Elasticsearch REST implementation of the online index. JSONL remains the record source. */
public final class ElasticsearchIndex implements SearchIndex {
    private record BulkAction(String index, String id, byte[] metadataBytes, byte[] sourceBytes, int encodedBytes) {}
    private static final int MAX_BULK_ACTIONS = 100;
    private static final int MAX_BULK_BYTES = 8 * 1024 * 1024;
    private final Path directory;
    private final HttpClient client;
    private final URI endpoint;
    private final String apiKey;
    private final String capturedIndex, processedIndex, telemetryIndex, groupsIndex;
    private final Duration requestTimeout;
    private final List<BulkAction> pending = new ArrayList<>();
    private int pendingBytes;
    private IOException bulkFailure;

    public ElasticsearchIndex(Path directory, String application, String sessionId, LogLensConfig.Search settings) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        endpoint = URI.create(settings.endpoint()); apiKey = settings.apiKey();
        requestTimeout = Duration.ofMillis(settings.requestTimeoutMillis());
        client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(settings.connectTimeoutMillis())).version(HttpClient.Version.HTTP_1_1).build();
        String scope = safeName(application) + "-" + safeName(sessionId);
        String root = settings.indexPrefix() + "-" + scope;
        capturedIndex = root + "-captured"; processedIndex = root + "-records";
        telemetryIndex = root + "-telemetry"; groupsIndex = root + "-groups";
        create(capturedIndex, pointerMapping(Map.of("id", keyword()), "seq"));
        create(processedIndex, pointerMapping(Map.of("id", keyword(), "level", keyword(), "logger", keyword(),
                // SQLite uses instr(message, query), so index the original value for literal, case-sensitive substring queries.
                "message", Map.of("type", "wildcard"), "traceId", keyword(), "orderId", keyword(), "tags", keyword(), "time", Map.of("type", "long")), "seq"));
        create(telemetryIndex, pointerMapping(Map.of("kind", keyword(), "connectionId", keyword(), "streamId", keyword(),
                "peerAddress", Map.of("type", "wildcard"), "transport", keyword(), "targetHost", Map.of("type", "wildcard"), "status", keyword(), "eventTime", Map.of("type", "long")), "seq"));
        create(groupsIndex, sortedMapping(Map.of("captureSequence", Map.of("type", "long"), "groupKey", keyword(), "segment", keyword(), "captureSegment", keyword()), "captureSequence"));
    }

    private static Map<String,Object> keyword() { return Map.of("type", "keyword", "ignore_above", 32766); }
    private static Map<String,Object> pointerMapping(Map<String,Object> extra, String sortField) {
        Map<String,Object> fields = new LinkedHashMap<>(); fields.put("seq", Map.of("type", "long")); fields.put("segment", keyword());
        fields.put("offset", Map.of("type", "long")); fields.put("length", Map.of("type", "integer")); fields.putAll(extra);
        return sortedMapping(fields, sortField);
    }
    private static Map<String,Object> sortedMapping(Map<String,Object> fields, String sortField) {
        return Map.of("settings", Map.of("index", Map.of("sort", Map.of("field", sortField, "order", "asc"))),
                "mappings", Map.of("properties", fields));
    }
    private static String safeName(String value) {
        String safe = value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        return safe.length() > 80 ? safe.substring(0, 80) : safe;
    }
    private URI uri(String path) { return endpoint.resolve((endpoint.getPath().endsWith("/") ? "" : "/") + path); }
    private JsonNode request(String method, String path, Object body, boolean allowExisting) throws IOException {
        return requestBytes(method, path, body == null ? null : JsonCodec.bytes(body), body == null ? null : "application/json", allowExisting);
    }
    private JsonNode requestBytes(String method, String path, byte[] body, String contentType, boolean allowExisting) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).timeout(requestTimeout).header("Accept", "application/json");
        if (apiKey != null) builder.header("Authorization", "ApiKey " + apiKey);
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", contentType).method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<byte[]> response;
        try { response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray()); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Elasticsearch request interrupted", interrupted); }
        int status = response.statusCode();
        if (allowExisting && status == 400 && new String(response.body(), StandardCharsets.UTF_8).contains("resource_already_exists_exception")) return JsonCodec.MAPPER.getNodeFactory().nullNode();
        if (status < 200 || status >= 300) throw new IOException("Elasticsearch returned HTTP " + status + " for " + method + " " + path);
        if (response.body().length == 0) return JsonCodec.MAPPER.nullNode();
        try { return JsonCodec.MAPPER.readTree(response.body()); }
        catch (IOException invalid) { throw new IOException("Elasticsearch returned invalid JSON", invalid); }
    }
    private void create(String index, Object mapping) throws IOException { request("PUT", index, mapping, true); }
    private synchronized void enqueue(String index, String id, Map<String,Object> source) throws IOException {
        if (bulkFailure != null) throw bulkFailure;
        byte[] metadata=JsonCodec.bytes(Map.of("index",Map.of("_index",index,"_id",id)));
        byte[] sourceBytes=JsonCodec.bytes(source);
        int encodedBytes=metadata.length+sourceBytes.length+2;
        if(!pending.isEmpty()&&pendingBytes+encodedBytes>MAX_BULK_BYTES)flushPendingLocked();
        pending.add(new BulkAction(index,id,metadata,sourceBytes,encodedBytes)); pendingBytes+=encodedBytes;
        if(pending.size()>=MAX_BULK_ACTIONS||pendingBytes>=MAX_BULK_BYTES)flushPendingLocked();
    }
    private synchronized void flushPending() throws IOException { flushPendingLocked(); }
    private void flushPendingLocked() throws IOException {
        if (bulkFailure != null) throw bulkFailure;
        if (pending.isEmpty()) return;
        try {
            if(pending.size()==1){
                BulkAction action=pending.get(0);
                requestBytes("PUT",action.index()+"/_doc/"+action.id()+"?refresh=wait_for",action.sourceBytes(),"application/json",false);
                pending.clear(); pendingBytes=0; return;
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream(pendingBytes);
            for (BulkAction action : pending) {
                body.write(action.metadataBytes()); body.write('\n'); body.write(action.sourceBytes()); body.write('\n');
            }
            JsonNode response = requestBytes("POST", "_bulk?refresh=wait_for", body.toByteArray(), "application/x-ndjson", false);
            JsonNode results=response.path("items");
            if(!results.isArray()||results.size()!=pending.size())throw new IOException("Elasticsearch bulk response did not confirm every operation");
            long failures=0;
            for(JsonNode item:results){
                JsonNode result=item.elements().hasNext()?item.elements().next():item;
                int status=result.path("status").asInt(500);
                if(status<200||status>=300||result.has("error"))failures++;
            }
            if(response.path("errors").asBoolean()||failures>0)throw new IOException("Elasticsearch bulk contained " + Math.max(1,failures) + " failed operation(s)");
            pending.clear(); pendingBytes=0;
        } catch (IOException failure) {
            // Deterministic IDs make any later replay safe; callers mark the session unhealthy.
            bulkFailure = failure;
            throw failure;
        }
    }
    private static Map<String,Object> pointer(long seq, String id, SegmentWriter.Pointer pointer) {
        return Map.of("seq", seq, "id", id, "segment", pointer.segment(), "offset", pointer.offset(), "length", pointer.length());
    }
    @Override public void captured(CapturedLog event, SegmentWriter.Pointer pointer) throws IOException {
        enqueue(capturedIndex, Long.toString(event.captureSequence()), pointer(event.captureSequence(), event.eventId(), pointer));
    }
    @Override public void telemetry(TelemetryEvent event, SegmentWriter.Pointer pointer) throws IOException {
        Map<String,Object> doc = new LinkedHashMap<>(pointer(event.captureSequence(), event.eventId(), pointer));
        doc.put("kind", event.kind().name()); doc.put("connectionId", event.connectionId()); doc.put("streamId", event.streamId());
        doc.put("peerAddress", event.peerAddress()); doc.put("transport", event.transport()); doc.put("targetHost", event.targetHost());
        doc.put("status", event.status()); doc.put("eventTime", event.eventTime()); enqueue(telemetryIndex, Long.toString(event.captureSequence()), doc);
        flushPending();
    }
    @Override public void processed(ProcessedRecord record, SegmentWriter.Pointer pointer) throws IOException {
        Map<String,Object> doc = new LinkedHashMap<>(pointer(record.recordSequence(), record.recordId(), pointer));
        doc.put("level", record.level().name()); doc.put("logger", record.logger()); doc.put("message", record.message());
        doc.put("traceId", record.fields().getOrDefault("traceId", record.mdc().get("traceId")));
        doc.put("orderId", record.fields().getOrDefault("orderId", record.mdc().get("orderId")));
        doc.put("tags", record.tags()); doc.put("time", record.firstSeen()); enqueue(processedIndex, Long.toString(record.recordSequence()), doc);
        flushPending();
    }
    @Override public void associate(long sequence, List<String> by, List<String> values, String segment) throws IOException {
        flushPending();
        JsonNode hits=request("POST",capturedIndex+"/_search",Map.of("size",1,"query",Map.of("term",Map.of("seq",sequence))),false).path("hits").path("hits");
        if(hits.isEmpty())throw new IOException("captured index row missing for group association");
        String captureSegment=hits.path(0).path("_source").path("segment").asText();
        enqueue(groupsIndex, Long.toString(sequence), Map.of("captureSequence", sequence, "groupKey", GroupKey.encode(by, values), "segment", segment,"captureSegment",captureSegment));
    }
    @Override public void removeSegment(String filename) throws IOException {
        flushPending();
        for (String index : List.of(capturedIndex, processedIndex, telemetryIndex, groupsIndex)) {
            request("POST", index + "/_delete_by_query?conflicts=proceed&refresh=true", Map.of("query", Map.of("term", Map.of("segment", filename))), false);
        }
        request("POST",groupsIndex+"/_delete_by_query?conflicts=proceed&refresh=true",Map.of("query",Map.of("term",Map.of("captureSegment",filename))),false);
    }
    private List<Map<String,Object>> filters() { return new ArrayList<>(); }
    private static void term(List<Map<String,Object>> filters, String field, String value) {
        if (value != null && !value.isBlank()) filters.add(Map.of("term", Map.of(field, value)));
    }
    private RecordRepository.Page<ProcessedRecord> recordPage(RecordQuery q) {
        List<Map<String,Object>> filters = filters(); filters.add(Map.of("range", Map.of("seq", Map.of("gt", q.after(), "lte", q.through()))));
        term(filters,"level",q.level()); term(filters,"logger",q.logger()); term(filters,"traceId",q.traceId()); term(filters,"orderId",q.orderId());
        term(filters,"tags",q.tag());
        if (q.search() != null && !q.search().isBlank()) contains(filters,"message",q.search());
        return page(processedIndex, filters, q.after(), q.limit(), ProcessedRecord.class);
    }
    @Override public RecordRepository.Page<ProcessedRecord> records(RecordQuery query) { return recordPage(query); }
    @Override public RecordRepository.Page<TelemetryEvent> telemetry(TelemetryQuery q) {
        List<Map<String,Object>> filters = filters(); filters.add(Map.of("range", Map.of("seq", Map.of("gt", q.after(), "lte", q.through()))));
        term(filters,"kind",q.kind()); term(filters,"connectionId",q.connectionId()); term(filters,"streamId",q.streamId());
        term(filters,"transport",q.transport()); term(filters,"status",q.status());
        contains(filters,"peerAddress",q.peerAddress()); contains(filters,"targetHost",q.target());
        Map<String,Object> timeBounds=new LinkedHashMap<>(); if(q.fromTime()>0)timeBounds.put("gte",q.fromTime()); timeBounds.put("lte",q.toTime());
        filters.add(Map.of("range",Map.of("eventTime",timeBounds)));
        return page(telemetryIndex, filters, q.after(), q.limit(), TelemetryEvent.class);
    }
    private static void contains(List<Map<String,Object>> filters,String field,String value) {
        if(value==null||value.isBlank())return;
        String escaped=value.replace("\\","\\\\").replace("*","\\*").replace("?","\\?");
        filters.add(Map.of("wildcard",Map.of(field,Map.of("value","*"+escaped+"*"))));
    }
    private <T> RecordRepository.Page<T> page(String index, List<Map<String,Object>> filters, long after, int limit, Class<T> type) {
        try {
            flushPending();
            // seq is unique and monotonic; range-based keyset pagination bounds each page and avoids from/size deep offsets.
            // Fixed `through` watermarks keep live catch-up stable while later documents are appended.
            Map<String,Object> body = Map.of("size", limit + 1, "track_total_hits", false,
                    "query", Map.of("bool", Map.of("filter", filters)), "sort", List.of(Map.of("seq", "asc")));
            JsonNode hits = request("POST", index + "/_search", body, false).path("hits").path("hits");
            List<SegmentReader.Location> locations = new ArrayList<>(); long cursor = after; boolean more = false;
            for (JsonNode hit : hits) {
                if (locations.size() == limit) { more = true; break; }
                JsonNode source = hit.path("_source");
                locations.add(new SegmentReader.Location(source.path("segment").asText(), source.path("offset").asLong(), source.path("length").asInt()));
                cursor = source.path("seq").asLong();
            }
            return new RecordRepository.Page<>(SegmentReader.readMany(directory, locations, type), cursor, more);
        } catch (IOException failure) { throw new StorageException("Elasticsearch search failed", failure); }
    }
    private JsonNode one(String index, Map<String,Object> query) {
        try { flushPending(); return request("POST", index + "/_search", Map.of("size",1,"query",query), false).path("hits").path("hits").path(0); }
        catch (IOException failure) { throw new StorageException("Elasticsearch lookup failed", failure); }
    }
    private static Map<String,Object> pointerQuery(String id) { return Map.of("term",Map.of("id",id)); }
    @Override public ProcessedRecord record(String id) {
        JsonNode hit = one(processedIndex,pointerQuery(id)); if (hit.isMissingNode() || hit.isNull()) return null;
        return hydrate(hit.path("_source"),ProcessedRecord.class);
    }
    private <T> T hydrate(JsonNode source, Class<T> type) {
        try { return SegmentReader.read(directory,source.path("segment").asText(),source.path("offset").asLong(),source.path("length").asInt(),type); }
        catch (IOException failure) { throw new StorageException("Elasticsearch row hydration failed",failure); }
    }
    @Override public RecordRepository.Page<CapturedLog> captured(long after, int limit) {
        if (after < 0 || limit < 1 || limit > 1000) throw new IllegalArgumentException("captured cursor/limit");
        List<Map<String,Object>> filters=List.of(Map.of("range",Map.of("seq",Map.of("gt",after))));
        return page(capturedIndex,filters,after,limit,CapturedLog.class);
    }
    @Override public RecordRepository.Sources sources(ProcessedRecord record, long after, int limit) {
        if (after < 0 || limit < 1 || limit > 1000) throw new IllegalArgumentException("source cursor/limit");
        try { flushPending(); } catch(IOException failure) { throw new StorageException("Elasticsearch pending index write failed", failure); }
        var refs=record.sourceRefs(); List<Long> sourceSequences=new ArrayList<>(); long count;
        if (refs.groupBy().isEmpty()) {
            try {
                Map<String,Object> range=Map.of("range",Map.of("seq",Map.of("gte",refs.firstCaptureSequence(),"lte",refs.lastCaptureSequence())));
                count=request("POST",capturedIndex+"/_count",Map.of("query",range),false).path("count").asLong();
                List<Map<String,Object>> sourceFilters= new ArrayList<>(); sourceFilters.add(range);
                if(after>0)sourceFilters.add(Map.of("range",Map.of("seq",Map.of("gt",after))));
                JsonNode hits=request("POST",capturedIndex+"/_search",Map.of("size",limit+1,"query",Map.of("bool",Map.of("filter",sourceFilters)),"sort",List.of(Map.of("seq","asc"))),false).path("hits").path("hits");
                for(JsonNode hit:hits)sourceSequences.add(hit.path("_source").path("seq").asLong());
            } catch(IOException failure){throw new StorageException("Elasticsearch source lookup failed",failure);}
        } else {
            List<Map<String,Object>> base=List.of(Map.of("range",Map.of("captureSequence",Map.of("gte",refs.firstCaptureSequence(),"lte",refs.lastCaptureSequence()))),
                    Map.of("term",Map.of("groupKey",GroupKey.encode(refs.groupBy(),refs.groupValues()))));
            List<Map<String,Object>> paged=new ArrayList<>(base);if(after>0)paged.add(Map.of("range",Map.of("captureSequence",Map.of("gt",after))));
            try {
                count=request("POST",groupsIndex+"/_count",Map.of("query",Map.of("bool",Map.of("filter",base))),false).path("count").asLong();
                JsonNode hits=request("POST",groupsIndex+"/_search",Map.of("size",limit+1,"query",Map.of("bool",Map.of("filter",paged)),"sort",List.of(Map.of("captureSequence","asc"))),false).path("hits").path("hits");
                sourceSequences.clear(); for(JsonNode hit:hits)sourceSequences.add(hit.path("_source").path("captureSequence").asLong());
            } catch(IOException failure){throw new StorageException("Elasticsearch source lookup failed",failure);}
        }
        List<SegmentReader.Location> locations=new ArrayList<>(); long cursor=after; boolean more=sourceSequences.size()>limit;
        try {
            List<Object> ids=new ArrayList<>(sourceSequences);
            if(!ids.isEmpty()){
                JsonNode found=request("POST",capturedIndex+"/_search",Map.of("size",limit+1,"query",Map.of("terms",Map.of("seq",ids)),"sort",List.of(Map.of("seq","asc"))),false).path("hits").path("hits");
                for(JsonNode hit:found){if(locations.size()==limit){more=true;break;}JsonNode source=hit.path("_source");locations.add(new SegmentReader.Location(source.path("segment").asText(),source.path("offset").asLong(),source.path("length").asInt()));cursor=source.path("seq").asLong();}
            }
        }catch(IOException failure){throw new StorageException("Elasticsearch source hydration failed",failure);}
        try { return new RecordRepository.Sources(new RecordRepository.Page<>(SegmentReader.readMany(directory,locations,CapturedLog.class),cursor,more),count<record.repeatCount()); }
        catch(IOException failure){throw new StorageException("Elasticsearch source hydration failed",failure);}
    }
    @Override public long watermark() { return maximum(processedIndex); }
    @Override public long telemetryWatermark() { return maximum(telemetryIndex); }
    private long maximum(String index) {
        try { flushPending(); return request("POST",index+"/_search",Map.of("size",0,"aggs",Map.of("max_seq",Map.of("max",Map.of("field","seq")))),false).path("aggregations").path("max_seq").path("value").asLong(0); }
        catch(IOException failure){throw new StorageException("Elasticsearch watermark query failed",failure);}
    }
    @Override public void flush() throws IOException { flushPending(); }
    @Override public synchronized void close() throws IOException {
        if (bulkFailure != null) { pending.clear(); pendingBytes=0; return; }
        flushPendingLocked();
    }
}
