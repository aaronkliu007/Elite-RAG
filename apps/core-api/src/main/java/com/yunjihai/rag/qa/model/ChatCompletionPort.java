package com.yunjihai.rag.qa.model;

@FunctionalInterface
public interface ChatCompletionPort {
    ModelGeneration generate(GroundedAnswerPrompt prompt);
}
