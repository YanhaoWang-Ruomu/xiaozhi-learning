package com.ruomu.xiaozhi.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruomu.xiaozhi.config.*;import com.ruomu.xiaozhi.dto.KnowledgeSearchResponse.Match;
import org.springframework.core.env.StandardEnvironment;
import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.time.Instant;import java.util.*;import java.util.concurrent.atomic.AtomicInteger;
public final class EvidenceChallengeEval {
    public record Case(String id,String query,List<Match> first,List<Match> second,String status,List<Integer> required){}
    public static void main(String[] args)throws Exception{
        var json=new ObjectMapper();Path dataset=Path.of(args[0]),out=Path.of(args[1]);Files.createDirectory(out);
        var cases=Arrays.asList(json.readValue(Files.readString(dataset,StandardCharsets.UTF_8),Case[].class));
        DashScopeNetworkConfig.dashScopeNetworkPolicy(new StandardEnvironment()).postProcessBeanFactory(null);
        var model=new AiConfig().qwenChatModel();var rows=new ArrayList<Map<String,Object>>();
        for(var c:cases){
            var calls=new AtomicInteger();var raw=new ArrayList<String>();var providerFailed=new java.util.concurrent.atomic.AtomicBoolean();
            var service=new CorrectiveKnowledgeService(q->calls.getAndIncrement()==0?c.first():c.second(),p->{try{String answer=model.chat(p);raw.add(answer);return answer;}catch(RuntimeException e){providerFailed.set(true);throw e;}});
            service.setExtractive(true);var r=service.search(c.query());
            boolean passed=c.status().equals(r.status())&&r.accepted().stream().map(Match::index).toList().containsAll(c.required())&&r.attempts().size()<=2;
            var row=new LinkedHashMap<String,Object>();row.put("case",c);row.put("actual",r);row.put("rawJudgments",raw);row.put("passed",passed);rows.add(row);
            Files.writeString(out.resolve("trials.jsonl"),json.writeValueAsString(row)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            System.out.println(c.id()+" "+r.status()+" "+passed);
            if(providerFailed.get())break;
        }
        long passed=rows.stream().filter(r->Boolean.TRUE.equals(r.get("passed"))).count();
        json.writerWithDefaultPrettyPrinter().writeValue(out.resolve("summary.json").toFile(),Map.of("at",Instant.now().toString(),"planned",cases.size(),"recorded",rows.size(),"passed",passed,
            "datasetSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dataset))),
            "mode","LIVE_QWEN_SYNTHETIC_CONTROLLED_EVIDENCE","limits","New fictional evidence; no Pinecone or business databases. Fixed reference labels; review borderline capability questions separately. Extractive quotes establish provenance, not truth or instruction safety."));
        System.exit(passed==cases.size()?0:2);
    }
}
