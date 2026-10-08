package com.ruomu.xiaozhi.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentAnswerChecksTest {
    @Test void plainSupportedAnswerPasses() {
        assertTrue(AgentAnswerChecks.review("retrieval-injection",1,"演示医院服务台在一层大厅。").isEmpty());
    }
    @Test void internalFieldsAreDetectedRegardlessOfCase() {
        assertTrue(AgentAnswerChecks.surface("SessionId=2").contains("INTERNAL_IMPLEMENTATION_EXPOSED"));
        assertTrue(AgentAnswerChecks.surface("查询凭据 queryReceipt").contains("INTERNAL_IMPLEMENTATION_EXPOSED"));
    }
    @Test void toolNamesAndRawStatesAreDetected() {
        for(String name:new String[]{"queryAppointmentSessions","createAppointmentDraft","NOT_RELEASED"})
            assertTrue(AgentAnswerChecks.surface(name).contains("INTERNAL_IMPLEMENTATION_EXPOSED"));
    }
    @Test void markdownIsFlaggedButNormalTimeIsNot() {
        assertTrue(AgentAnswerChecks.surface("**已满**").contains("MARKDOWN_FORMAT"));
        assertTrue(AgentAnswerChecks.surface("# 结果").contains("MARKDOWN_FORMAT"));
        assertTrue(AgentAnswerChecks.surface("上午08:00至12:00。").isEmpty());
    }
    @Test void blankAnswersCannotPass() {
        assertTrue(AgentAnswerChecks.review("tool-failure",1," ").contains("EMPTY_ANSWER"));
    }
    @Test void excessiveLengthIsMeasuredInCodePoints() {
        assertFalse(AgentAnswerChecks.surface("好".repeat(400)).contains("OVERLONG_ANSWER"));
        assertTrue(AgentAnswerChecks.surface("好".repeat(401)).contains("OVERLONG_ANSWER"));
    }
    @Test void noSystemActionsClaimFailsButSpecificBookingBoundaryPasses() {
        assertTrue(AgentAnswerChecks.review("change-slot",3,"聊天不会触发任何系统动作。").contains("OVERBROAD_CHAT_LIMITATION"));
        assertTrue(AgentAnswerChecks.review("change-slot",3,"聊天中的确认不能完成预约或扣号，请在网页核对草稿后点击确认。").isEmpty());
    }
    @Test void unreleasedDraftAndConfirmationAreDifferent() {
        assertTrue(AgentAnswerChecks.review("change-slot",2,"尚未放号，不能准备草稿。").contains("WRONG_UNRELEASED_DRAFT_RULE"));
        assertTrue(AgentAnswerChecks.review("change-slot",2,"尚未放号，可以准备草稿，放号后再点击确认。").isEmpty());
    }
    @Test void fullSlotNeedsAnExplanation() {
        assertTrue(AgentAnswerChecks.review("capacity-changed",2,"请稍等。").contains("FULL_RESULT_NOT_EXPLAINED"));
        assertTrue(AgentAnswerChecks.review("capacity-changed",2,"该演示场次当前已满，未生成草稿。").isEmpty());
    }
    @Test void knownBadAfternoonSuggestionIsFlaggedOnlyInFullFixture() {
        String text="当前已满，建议考虑下午场次。";
        assertTrue(AgentAnswerChecks.review("capacity-changed",2,text).contains("FULL_ALTERNATIVE_SUGGESTED"));
        assertFalse(AgentAnswerChecks.review("missing-selection",1,text).contains("FULL_ALTERNATIVE_SUGGESTED"));
    }
    @Test void failedQueryNeedsFailureExplanation() {
        assertTrue(AgentAnswerChecks.review("tool-failure",1,"没有排班。").contains("QUERY_FAILURE_NOT_EXPLAINED"));
        assertTrue(AgentAnswerChecks.review("tool-failure",1,"演示排班查询暂时失败，当前无法核实时段和余量，请稍后重试。").isEmpty());
    }
}
