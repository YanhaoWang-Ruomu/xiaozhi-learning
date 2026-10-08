package com.ruomu.xiaozhi.service;

import com.fasterxml.jackson.databind.*;
import com.ruomu.xiaozhi.observability.AiTelemetry;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;

/** Dedicated relevance model. HTTP failure never masquerades as a model ranking. */
@Component
public class DedicatedReranker {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final String model,endpoint,key;
    private final Function<String,String> transport;
    @Autowired
    public DedicatedReranker(@Value("${xiaozhi.rag.reranker.model:gte-rerank-v2}") String model,
            @Value("${xiaozhi.rag.reranker.endpoint:https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank}") String endpoint) {
        this.model=model;this.endpoint=endpoint;this.key=System.getenv("DASHSCOPE_API_KEY");this.transport=this::post;
        validateEndpoint(endpoint);
        if(!Set.of("qwen3-rerank","gte-rerank-v2").contains(model))throw new IllegalArgumentException("Unsupported rerank model");
    }
    DedicatedReranker(String model,Function<String,String> transport){this.model=model;this.transport=transport;this.endpoint="";this.key="";}
    static void validateEndpoint(String endpoint){
        URI uri=URI.create(endpoint);String host=uri.getHost();
        if(!"https".equals(uri.getScheme())||host==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null
                ||!(host.equals("dashscope.aliyuncs.com")||host.equals("dashscope-intl.aliyuncs.com")||host.endsWith(".maas.aliyuncs.com")))
            throw new IllegalArgumentException("Reranker endpoint must be an official HTTPS DashScope endpoint");
    }
    public String model(){return model;}
    public List<HybridKnowledgeService.Candidate> rerank(String query,List<HybridKnowledgeService.Candidate> candidates){
        if(query==null||query.isBlank()||query.length()>500)throw new IllegalArgumentException("Invalid query");
        if(candidates.isEmpty())return List.of();
        if(candidates.size()>12||candidates.stream().map(HybridKnowledgeService.Candidate::index).distinct().count()!=candidates.size()
                ||candidates.stream().anyMatch(c->c.text()==null||c.text().isBlank()||c.text().length()>4000))
            throw new IllegalArgumentException("Invalid candidates");
        return AiTelemetry.call("retrieval.dedicated_rerank",()->{
            try {
                var docs=candidates.stream().map(HybridKnowledgeService.Candidate::text).toList();
                Object request=model.equals("qwen3-rerank")
                    ?Map.of("model",model,"query",query,"documents",docs,"top_n",docs.size())
                    :Map.of("model",model,"input",Map.of("query",query,"documents",docs),"parameters",Map.of("top_n",docs.size(),"return_documents",false));
                String raw=transport.apply(JSON.writeValueAsString(request));
                if(raw==null||raw.length()>64000)throw new IllegalStateException("Invalid reranker response");
                var root=JSON.readTree(raw);
                var results=model.equals("qwen3-rerank")?root.path("results"):root.path("output").path("results");
                if(root.has("code")||!results.isArray()||results.size()!=candidates.size())throw new IllegalStateException("Incomplete reranker result");
                var seen=new HashSet<Integer>();var ranked=new ArrayList<HybridKnowledgeService.Candidate>();
                for(var item:results){
                    if(!item.path("index").isIntegralNumber()||!item.path("index").canConvertToInt()||!item.path("relevance_score").isNumber())
                        throw new IllegalStateException("Invalid reranker score");
                    int i=item.get("index").intValue();double score=item.get("relevance_score").doubleValue();
                    if(i<0||i>=candidates.size()||!seen.add(i)||!Double.isFinite(score)||score<0||score>1)
                        throw new IllegalStateException("Invalid reranker index or score");
                    var c=candidates.get(i);ranked.add(new HybridKnowledgeService.Candidate(c.index(),c.source(),c.text(),c.dense(),c.bm25(),c.rrf(),score));
                }
                ranked.sort(Comparator.comparingDouble(HybridKnowledgeService.Candidate::relevance).reversed().thenComparingInt(HybridKnowledgeService.Candidate::index));
                return List.copyOf(ranked);
            }catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("Invalid reranker response");}
        });
    }
    private String post(String body){
        if(key==null||key.isBlank())throw new IllegalStateException("Reranker API key missing");
        try {
            var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
            var request=HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(25))
                    .header("Authorization","Bearer "+key).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)throw new IllegalStateException("RERANK_HTTP_"+response.statusCode());
            return response.body();
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("RERANK_INTERRUPTED");}
        catch(java.io.IOException e){throw new IllegalStateException("RERANK_NETWORK_ERROR");}
    }
}
