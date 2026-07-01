package com.donotmiss.backend.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Bm25QueryCompactorTest {

    @Test
    void compactsLongRewriteAndKeepsTechnicalTerms() {
        String query = """
                我想深入学习 Java JVM 性能调优和 GC 诊断
                深入理解 JVM 内存模型与垃圾回收机制 intermediate
                Java 后端开发 JVM GC 调优 skill 企业 线上
                """;

        String compacted = Bm25QueryCompactor.compact(query);

        assertThat(compacted)
                .contains("java")
                .contains("jvm")
                .contains("gc")
                .contains("性能调优")
                .doesNotContain("intermediate")
                .doesNotContain("skill");
        assertThat(compacted.length()).isLessThanOrEqualTo(240);
    }

    @Test
    void fallbackIsShortEnoughForSafeRetry() {
        String query = "我想从零开始学习日语，希望是线上活动，适合初学者，可以练习口语和跨文化沟通 "
                .repeat(10);

        String fallback = Bm25QueryCompactor.fallback(query);

        assertThat(fallback).isNotBlank();
        assertThat(fallback.length()).isLessThanOrEqualTo(96);
        assertThat(fallback.split("\\s+").length).isLessThanOrEqualTo(8);
    }
}
