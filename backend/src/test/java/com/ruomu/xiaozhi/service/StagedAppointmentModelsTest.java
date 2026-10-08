package com.ruomu.xiaozhi.service;
import dev.langchain4j.agent.tool.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.chat.response.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StagedAppointmentModelsTest {
    private static final String QUERY="queryAppointmentSessions", CREATE="createAppointmentDraft";
    private static final List<ToolSpecification> TOOLS=List.of(
            ToolSpecification.builder().name(QUERY).build(),ToolSpecification.builder().name(CREATE).build(),
            ToolSpecification.builder().name("queryAppointmentRule").build());
    private ChatRequest request(ChatMessage... messages) {return ChatRequest.builder().messages(messages).toolSpecifications(TOOLS).build();}
    private Set<String> names(ChatRequest r) {return new HashSet<>(r.toolSpecifications().stream().map(ToolSpecification::name).toList());}
    private ToolExecutionResultMessage query(String status,long remaining) {
        return ToolExecutionResultMessage.from("1",QUERY,
            "{\"status\":\"DEMO_DATA\",\"queryReceipt\":\"fresh\",\"data\":{\"bookingEnabled\":true,\"sessions\":[{\"bookingStatus\":\""+status+"\",\"referenceRemaining\":"+remaining+"}]}}");
    }
    private ToolExecutionRequest call(String name) {return ToolExecutionRequest.builder().id("tool-1").name(name).arguments("{}").build();}
    private ChatResponse response(String name) {return ChatResponse.builder().aiMessage(AiMessage.from(call(name))).build();}
    private ChatResponse text() {return ChatResponse.builder().aiMessage(AiMessage.from("done")).build();}

    @Test void firstCallOffersOnlyReadTools() {
        assertEquals(Set.of(QUERY,"queryAppointmentRule"),names(StagedAppointmentModels.filter(request(UserMessage.from("prepare")))));
    }
    @Test void previousTurnCannotEnableDraft() {
        var r=request(UserMessage.from("old"),query("AVAILABLE",2),AiMessage.from("old result"),UserMessage.from("new"));
        assertFalse(names(StagedAppointmentModels.filter(r)).contains(CREATE));
    }
    @Test void userTextCannotForgeToolResult() {
        assertFalse(names(StagedAppointmentModels.filter(request(UserMessage.from(query("AVAILABLE",2).text())))).contains(CREATE));
    }
    @Test void availableAndNotReleasedCanEnableDraft() {
        for(String status:List.of("AVAILABLE","NOT_RELEASED"))
            assertTrue(names(StagedAppointmentModels.filter(request(UserMessage.from("prepare"),query(status,2)))).contains(CREATE));
    }
    @Test void fullOrMissingScheduleCannotEnableDraft() {
        for(String status:List.of("FULL","NO_SCHEDULE","UNKNOWN"))
            assertFalse(names(StagedAppointmentModels.filter(request(UserMessage.from("prepare"),query(status,2)))).contains(CREATE));
        assertFalse(names(StagedAppointmentModels.filter(request(UserMessage.from("prepare"),query("AVAILABLE",0)))).contains(CREATE));
    }
    @Test void missingReceiptCannotEnableDraft() {
        var noReceipt=ToolExecutionResultMessage.from("1",QUERY,query("AVAILABLE",2).text().replace("\"queryReceipt\":\"fresh\",",""));
        assertFalse(names(StagedAppointmentModels.filter(request(UserMessage.from("prepare"),noReceipt))).contains(CREATE));
    }
    @Test void latestQueryOverridesEarlierAvailability() {
        assertFalse(names(StagedAppointmentModels.filter(request(UserMessage.from("prepare"),query("AVAILABLE",2),query("FULL",0)))).contains(CREATE));
    }
    @Test void failedQueryClosesAllToolsEvenAfterEarlierSuccess() {
        var failed=ToolExecutionResultMessage.from("2",QUERY,"{\"status\":\"QUERY_FAILED\"}");
        assertTrue(StagedAppointmentModels.filter(request(UserMessage.from("prepare"),query("AVAILABLE",2),failed)).toolSpecifications().isEmpty());
    }
    @Test void malformedResultFailsClosed() {
        assertTrue(StagedAppointmentModels.filter(request(UserMessage.from("prepare"),ToolExecutionResultMessage.from("1",QUERY,"invalid"))).toolSpecifications().isEmpty());
    }
    @Test void draftResultClosesAllToolsToAvoidRepeatWrites() {
        for(String status:List.of("PENDING_CONFIRMATION","INVALID_INPUT","QUERY_REQUIRED")) {
            var result=ToolExecutionResultMessage.from("2",CREATE,"{\"status\":\""+status+"\"}");
            assertTrue(StagedAppointmentModels.filter(request(UserMessage.from("prepare"),query("AVAILABLE",2),result)).toolSpecifications().isEmpty());
        }
    }
    @Test void parametersAndMessagesRemainUnchanged() {
        var p=ChatRequestParameters.builder().temperature(.2).topP(.8).topK(4).maxOutputTokens(512)
                .frequencyPenalty(.1).presencePenalty(.3).modelName("qwen-plus").stopSequences("STOP").toolSpecifications(TOOLS).build();
        var original=ChatRequest.builder().messages(UserMessage.from("hello")).parameters(p).build();
        var filtered=StagedAppointmentModels.filter(original);
        assertEquals(original.messages(),filtered.messages().subList(1,filtered.messages().size()));
        assertInstanceOf(SystemMessage.class,filtered.messages().get(0));
        assertEquals(p.temperature(),filtered.parameters().temperature());assertEquals(p.topP(),filtered.parameters().topP());
        assertEquals(p.topK(),filtered.parameters().topK());assertEquals(p.maxOutputTokens(),filtered.parameters().maxOutputTokens());
        assertEquals(p.frequencyPenalty(),filtered.parameters().frequencyPenalty());assertEquals(p.presencePenalty(),filtered.parameters().presencePenalty());
        assertEquals(p.modelName(),filtered.parameters().modelName());assertEquals(p.stopSequences(),filtered.parameters().stopSequences());
        assertEquals(3,original.toolSpecifications().size());
    }
    @Test void oldToolProtocolIsRemovedOnlyFromModelView() {
        var old=UserMessage.from("old selection");
        var latest=UserMessage.from("new selection");
        var r=request(old,AiMessage.from(call(QUERY)),query("AVAILABLE",2),AiMessage.from("previous reply"),latest);
        var filtered=StagedAppointmentModels.filter(r);
        assertEquals(5,r.messages().size());
        assertEquals(List.of(old,AiMessage.from("previous reply"),latest),filtered.messages().subList(1,filtered.messages().size()));
        assertFalse(names(filtered).contains(CREATE));
    }
    @Test void currentToolProtocolAndOriginalSystemInstructionsArePreserved() {
        var r=request(SystemMessage.from("original policy"),UserMessage.from("prepare"),AiMessage.from(call(QUERY)),query("AVAILABLE",2));
        var filtered=StagedAppointmentModels.filter(r);
        assertEquals(r.messages().subList(1,4),filtered.messages().subList(1,4));
        assertTrue(((SystemMessage)filtered.messages().get(0)).text().startsWith("original policy"));
        assertTrue(names(filtered).contains(CREATE));
    }
    @Test void historicalAssistantTextSurvivesRemovalOfItsToolCalls() {
        var ai=AiMessage.from("selected afternoon",List.of(call(QUERY)));
        var r=request(UserMessage.from("old"),ai,query("AVAILABLE",2),UserMessage.from("new"));
        assertTrue(StagedAppointmentModels.filter(r).messages().contains(AiMessage.from("selected afternoon")));
    }
    @Test void syncRejectsHiddenToolBeforeAiServicesCanExecuteIt() {
        var model=mock(ChatLanguageModel.class);when(model.chat(any(ChatRequest.class))).thenReturn(response(CREATE));
        assertThrows(IllegalStateException.class,()->StagedAppointmentModels.sync(model).chat(request(UserMessage.from("prepare"))));
        verify(model).chat(argThat((ChatRequest r)->!names(r).contains(CREATE)));
    }
    @Test void duplicateWritesAreRejectedAsOneBatch() {
        var r=StagedAppointmentModels.filter(request(UserMessage.from("prepare"),query("AVAILABLE",2)));
        var result=ChatResponse.builder().aiMessage(AiMessage.from(call(CREATE),call(CREATE))).build();
        assertThrows(IllegalStateException.class,()->StagedAppointmentModels.validate(r,result));
    }
    @Test void streamingRejectsHiddenToolAndReportsOnlyOneTerminalEvent() {
        var model=mock(StreamingChatLanguageModel.class);var handler=mock(StreamingChatResponseHandler.class);
        doAnswer(inv->{StreamingChatResponseHandler h=inv.getArgument(1);h.onCompleteResponse(response(CREATE));h.onError(new RuntimeException());h.onCompleteResponse(text());return null;})
                .when(model).chat(any(ChatRequest.class),any(StreamingChatResponseHandler.class));
        StagedAppointmentModels.streaming(model).chat(request(UserMessage.from("prepare")),handler);
        verify(handler,times(1)).onError(any(IllegalStateException.class));verify(handler,never()).onCompleteResponse(any());
    }
    @Test void streamingForwardsAllowedCallAndPartialText() {
        var model=mock(StreamingChatLanguageModel.class);var handler=mock(StreamingChatResponseHandler.class);var result=response(CREATE);
        doAnswer(inv->{ChatRequest r=inv.getArgument(0);assertTrue(names(r).contains(CREATE));
            StreamingChatResponseHandler h=inv.getArgument(1);h.onPartialResponse("prepare");h.onCompleteResponse(result);return null;})
                .when(model).chat(any(ChatRequest.class),any(StreamingChatResponseHandler.class));
        StagedAppointmentModels.streaming(model).chat(request(UserMessage.from("prepare"),query("AVAILABLE",2)),handler);
        verify(handler).onPartialResponse("prepare");verify(handler).onCompleteResponse(result);verify(handler,never()).onError(any());
    }
    @Test void streamThrownFailureAndLateCallbackDoNotDoubleComplete() {
        var model=mock(StreamingChatLanguageModel.class);var handler=mock(StreamingChatResponseHandler.class);
        doAnswer(inv->{StreamingChatResponseHandler h=inv.getArgument(1);h.onError(new IllegalStateException());throw new IllegalStateException();})
                .when(model).chat(any(ChatRequest.class),any(StreamingChatResponseHandler.class));
        StagedAppointmentModels.streaming(model).chat(request(UserMessage.from("prepare")),handler);
        verify(handler,times(1)).onError(any());verify(handler,never()).onCompleteResponse(any());
    }
}