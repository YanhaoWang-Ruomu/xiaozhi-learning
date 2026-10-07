package com.ruomu.xiaozhi.observability;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.StatusCode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TracingTest {
 @Test void asyncToolAndSaveShareParentWithoutContent() throws Exception {
  var exporter=InMemorySpanExporter.create();try(var provider=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()){
   AiTelemetry.install(OpenTelemetrySdk.builder().setTracerProvider(provider).build());
   var root=AiTelemetry.start("chat.stream");
   Thread worker=new Thread(()->{try(var scope=root.scope()){AiTelemetry.call("tool.query",()->"secret prompt");AiTelemetry.call("history.save",()->"private answer");}});
   worker.start();worker.join();root.close();root.close();
   var spans=exporter.getFinishedSpanItems();assertEquals(3,spans.size());assertTrue(spans.stream().allMatch(s->s.getTraceId().equals(root.span.getSpanContext().getTraceId())));
   assertEquals(2,spans.stream().filter(s->s.getParentSpanId().equals(root.span.getSpanContext().getSpanId())).count());assertTrue(spans.stream().allMatch(s->s.getAttributes().isEmpty()));
  }finally{AiTelemetry.install(OpenTelemetry.noop());}
 }
 @Test void recordsErrorClassWithoutSensitiveMessage(){var exporter=InMemorySpanExporter.create();try(var provider=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()){
  AiTelemetry.install(OpenTelemetrySdk.builder().setTracerProvider(provider).build());assertThrows(IllegalStateException.class,()->AiTelemetry.call("failure",()->{throw new IllegalStateException("secret");}));
  var s=exporter.getFinishedSpanItems().get(0);assertEquals(StatusCode.ERROR,s.getStatus().getStatusCode());assertFalse(s.toString().contains("secret"));
 }finally{AiTelemetry.install(OpenTelemetry.noop());}}
 @Test void modelCallbackOnAnotherThreadRestoresRequestParent() throws Exception {
  var exporter=InMemorySpanExporter.create();try(var provider=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()){
   AiTelemetry.install(OpenTelemetrySdk.builder().setTracerProvider(provider).build());
   var delegate=org.mockito.Mockito.mock(dev.langchain4j.model.chat.StreamingChatLanguageModel.class);
   var thread=new java.util.concurrent.atomic.AtomicReference<Thread>();
   org.mockito.Mockito.doAnswer(invocation->{var handler=(dev.langchain4j.model.chat.response.StreamingChatResponseHandler)invocation.getArgument(1);var t=new Thread(()->handler.onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse.builder().aiMessage(dev.langchain4j.data.message.AiMessage.from("private")).build()));thread.set(t);t.start();return null;}).when(delegate).chat(org.mockito.ArgumentMatchers.any(dev.langchain4j.model.chat.request.ChatRequest.class),org.mockito.ArgumentMatchers.any(dev.langchain4j.model.chat.response.StreamingChatResponseHandler.class));
   var root=AiTelemetry.start("chat.stream");try(var scope=root.scope()){
    ObservedModels.streaming(delegate).chat(dev.langchain4j.model.chat.request.ChatRequest.builder().messages(dev.langchain4j.data.message.UserMessage.from("private question")).build(),new dev.langchain4j.model.chat.response.StreamingChatResponseHandler(){public void onPartialResponse(String text){}public void onError(Throwable e){throw new AssertionError(e);}public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse response){AiTelemetry.call("tool.query",()->null);}});
   }
   thread.get().join();root.close();var spans=exporter.getFinishedSpanItems();assertEquals(3,spans.size());assertTrue(spans.stream().allMatch(s->s.getTraceId().equals(root.span.getSpanContext().getTraceId())));
   assertTrue(spans.stream().filter(s->s.getName().equals("tool.query")).allMatch(s->s.getParentSpanId().equals(root.span.getSpanContext().getSpanId())));
  }finally{AiTelemetry.install(OpenTelemetry.noop());}
 }
}
