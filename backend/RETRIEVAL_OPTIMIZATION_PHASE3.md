# Retrieval Optimization Phase 3

本阶段把检索链路从“Query Rewrite + 本地规则 + 可选 BM25”推进到“BM25 + Embedding Vector + Rank Fusion + 可选 LLM Reranker”。

## 1. 新增能力

### 1.1 Embedding 向量召回

- `OpenAiCompatibleLlmClient` 新增 `/embeddings` 调用。
- 默认 embedding 模型为 `text-embedding-v4`，维度通过 `AI_EMBEDDING_DIMENSIONS` 配置。
- `EventSearchIndexService` 在重建索引时，可以把事件标题、组织、分类、地点、内容、收益、技能合成 `allText`，并生成 embedding 写入 OpenSearch 的 `knn_vector` 字段。
- 向量召回默认关闭，避免重建索引时消耗模型额度。

### 1.2 BM25 + Vector 混合召回

`HybridEventRetrievalService` 的候选获取顺序变成：

1. OpenSearch BM25 召回一批候选。
2. 如果启用向量，使用 query embedding 做 kNN 召回。
3. 使用 RRF 思路融合两个排序列表。
4. 对融合后的事件继续执行本地分数：关键词、意图、用户画像、时间地点收益等元数据。
5. 如果搜索引擎不可用，自动退回数据库候选 + Java 本地规则。

这样面试时可以讲成两阶段检索：

- 召回层：扩大候选覆盖率，BM25 负责字面匹配，Vector 负责语义匹配。
- 排序层：用结构化特征和用户画像做可解释打分。

### 1.3 可选 LLM Reranker

新增 `EventRerankerService`。

- reranker 只负责重排候选事件，不允许生成新事件。
- 模型输出的 `eventId` 如果不在后端候选列表中，会被后端丢弃。
- reranker reason 会写入 retrieval evidence，方便 trace 查看。
- 默认关闭，因为它会额外消耗一次 LLM 调用。

### 1.4 Context Compression

AI 推荐和计划生成不会把整库全文直接塞给模型，而是只传入召回后的候选事件，并对每个候选做压缩：

- 候选数量限制在前 N 个。
- 活动内容、技能、历史记录字段做长度裁剪。
- 每个候选附带 `compressedContext`，保留标题、组织、分类、时间、地点、收益、技能和 retrieval evidence。
- 模型生成理由时优先参考后端检索证据，减少无关字段带来的干扰。

面试时可以说：先由检索层筛候选，再把每个候选压成结构化 evidence context。这样既节省 token，也能让模型把注意力集中在标题、技能、地点、收益和检索证据上。

## 2. 配置

基础 BM25：

```properties
SEARCH_ENABLED=true
SEARCH_BASE_URL=http://localhost:9200
SEARCH_INDEX_NAME=do_not_miss_events
```

启用向量召回：

```properties
SEARCH_VECTOR_ENABLED=true
AI_EMBEDDING_MODEL=text-embedding-v4
AI_EMBEDDING_DIMENSIONS=1024
```

启用 LLM Reranker：

```properties
SEARCH_RERANK_ENABLED=true
SEARCH_RERANK_TOP_N=12
```

配置变更后，重启后端并执行：

```powershell
curl -X POST "http://localhost:8080/api/ai/retrieval/reindex" `
  -H "Authorization: Bearer $TOKEN"
```

## 3. 注意事项

- 如果先创建了不带向量字段的 OpenSearch index，之后再打开 `SEARCH_VECTOR_ENABLED=true`，建议删除旧 index 后重新 `/reindex`。
- 如果 embedding 维度和 `AI_EMBEDDING_DIMENSIONS` 不一致，后端会跳过向量写入并在日志里提示。
- 当前 vector 召回先不做 OpenSearch 内部过滤，后端会在融合后再次按分类、收益、地点过滤，避免语义召回带入不符合硬条件的事件。
- 生产环境中可以继续加缓存：事件 embedding 缓存、query embedding 短 TTL 缓存、reranker 结果缓存。

## 4. 面试表达

可以这样说：

> 我把事件推荐拆成了 Query Understanding、Recall、Ranking、Generation 四层。Query Rewrite 把自然语言需求改写成结构化意图；Recall 层同时使用 BM25 和向量召回，分别覆盖字面相关和语义相关；Ranking 层先用可解释特征打分，再可选接入 LLM Reranker；最后生成层只基于后端候选事件输出推荐理由，所有 eventId 都经过白名单校验，避免模型幻觉。
