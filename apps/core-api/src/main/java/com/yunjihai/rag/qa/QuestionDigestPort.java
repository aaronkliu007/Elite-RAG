package com.yunjihai.rag.qa;

@FunctionalInterface
public interface QuestionDigestPort {
    String digest(String value);
}
