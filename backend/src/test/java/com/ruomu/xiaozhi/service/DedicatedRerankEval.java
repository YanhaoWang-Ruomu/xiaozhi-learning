package com.ruomu.xiaozhi.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruomu.xiaozhi.config.*;
import com.ruomu.xiaozhi.dto.KnowledgeSearchResponse.Match;
import com.ruomu.xiaozhi.eval.RetrievalEval;
import org.springframework.core.env.StandardEnvironment;
import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import java.time.Instant;import java.util.*;

/** Paired ranking evaluation with shared RRF candidates and unchanged baseline eligibility. */
public final class DedicatedRerankEval {
    private static void checkPayload(String resource,String expected)throws Exception{
        try(var stream=DedicatedRerankEval.class.getResourceAsStream(resource)){
            if(stream==null||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes())).equals(expected))throw new IllegalArgumentException("Unreviewed corpus: "+resource);
        }
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("dataset output");
        var json=new ObjectMapper();Path dataset=Path.of(args[0]),out=Path.of(args[1]);Files.createDirectory(out);
        var cases=new ArrayList<RetrievalEval.Case>();
        for(String line:Files.readAllLines(dataset,StandardCharsets.UTF_8))if(!line.isBlank())cases.add(json.readValue(line,RetrievalEval.Case.class));
        if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dataset))).equals("e0e8eae18a67dde3cd58ed6cedcf9db123187681943687cdde7a4413fa1784b7"))throw new IllegalArgumentException("Unreviewed dataset");
        checkPayload("/knowledge/catalog.txt","e40be93e9b2f6e6bd9902148737955ad2bf89f715d0061b949474f9500134ab7");
        checkPayload("/knowledge/hospital-info.txt","cdd1e8bae69b9f29e0a5d6d442b074eba99fc5ff9e168bb988fd20f94eb3684a");
        checkPayload("/knowledge/appointment-guide.txt","e595a77a8fb147b5c73e4f53e94374b73eb91e7b1e01e950eb73cb741b5d82b2");
        checkPayload("/knowledge/knowledge-scope.txt","8b043745adda1ed62b6c41d330d88c63162a36b8351f1df74c5a940d7f31aa3f");
        checkPayload("/knowledge/visit-handbook.pdf","a591069422150969561d839805309c7d9bfdc9dbf8c62ccd2e7161848b169fb3");
        var docs=new KnowledgeDocumentService();var corpus=docs.preview().chunks();
        for(var c:cases)for(var e:c.evidence())if(corpus.stream().noneMatch(chunk->chunk.source().equals(e.source())&&CorrectiveRagEval.normalized(chunk.text()).contains(CorrectiveRagEval.normalized(e.quote()))))throw new IllegalArgumentException("Missing gold "+c.id());
        DashScopeNetworkConfig.dashScopeNetworkPolicy(new StandardEnvironment()).postProcessBeanFactory(null);
        var dense=new KnowledgeSearchService(docs,new KnowledgeEmbeddingConfig().knowledgeEmbeddingModel(),new PineconeClient(json,System.getenv("PINECONE_API_KEY"),System.getenv("PINECONE_INDEX_HOST")));
        var hybrid=new HybridKnowledgeService(docs,dense,new AiConfig().qwenChatModel(),"hybrid");
        String model=System.getenv().getOrDefault("XIAOZHI_RERANK_MODEL","gte-rerank-v2");
        String endpoint=System.getenv().getOrDefault("XIAOZHI_RERANK_ENDPOINT","https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank");
        var reranker=new DedicatedReranker(model,endpoint);var rows=new ArrayList<Map<String,Object>>();
        for(var c:cases){
            var row=new LinkedHashMap<String,Object>();row.put("id",c.id());row.put("query",c.query());row.put("gold",c.evidence());long start=System.nanoTime();
            try{
                var base=hybrid.search(c.query());row.put("baselineMs",(System.nanoTime()-start)/1000000);row.put("baseline",base);
                var eligible=base.ranked().stream().filter(x->x.dense()>=.80||(x.bm25()>0&&x.relevance()>=.52)).map(HybridKnowledgeService.Candidate::index).collect(java.util.stream.Collectors.toSet());
                start=System.nanoTime();var ranking=reranker.rerank(c.query(),base.ranked());row.put("rerankMs",(System.nanoTime()-start)/1000000);
                var accepted=ranking.stream().filter(x->eligible.contains(x.index())).limit(2).map(x->new Match(x.index(),x.source(),x.relevance(),x.text())).toList();
                row.put("ranking",ranking);row.put("accepted",accepted);
                row.put("baselineRecall",CorrectiveRagEval.recall(c,base.accepted(),corpus));row.put("rerankRecall",CorrectiveRagEval.recall(c,accepted,corpus));
                row.put("baselineFalseAccept",c.evidence().isEmpty()&&!base.accepted().isEmpty());row.put("rerankFalseAccept",c.evidence().isEmpty()&&!accepted.isEmpty());row.put("status","OK");
            }catch(RuntimeException e){row.put("status","ERROR");row.put("errorType",e.getClass().getSimpleName());String message=e.getMessage();row.put("code",message!=null&&message.matches("RERANK_[A-Z0-9_]+")?message:"RERANK_OR_RETRIEVAL_UNAVAILABLE");}
            rows.add(row);Files.writeString(out.resolve("trials.jsonl"),json.writeValueAsString(row)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            System.out.println("RERANK_EVAL "+c.id()+" "+row.get("status"));if("ERROR".equals(row.get("status")))break;
        }
        var positive=rows.stream().filter(r->!((List<?>)r.get("gold")).isEmpty()).toList();
        var summary=new LinkedHashMap<String,Object>();summary.put("at",Instant.now().toString());summary.put("model",model);summary.put("endpoint",endpoint);
        summary.put("datasetSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dataset))));
        summary.put("planned",cases.size());summary.put("recorded",rows.size());summary.put("positiveCount",positive.size());
        for(String prefix:List.of("baseline","rerank")){
            summary.put(prefix+"Recall",positive.stream().mapToDouble(r->((Number)r.getOrDefault(prefix+"Recall",0)).doubleValue()).average().orElse(0));
            summary.put(prefix+"FalseAccepts",rows.stream().filter(r->Boolean.TRUE.equals(r.get(prefix+"FalseAccept"))).count());
        }
        long failures=rows.stream().filter(r->!"OK".equals(r.get("status"))).count();summary.put("failures",failures);
        summary.put("meanRerankMs",rows.stream().filter(r->r.containsKey("rerankMs")).mapToLong(r->((Number)r.get("rerankMs")).longValue()).average().orElse(0));
        boolean gate=CorrectiveRagEval.releaseGate(((Number)summary.get("baselineRecall")).doubleValue(),((Number)summary.get("rerankRecall")).doubleValue(),
            ((Number)summary.get("baselineFalseAccepts")).longValue(),((Number)summary.get("rerankFalseAccepts")).longValue(),failures,cases.size(),rows.size());
        summary.put("releaseGate",gate?"PASS_FIXED_SAMPLE_ONLY":"FAIL_DO_NOT_ENABLE_BY_DEFAULT");
        summary.put("limits","Read-only real Pinecone/dedicated model. Same candidates and eligibility; only order changes. Relative model scores are not calibrated probabilities. Small reused corpus, not held-out answer quality.");
        json.writerWithDefaultPrettyPrinter().writeValue(out.resolve("summary.json").toFile(),summary);
        System.out.println("RERANK_REPORT="+out);System.exit(gate?0:2);
    }
}
