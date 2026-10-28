package com.yunjihai.rag.qa.model;

public interface ChatCompletionPort {
    ModelCallDescriptor descriptor();

    ModelGeneration generate(GroundedAnswerPrompt prompt);
}
