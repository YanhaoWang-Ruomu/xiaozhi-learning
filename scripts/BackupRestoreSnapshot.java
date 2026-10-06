import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.mongodb.client.*;
import com.ruomu.xiaozhi.service.ConversationHistoryService;
import com.ruomu.xiaozhi.store.MongoChatMemoryStore;
import com.ruomu.xiaozhi.dto.ChatResponse;
import dev.langchain4j.data.message.*;
import org.bson.Document;
import org.bson.json.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

public class BackupRestoreSnapshot {
 static final ObjectMapper JSON=new ObjectMapper();
 static final JsonWriterSettings BSON=JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build();
 static String hash(String s)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}
 static JsonNode sorted(JsonNode node){
  if(node.isObject()){ObjectNode out=JSON.createObjectNode();TreeSet<String> keys=new TreeSet<>();node.fieldNames().forEachRemaining(keys::add);keys.forEach(k->out.set(k,sorted(node.get(k))));return out;}
  if(node.isArray()){ArrayNode out=JSON.createArrayNode();node.forEach(v->out.add(sorted(v)));return out;}
  return node;
 }
 static String stable(Object value)throws Exception{return sorted(JSON.valueToTree(value)).toString();}
 static List<String> indexes(Iterable<Document> docs)throws Exception{
  List<Document> ordered=new ArrayList<>();
  for(Document original:docs){
   Document index=new Document(original);Document keys=index.get("key",Document.class);
   List<Document> keyOrder=new ArrayList<>();keys.forEach((field,direction)->keyOrder.add(new Document("field",field).append("direction",direction)));
   index.put("key",keyOrder);ordered.add(index);
  }
  return documents(ordered);
 }
 static List<String> documents(Iterable<Document> docs)throws Exception{
  List<String> rows=new ArrayList<>();for(Document d:docs)rows.add(sorted(JSON.readTree(d.toJson(BSON))).toString());Collections.sort(rows);return rows;
 }
 public static void main(String[] args)throws Exception{
  String mode=args[0];int sql=Integer.parseInt(args[1]),mongo=Integer.parseInt(args[2]);Path file=Path.of(args[3]);
  if(!Set.of(13307,13308).contains(sql)||!Set.of(27317,27318).contains(mongo))throw new IllegalArgumentException("Only isolated DB ports allowed");
  try(MongoClient client=MongoClients.create("mongodb://127.0.0.1:"+mongo+"/?serverSelectionTimeoutMS=5000")){
   var db=client.getDatabase("xiaozhi_learning");
   if(mode.equals("seed-memory")){
    JsonNode fixture=JSON.readTree(Files.readString(file));
    MongoTemplate template=new MongoTemplate(client,"xiaozhi_learning");
    var history=new ConversationHistoryService(template);
    String id=fixture.path("conversationId").asText(),owner=fixture.path("userId").asText();
    var key=history.begin(id,"备份演练中文问题",owner);
    history.complete(key,new ChatResponse("备份演练固定回复，不调用模型。",List.of(),
     List.of(new ChatResponse.Source("knowledge/backup-fixture.txt",0,"隔离恢复测试资料"))));
    new MongoChatMemoryStore(template).updateMessages(id,List.of(UserMessage.from("备份演练中文问题"),AiMessage.from("备份演练固定回复，不调用模型。")));
    System.out.println("FIXTURE_HISTORY_AND_MEMORY_OK");return;
   }
   if(!mode.equals("snapshot"))throw new IllegalArgumentException("Unknown mode");
   Map<String,Object> result=new TreeMap<>(),collections=new TreeMap<>(),tables=new TreeMap<>(),rawDdl=new TreeMap<>();
   for(Document collection:db.listCollections()){
    String name=collection.getString("name");if(name.startsWith("system."))continue;
    var rows=documents(db.getCollection(name).find());
    collections.put(name,Map.of("count",rows.size(),"dataHash",hash(stable(rows)),
     "indexHash",hash(stable(indexes(db.getCollection(name).listIndexes()))),
     "optionsHash",hash(stable(collection.get("options",Document.class)))));
   }
   try(Connection conn=DriverManager.getConnection("jdbc:mysql://127.0.0.1:"+sql+"/xiaozhi_learning?sslMode=DISABLED&characterEncoding=UTF-8&connectionTimeZone=Asia/Shanghai","root","")){
    List<String> names=new ArrayList<>();
    try(var st=conn.createStatement();var rs=st.executeQuery("SHOW TABLES")){while(rs.next())names.add(rs.getString(1));}
    for(String name:names){
     if(!name.matches("[a-z0-9_]+"))throw new IllegalStateException("Unexpected table identifier");
     List<String> rows=new ArrayList<>();
     try(var st=conn.createStatement();var rs=st.executeQuery("SELECT * FROM "+name)){
      var meta=rs.getMetaData();while(rs.next()){Map<String,Object> row=new TreeMap<>();for(int i=1;i<=meta.getColumnCount();i++)row.put(meta.getColumnName(i),rs.getString(i));rows.add(stable(row));}
     }
     Collections.sort(rows);String ddl;
     try(var st=conn.createStatement();var rs=st.executeQuery("SHOW CREATE TABLE "+name)){rs.next();ddl=rs.getString(2);}
     rawDdl.put(name,ddl);
     // mysqldump restore can make an inherited charset explicit. Verify column
     // semantics separately before normalizing only this redundant spelling.
     List<String> columns=new ArrayList<>();
     String columnSql="SELECT COLUMN_NAME,ORDINAL_POSITION,COLUMN_DEFAULT,IS_NULLABLE,DATA_TYPE,CHARACTER_MAXIMUM_LENGTH,NUMERIC_PRECISION,NUMERIC_SCALE,DATETIME_PRECISION,CHARACTER_SET_NAME,COLLATION_NAME,COLUMN_TYPE,COLUMN_KEY,EXTRA,GENERATION_EXPRESSION FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? ORDER BY ORDINAL_POSITION";
     try(var st=conn.prepareStatement(columnSql)){
      st.setString(1,name);
      try(var rs=st.executeQuery()){var meta=rs.getMetaData();while(rs.next()){Map<String,Object> row=new TreeMap<>();for(int i=1;i<=meta.getColumnCount();i++)row.put(meta.getColumnName(i),rs.getString(i));columns.add(stable(row));}}
     }
     String normalizedDdl=ddl.replace(" CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin"," COLLATE utf8mb4_0900_bin");
     tables.put(name,Map.of("count",rows.size(),"dataHash",hash(stable(rows)),"schemaHash",hash(normalizedDdl),"columnsHash",hash(stable(columns))));
    }
   }
   result.put("mysql",tables);result.put("mongo",collections);
   Files.writeString(Path.of(file.toString()+".ddl.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(sorted(JSON.valueToTree(rawDdl))),StandardCharsets.UTF_8);
   Files.writeString(file,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(sorted(JSON.valueToTree(result))),StandardCharsets.UTF_8);
   System.out.println("SNAPSHOT_OK tables="+tables.size()+" collections="+collections.size());
  }
 }
}
