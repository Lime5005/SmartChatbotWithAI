package org.lime.chatbotwithai.web;

import org.lime.chatbotwithai.conversation.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping
    public ConversationTurnResponse start(@RequestBody(required = false) ConversationStartRequest request) {
        String locale = request != null ? request.locale() : null;
        return conversationService.startConversation(locale);
    }

    @PostMapping("/{sessionId}/messages")
    public ConversationTurnResponse reply(@PathVariable String sessionId,
                                          @RequestBody UserReplyRequest request) {
        return conversationService.applyUserReply(sessionId, request);
    }

    @PostMapping("/{sessionId}/events")
    public ConversationTurnResponse event(@PathVariable String sessionId,
                                          @RequestBody ConversationEventRequest request) {
        return conversationService.recordEvent(sessionId, request);
    }
}

