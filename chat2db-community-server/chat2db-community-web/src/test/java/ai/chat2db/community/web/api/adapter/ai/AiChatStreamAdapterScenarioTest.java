package ai.chat2db.community.web.api.adapter.ai;

import ai.chat2db.community.domain.api.model.ai.AiChatMessage;
import ai.chat2db.community.domain.api.model.ai.AiChatSession;
import ai.chat2db.community.domain.api.model.ai.AiContextReferenceSnapshot;
import ai.chat2db.community.domain.api.model.request.ai.AiChatMessageAddRequest;
import ai.chat2db.community.domain.api.service.ai.IAiChatHistoryService;
import ai.chat2db.community.web.api.model.request.ai.ChatRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiChatStreamAdapterScenarioTest {

    @Test
    void resolvesSqlScenarioPromptsFromQuestionType() {
        assertPromptContains("SQL_EXPLAIN", "SQL Explanation Mode");
        assertPromptContains("SQL_OPTIMIZER", "SQL Optimization Mode");
        assertPromptContains("SQL_DEBUG", "SQL Diagnosis Mode");
        assertPromptContains("SQL_DEBUG_CHAIN", "SQL Diagnosis Mode");
        assertPromptContains("SQL_2_SQL", "SQL Dialect Conversion Mode");
    }

    @Test
    void ordinaryAndUnknownQuestionTypesDoNotAddSqlScenarioPrompt() {
        assertPromptIsEmpty("ORDINARY_CHAT");
        assertPromptIsEmpty("UNKNOWN_SCENARIO");
        assertPromptIsEmpty(null);
    }

    @Test
    void persistHistoryDefaultsToTrueAndCanBeDisabledForOneShotScenarios() {
        ChatRequest defaultRequest = new ChatRequest();
        assertTrue(AiChatStreamAdapter.shouldPersistHistory(defaultRequest));

        ChatRequest oneShotRequest = new ChatRequest();
        oneShotRequest.setPersistHistory(false);
        assertFalse(AiChatStreamAdapter.shouldPersistHistory(oneShotRequest));
    }

    @Test
    void sensitiveLogTextIsReducedToLengthOnly() {
        String secretPrompt = "private context and customer values";

        assertEquals(secretPrompt.length(), AiChatStreamAdapter.textLength(secretPrompt));
        assertEquals(0, AiChatStreamAdapter.textLength(null));
    }

    @Test
    void persistsServerResolvedContextSnapshotWithUserMessage() throws Exception {
        RecordingHistoryService historyService = new RecordingHistoryService();
        AiChatStreamAdapter adapter = adapter(historyService);
        ChatRequest request = existingSessionRequest();
        AiContextReferenceSnapshot reference = new AiContextReferenceSnapshot();
        reference.setProvider("catalog");
        reference.setId("186");
        reference.setType("term");
        reference.setLabel("product family");
        reference.setDescription("selected product family");

        invokePrepareSession(adapter, request, List.of(reference));

        assertEquals(1, historyService.addedMessages.size());
        AiContextReferenceSnapshot persisted = historyService.addedMessages.get(0).getContextReferences().get(0);
        assertEquals("catalog", persisted.getProvider());
        assertEquals("186", persisted.getId());
        assertEquals("term", persisted.getType());
        assertEquals("product family", persisted.getLabel());
        assertEquals("selected product family", persisted.getDescription());
    }

    @Test
    void doesNotInventContextWhenResolvedSnapshotIsEmpty() throws Exception {
        RecordingHistoryService historyService = new RecordingHistoryService();

        invokePrepareSession(adapter(historyService), existingSessionRequest(), List.of());

        assertEquals(1, historyService.addedMessages.size());
        assertTrue(historyService.addedMessages.get(0).getContextReferences().isEmpty());
    }

    private void assertPromptContains(String questionType, String expected) {
        ChatRequest request = new ChatRequest();
        request.setQuestionType(questionType);
        assertTrue(AiChatStreamAdapter.buildQuestionTypePrompt(request).contains(expected));
    }

    private void assertPromptIsEmpty(String questionType) {
        ChatRequest request = new ChatRequest();
        request.setQuestionType(questionType);
        assertTrue(AiChatStreamAdapter.buildQuestionTypePrompt(request).isEmpty());
    }

    private AiChatStreamAdapter adapter(IAiChatHistoryService historyService) {
        return new AiChatStreamAdapter(null, null, new AiToolAdapter(null, null), historyService,
                null, null, null, null, null);
    }

    private ChatRequest existingSessionRequest() {
        ChatRequest request = new ChatRequest();
        request.setSessionId("session-1");
        request.setInput("查询销量");
        return request;
    }

    private void invokePrepareSession(AiChatStreamAdapter adapter, ChatRequest request,
                                      List<AiContextReferenceSnapshot> contextReferences) throws Exception {
        Method method = AiChatStreamAdapter.class.getDeclaredMethod(
                "prepareSession", ChatRequest.class, Long.class, List.class);
        method.setAccessible(true);
        method.invoke(adapter, request, 35L, contextReferences);
    }

    private static class RecordingHistoryService implements IAiChatHistoryService {
        private final List<AiChatMessageAddRequest> addedMessages = new java.util.ArrayList<>();

        @Override
        public AiChatSession createSession(Long userId, String firstMessage) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AiChatMessage addMessage(AiChatMessageAddRequest request) {
            addedMessages.add(request);
            return new AiChatMessage();
        }

        @Override
        public List<AiChatSession> listSessions(Long userId) {
            return List.of();
        }

        @Override
        public void renameSession(String sessionId, Long userId, String title) {
        }

        @Override
        public List<AiChatMessage> getMessages(String sessionId, Long userId) {
            return List.of();
        }

        @Override
        public List<AiChatMessage> getHistoryForAI(String sessionId, Long userId) {
            return List.of();
        }

        @Override
        public void deleteSession(String sessionId, Long userId) {
        }
    }
}
