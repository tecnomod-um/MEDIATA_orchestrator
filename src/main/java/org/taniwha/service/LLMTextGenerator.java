package org.taniwha.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// Receives prompts and returns llm responses
@Service
public class LLMTextGenerator {

    private static final Logger logger = LoggerFactory.getLogger(LLMTextGenerator.class);

    private final ChatModel chatModel;
    private final boolean llmEnabled;

    public LLMTextGenerator(ObjectProvider<ChatModel> chatModelProvider,
                            @Value("${llm.enabled:true}") boolean llmEnabled) {
        this.chatModel = chatModelProvider.getIfAvailable();
        this.llmEnabled = llmEnabled && this.chatModel != null;

        logger.info("[LLMTextGenerator] Initialized. llm.enabled property: {}, ChatModel bean present: {}, effective llmEnabled: {}",
                llmEnabled, this.chatModel != null, this.llmEnabled);
    }

    public boolean isEnabled() {
        return llmEnabled;
    }

    public String generate(String promptText) {
        if (!llmEnabled) return "";

        try {
            UserMessage userMessage = new UserMessage(promptText);
            Prompt prompt = new Prompt(userMessage);

            ChatResponse response = chatModel.call(prompt);

            if (response == null ||
                    response.getResult() == null ||
                    response.getResult().getOutput() == null ||
                    response.getResult().getOutput().getText() == null) {
                logger.warn("[LLMTextGenerator] Empty response from LLM");
                return "";
            }

            return response.getResult().getOutput().getText();
        } catch (Exception e) {
            logger.warn("[LLMTextGenerator] LLM call failed: {}", e.getMessage());
            return "";
        }
    }
}
