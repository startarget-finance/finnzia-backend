package com.finnza.controller;

import com.finnza.dto.request.ChatAiRequest;
import com.finnza.dto.response.ChatAiResponse;
import com.finnza.service.AiChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/ai")
@CrossOrigin(origins = "*")
public class AiChatController {

    private final AiChatService aiChatService;

    public AiChatController(AiChatService aiChatService) {
        this.aiChatService = aiChatService;
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatAiResponse> chat(@RequestBody ChatAiRequest request) {
        if (request == null || request.getMessage() == null || request.getMessage().isBlank()) {
            return ResponseEntity.badRequest().body(new ChatAiResponse("Mensagem vazia.", java.time.Instant.now()));
        }

        ChatAiResponse response = aiChatService.enviarChat(request.getMessage());
        return ResponseEntity.ok(response);
    }
}

