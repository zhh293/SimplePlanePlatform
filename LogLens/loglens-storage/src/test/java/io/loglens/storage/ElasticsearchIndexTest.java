package io.loglens.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.loglens.api.CapturedLog;
import io.loglens.api.LogClock;
import io.loglens.api.LogContext;
import io.loglens.api.LogLevel;
import io.loglens.api.ProcessedRecord;
import io.loglens.api.SourceRefs;
import io.loglens.api.TelemetryEvent;
import io.loglens.api.TelemetryKind;
import io.loglens.config.LogLensConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ElasticsearchIndexTest {
    @TempDir Path directory;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void indexesPointersAndHydratesRecordsFromCanonicalJsonl() throws Exception {
        Map<String,Map<String,JsonNode>> documents=new ConcurrentHashMap<>();
        List<Integer> bulkSizes=new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer server=server(documents,false,new java.util.concurrent.CopyOnWriteArrayList<>(),bulkSizes);
        try {
            server.start(); String session="session-123";
            var settings=new LogLensConfig.Search(LogLensConfig.SearchBackend.ELASTICSEARCH,
                    "http://127.0.0.1:"+server.getAddress().getPort(),"loglens",null,1000,3000);
            CapturedLog captured=new CapturedLog(session+":e:1",session,1,100,100,100,LogLevel.INFO,"demo.Logger","main","hello {}","hello world",List.of("world"),Map.of(),null,List.of(),false);
            ProcessedRecord processed=ProcessedRecord.from(session,1,new LogContext(captured));
            SegmentWriter capturedWriter=new SegmentWriter(directory,"captured","jsonl",1_000_000,LogClock.system());
            SegmentWriter processedWriter=new SegmentWriter(directory,"processed","jsonl",1_000_000,LogClock.system());
            SegmentWriter telemetryWriter=new SegmentWriter(directory,"telemetry","jsonl",1_000_000,LogClock.system());
            try(ElasticsearchIndex index=new ElasticsearchIndex(directory,"demo",session,settings)){
                index.captured(captured,capturedWriter.append(JsonCodec.bytes(captured)));
                index.processed(processed,processedWriter.append(JsonCodec.bytes(processed)));
                assertEquals(2,bulkSizes.get(0),"captured and processed pointers should share one bulk request");
                TelemetryEvent telemetry=new TelemetryEvent(session+":t:2",session,2,101,101,101,TelemetryKind.CLIENT_CONNECTED,"c-1",null,"127.0.0.1:42","http2",null,0,"connected",null,0,0);
                index.telemetry(telemetry,telemetryWriter.append(JsonCodec.bytes(telemetry)));
                var page=index.records(RecordQuery.page(0,10));
                assertEquals(1,page.items().size()); assertEquals("hello world",page.items().get(0).message());
                assertEquals(processed.recordId(),index.record(processed.recordId()).recordId());
                assertEquals(captured.eventId(),index.captured(0,10).items().get(0).eventId());
                assertEquals(telemetry.eventId(),index.telemetry(new TelemetryQuery(0,Long.MAX_VALUE,10,null,null,null,null,null,null,null,0,Long.MAX_VALUE)).items().get(0).eventId());
                assertEquals(telemetry.eventId(),index.telemetry(new TelemetryQuery(0,Long.MAX_VALUE,10,null,null,null,"127.0",null,null,null,0,Long.MAX_VALUE)).items().get(0).eventId());
                assertEquals(2,index.telemetryWatermark());
                List<String> groupBy=List.of("logger","level","template","exceptionType");
                List<String> groupValues=List.of("demo.Logger","INFO","hello {}","");
                index.associate(1,groupBy,groupValues,"groups-0001.jsonl");
                LogContext aggregateContext=new LogContext(captured);
                aggregateContext.aggregation(1,100,100,new SourceRefs(groupBy,groupValues,1,1,List.of(captured.eventId())),"WINDOW");
                ProcessedRecord aggregate=ProcessedRecord.from(session,2,aggregateContext);
                var sources=index.sources(aggregate,0,10);
                assertEquals(1,sources.page().items().size()); assertFalse(sources.expired());
                LogContext sparseContext=new LogContext(captured);
                sparseContext.aggregation(1,100,100,new SourceRefs(List.of(),List.of(),1,1_000_000,List.of(captured.eventId())),"WINDOW");
                var sparseSources=index.sources(ProcessedRecord.from(session,3,sparseContext),0,10);
                assertEquals(1,sparseSources.page().items().size()); assertFalse(sparseSources.expired());
                assertEquals(1,index.watermark());
                index.removeSegment("captured-0001.jsonl");
                assertTrue(index.captured(0,10).items().isEmpty());
                assertTrue(index.sources(aggregate,0,10).expired());
                assertEquals(4,documents.size());
                assertTrue(documents.keySet().stream().allMatch(name->name.startsWith("loglens-demo-session-123-")));
            } finally { capturedWriter.close(); processedWriter.close(); telemetryWriter.close(); }
        } finally { server.stop(0); }
    }

    @Test void rejectsElasticsearchHttpFailuresWithoutReturningAHealthyIndex() throws Exception {
        HttpServer server=server(new ConcurrentHashMap<>(),true);
        try {
            server.start();
            var settings=new LogLensConfig.Search(LogLensConfig.SearchBackend.ELASTICSEARCH,
                    "http://127.0.0.1:"+server.getAddress().getPort(),"loglens",null,1000,1000);
            assertThrows(java.io.IOException.class,()->new ElasticsearchIndex(directory,"demo","session",settings));
        } finally { server.stop(0); }
    }

    @Test void elasticsearchUsesLiteralSubstringSearchAndBoundedKeysetPages() throws Exception {
        Map<String,Map<String,JsonNode>> documents=new ConcurrentHashMap<>(); List<JsonNode> searchRequests=new java.util.concurrent.CopyOnWriteArrayList<>(); HttpServer server=server(documents,false,searchRequests);
        try {
            server.start(); String session="search-session";
            var settings=new LogLensConfig.Search(LogLensConfig.SearchBackend.ELASTICSEARCH,
                    "http://127.0.0.1:"+server.getAddress().getPort(),"loglens",null,1000,3000);
            SegmentWriter writer=new SegmentWriter(directory,"processed","jsonl",1_000_000,LogClock.system());
            try(ElasticsearchIndex index=new ElasticsearchIndex(directory,"demo",session,settings)){
                for(long seq:new long[]{10,1000,100000,200000}){
                    String message=(seq==1000||seq==100000)?"prefix ID*42 suffix":seq==200000?"prefix id*42 suffix":"unrelated";
                    CapturedLog captured=new CapturedLog(session+":e:"+seq,session,seq,100,100,100,LogLevel.INFO,"demo.Logger","main",message,message,List.of(),Map.of(),null,List.of(),false);
                    index.processed(ProcessedRecord.from(session,seq,new LogContext(captured)),writer.append(JsonCodec.bytes(ProcessedRecord.from(session,seq,new LogContext(captured)))));
                }
                var first=index.records(new RecordQuery(10,100000,1,null,null,null,null,null,"ID*42"));
                assertEquals(List.of(1000L),first.items().stream().map(ProcessedRecord::recordSequence).toList());
                assertTrue(first.hasMore());
                var second=index.records(new RecordQuery(first.nextCursor(),100000,1,null,null,null,null,null,"ID*42"));
                assertEquals(List.of(100000L),second.items().stream().map(ProcessedRecord::recordSequence).toList());
                assertFalse(second.hasMore());
                var caseSensitive=index.records(new RecordQuery(0,Long.MAX_VALUE,10,null,null,null,null,null,"id*42"));
                assertEquals(List.of(200000L),caseSensitive.items().stream().map(ProcessedRecord::recordSequence).toList());
                assertTrue(searchRequests.stream().allMatch(body->!body.has("from")&&body.path("size").asInt()>0&&body.path("size").asInt()<=11&&body.path("sort").path(0).has("seq")));
                JsonNode firstQuery=searchRequests.get(0).path("query").path("bool").path("filter");
                assertTrue(firstQuery.toString().contains("100000"));
                assertTrue(firstQuery.toString().contains("10"));
                assertTrue(documents.keySet().stream().filter(key->key.endsWith("-records")).findFirst().isPresent());
            } finally { writer.close(); }
        } finally { server.stop(0); }
    }

    @Test void sessionStorageRetainsTextJsonlQueriesAndBroadcastWithElasticsearchSelected() throws Exception {
        Map<String,Map<String,JsonNode>> documents=new ConcurrentHashMap<>(); HttpServer server=server(documents,false);
        try {
            server.start(); Path yaml=directory.resolve("es.yaml");
            java.nio.file.Files.writeString(yaml,"schemaVersion: 1\napplication: demo\noutput:\n  directory: '"+directory.resolve("logs").toString().replace("\\","/")+"'\n  search:\n    backend: elasticsearch\n    endpoint: http://127.0.0.1:"+server.getAddress().getPort()+"\nweb:\n  enabled: false\n");
            try(SessionStorage storage=new SessionStorage(io.loglens.config.ConfigLoader.load(yaml),LogClock.system())){
                AtomicInteger broadcasts=new AtomicInteger(); storage.broadcastTo(ignored->{
                    assertTrue(java.nio.file.Files.exists(storage.directory().resolve("processed-0001.jsonl")));
                    broadcasts.incrementAndGet();
                });
                CapturedLog captured=new CapturedLog("pending","pending",0,100,100,100,io.loglens.api.LogLevel.INFO,"demo.Logger","main","hello {}","hello world",List.of("world"),Map.of(),null,List.of(),false);
                storage.captured(new CapturedLog(captured.eventId(),storage.sessionId(),1,captured.eventTime(),captured.capturedAt(),captured.monotonicNanos(),captured.level(),captured.logger(),captured.thread(),captured.template(),captured.message(),captured.parameters(),captured.mdc(),captured.exception(),captured.caller(),captured.truncated()));
                var event=storage.captured(0,10).items().get(0); storage.processed(ProcessedRecord.from(storage.sessionId(),1,new LogContext(event)));
                assertEquals(1,broadcasts.get()); assertEquals(1,storage.records(RecordQuery.page(0,10)).items().size());
                assertTrue(java.nio.file.Files.readString(storage.directory().resolve("processed-0001.jsonl")).contains("hello world"));
                assertTrue(java.nio.file.Files.readString(storage.directory().resolve("app-0001.log")).contains("hello world"));
                assertTrue(java.nio.file.Files.exists(storage.directory().resolve("captured-0001.jsonl")));
            }
        } finally { server.stop(0); }
    }

    @Test void failedElasticsearchIndexWriteDoesNotBroadcastAnUnindexedFinalRecord() throws Exception {
        Map<String,Map<String,JsonNode>> documents=new ConcurrentHashMap<>(); AtomicBoolean failDocuments=new AtomicBoolean();
        HttpServer server=server(documents,false,failDocuments);
        try {
            server.start(); Path yaml=directory.resolve("es-failure.yaml");
            java.nio.file.Files.writeString(yaml,"schemaVersion: 1\napplication: demo\noutput:\n  directory: '"+directory.resolve("logs-failure").toString().replace("\\","/")+"'\n  search:\n    backend: elasticsearch\n    endpoint: http://127.0.0.1:"+server.getAddress().getPort()+"\nweb:\n  enabled: false\n");
            try(SessionStorage storage=new SessionStorage(io.loglens.config.ConfigLoader.load(yaml),LogClock.system())){
                AtomicInteger broadcasts=new AtomicInteger(); storage.broadcastTo(ignored->broadcasts.incrementAndGet());
                CapturedLog event=new CapturedLog("pending",storage.sessionId(),1,100,100,100,io.loglens.api.LogLevel.ERROR,"demo.Logger","main","failure","failure",List.of(),Map.of(),null,List.of(),false);
                storage.captured(event); failDocuments.set(true);
                assertThrows(StorageException.class,()->storage.processed(ProcessedRecord.from(storage.sessionId(),1,new LogContext(event))));
                assertEquals(0,broadcasts.get()); assertFalse(storage.healthy());
                assertTrue(java.nio.file.Files.readString(storage.directory().resolve("processed-0001.jsonl")).contains("failure"));
            }
        } finally { server.stop(0); }
    }

    private HttpServer server(Map<String,Map<String,JsonNode>> documents,boolean reject) throws Exception {
        return server(documents,reject,new AtomicBoolean());
    }
    private HttpServer server(Map<String,Map<String,JsonNode>> documents,boolean reject,List<JsonNode> searchRequests) throws Exception {
        return server(documents,reject,new AtomicBoolean(),searchRequests,new java.util.concurrent.CopyOnWriteArrayList<>());
    }
    private HttpServer server(Map<String,Map<String,JsonNode>> documents,boolean reject,AtomicBoolean failDocuments) throws Exception {
        return server(documents,reject,failDocuments,new java.util.concurrent.CopyOnWriteArrayList<>(),new java.util.concurrent.CopyOnWriteArrayList<>());
    }
    private HttpServer server(Map<String,Map<String,JsonNode>> documents,boolean reject,List<JsonNode> searchRequests,List<Integer> bulkSizes) throws Exception {
        return server(documents,reject,new AtomicBoolean(),searchRequests,bulkSizes);
    }
    private HttpServer server(Map<String,Map<String,JsonNode>> documents,boolean reject,AtomicBoolean failDocuments,List<JsonNode> searchRequests,List<Integer> bulkSizes) throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            try {
                String path=exchange.getRequestURI().getPath(); String[] parts=path.substring(1).split("/");
                if(reject){respond(exchange,503,"{}");return;}
                if(exchange.getRequestMethod().equals("PUT")&&parts.length==1){documents.computeIfAbsent(parts[0],ignored->new ConcurrentHashMap<>());respond(exchange,200,"{}");return;}
                if(exchange.getRequestMethod().equals("PUT")&&parts.length>=3&&parts[1].equals("_doc")){
                    JsonNode source=JSON.readTree(exchange.getRequestBody());documents.computeIfAbsent(parts[0],ignored->new ConcurrentHashMap<>()).put(parts[2],source);respond(exchange,201,"{}");return;
                }
                if(exchange.getRequestMethod().equals("POST")&&parts.length==1&&parts[0].equals("_bulk")){
                    if(failDocuments.get()){respond(exchange,200,"{\"errors\":true,\"items\":[{\"index\":{\"status\":400,\"error\":{\"type\":\"mock_failure\"}}}]}");return;}
                    String content=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                    String[] lines=content.split("\\n");var items=new ArrayList<Map<String,Object>>();int actions=0;
                    for(int i=0;i+1<lines.length;i+=2){JsonNode metadata=JSON.readTree(lines[i]).path("index");JsonNode source=JSON.readTree(lines[i+1]);String index=metadata.path("_index").asText(),id=metadata.path("_id").asText();documents.computeIfAbsent(index,ignored->new ConcurrentHashMap<>()).put(id,source);items.add(Map.of("index",Map.of("status",201)));actions++;}
                    bulkSizes.add(actions);respond(exchange,200,JSON.writeValueAsString(Map.of("errors",false,"items",items)));return;
                }
                if(exchange.getRequestMethod().equals("POST")&&parts.length>=2&&parts[1].equals("_search")){
                    JsonNode body=JSON.readTree(exchange.getRequestBody());
                    searchRequests.add(body.deepCopy());
                    if(body.path("size").asInt(10)==0){long maximum=documents.getOrDefault(parts[0],Map.of()).values().stream().mapToLong(doc->doc.path("seq").asLong()).max().orElse(0);respond(exchange,200,"{\"aggregations\":{\"max_seq\":{\"value\":"+maximum+"}}}");return;}
                    var docs=new ArrayList<>(documents.getOrDefault(parts[0],Map.of()).values());
                    docs.removeIf(doc->!matches(body.path("query"),doc));
                    docs.sort(java.util.Comparator.comparingLong(doc->doc.path("seq").asLong()));
                    int size=body.path("size").asInt(10);var hits=new ArrayList<Map<String,Object>>();for(JsonNode doc:docs){if(hits.size()>=size)break;hits.add(Map.of("_source",doc));}
                    respond(exchange,200,JSON.writeValueAsString(Map.of("hits",Map.of("hits",hits))));return;
                }
                if(exchange.getRequestMethod().equals("POST")&&parts.length>=2&&parts[1].equals("_count")){
                    respond(exchange,200,JSON.writeValueAsString(Map.of("count",documents.getOrDefault(parts[0],Map.of()).size())));return;
                }
                if(exchange.getRequestMethod().equals("POST")&&parts.length>=2&&parts[1].equals("_delete_by_query")){
                    JsonNode body=JSON.readTree(exchange.getRequestBody());var fields=body.path("query").path("term").fields();
                    if(fields.hasNext()){
                        var field=fields.next();String name=field.getKey(),value=field.getValue().asText();
                        documents.getOrDefault(parts[0],Map.of()).entrySet().removeIf(entry->entry.getValue().path(name).asText().equals(value));
                    }
                    respond(exchange,200,"{\"deleted\":1}");return;
                }
                respond(exchange,200,"{}");
            }catch(Exception failure){respond(exchange,500,"{}");}
        });
        return server;
    }
    private static boolean matches(JsonNode query,JsonNode source){
        if(query.has("bool")){for(JsonNode filter:query.path("bool").path("filter"))if(!matches(filter,source))return false;return true;}
        if(query.has("range")){var fields=query.path("range").fields();if(!fields.hasNext())return true;var field=fields.next();long actual=source.path(field.getKey()).asLong(Long.MIN_VALUE);JsonNode bounds=field.getValue();return (!bounds.has("gt")||actual>bounds.path("gt").asLong())&&(!bounds.has("gte")||actual>=bounds.path("gte").asLong())&&(!bounds.has("lt")||actual<bounds.path("lt").asLong())&&(!bounds.has("lte")||actual<=bounds.path("lte").asLong());}
        if(query.has("term")){var fields=query.path("term").fields();if(!fields.hasNext())return true;var field=fields.next();return source.path(field.getKey()).asText().equals(field.getValue().asText());}
        if(query.has("wildcard")){var fields=query.path("wildcard").fields();if(!fields.hasNext())return true;var field=fields.next();String value=field.getValue().path("value").asText();return wildcard(value,source.path(field.getKey()).asText());}
        return true;
    }
    private static boolean wildcard(String pattern,String value){
        StringBuilder regex=new StringBuilder("^");boolean escaped=false;
        for(char c:pattern.toCharArray()){
            if(escaped){regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));escaped=false;}
            else if(c=='\\')escaped=true;
            else if(c=='*')regex.append(".*");
            else if(c=='?')regex.append('.');
            else regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
        }
        if(escaped)regex.append(java.util.regex.Pattern.quote("\\"));
        return value.matches(regex.append('$').toString());
    }
    private static void respond(com.sun.net.httpserver.HttpExchange exchange,int status,String body){
        try{byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();}catch(Exception ignored){}
    }
}
