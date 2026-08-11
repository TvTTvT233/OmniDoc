package com.javaee.aiservice.memory.context;

public enum MemoryCallStage {
    PLANNER,
    FOLLOW_UP_PLANNER,
    DIRECT_ANSWER,
    RAG_ANSWER,
    REFLECTION,
    COMPLETION_EVALUATION,
    FINAL_SYNTHESIS
}
