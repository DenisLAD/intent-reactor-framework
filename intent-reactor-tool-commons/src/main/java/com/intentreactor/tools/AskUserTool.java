package com.intentreactor.tools;

import com.intentreactor.api.Tool;
import com.intentreactor.api.ToolInput;
import com.intentreactor.api.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Pauses plan execution and asks the user a clarifying question.
 *
 * <p>Marked {@link #isRisky()} to trigger the confirmation pause.
 * The UI detects {@code toolName == "ask_user"} and shows a text-input dialog instead of
 * approve/reject buttons. The user's answer is sent back as
 * {@code modifiedParameters = { "answer": "..." }}, which {@code executeTool()} merges into
 * the parameters before calling {@link #execute(ToolInput)}.
 */
@Component
public class AskUserTool implements Tool {

    @Override
    public String getName() {
        return "ask_user";
    }

    @Override
    public String getDescription() {
        return "Задаёт пользователю уточняющий вопрос и возвращает его ответ. " +
                "Используй только когда тебе не хватает информации для выполнения задачи " +
                "и ты не можешь сделать разумное предположение. " +
                "Параметр 'question' — это точный вопрос, который увидит пользователь. " +
                "Не используй этот инструмент если информации уже достаточно.";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "question", Map.of(
                                "type", "string",
                                "description", "Вопрос для пользователя"
                        ),
                        "answer", Map.of(
                                "type", "string",
                                "description", "Ответ пользователя (заполняется UI автоматически)"
                        )
                ),
                "required", List.of("question")
        );
    }

    @Override
    public ToolResult execute(ToolInput input) {
        String answer = (String) input.getParameters().get("answer");
        String question = (String) input.getParameters().get("question");
        if (answer != null && !answer.isBlank()) {
            return ToolResult.ok("Пользователь ответил: " + answer);
        }
        return ToolResult.ok("Пользователь подтвердил вопрос: " + question);
    }

    @Override
    public boolean isRisky() {
        return true;
    }
}
