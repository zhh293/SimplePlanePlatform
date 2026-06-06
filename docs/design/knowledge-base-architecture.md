# 文档知识库架构设计

## 1. 背景与目标

文档类型知识库与传统业务系统有本质区别：文档体积大（几KB到几十KB一篇）、全文检索需求强、读多写少但写入不能丢、数据量可达千万级。传统的 MySQL 关系型数据库在这种场景下表现很差——全文搜索慢、大文本存储效率低、扩容困难。

本设计采用 **ES + MongoDB + Redis** 三层架构，各组件各司其职：

- **Elasticsearch**：全文检索引擎，负责模糊搜索、相关性排序
- **MongoDB**：文档存储主库（Source of Truth），支持灵活的文档结构和水平分片
- **Redis**：轻量缓存层，加速高频元数据访问
- **MySQL**：最底层持久化备份，定期从 MongoDB 异步同步，不参与在线查询链路

## 2. 整体架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                           Client                                     │
└─────────────┬───────────────────────────────────┬───────────────────┘
              │ 搜索请求                           │ 写入/更新请求
              ▼                                   ▼
┌─────────────────────────┐         ┌─────────────────────────────────┐
│      Search Service      │         │        Write Service            │
│  1. Redis 热搜缓存命中？  │         │  1. 写入 MongoDB（文档+Outbox）  │
│  2. 未命中 → ES 检索     │         │  2. 返回成功                     │
│  3. 拿 ID → MongoDB 取详情│         └──────────────┬──────────────────┘
└─────────────────────────┘                          │ Change Stream / 轮询
                                                     ▼
                                          ┌─────────────────────┐
                                          │     Message Queue    │
                                          └───┬─────────────┬───┘
                                              │             │
                                              ▼             ▼
                                    ┌──────────────┐ ┌──────────────┐
                                    │  ES Consumer │ │ MySQL Consumer│
                                    │  更新索引     │ │  异步同步     │
                                    └──────────────┘ └──────────────┘
```

## 3. 存储层设计（MongoDB）

### 3.1 垂直分集合（冷热分离）

按数据访问频率和体积拆分为三个核心集合：

| 集合 | 数据内容 | 特点 |
|------|---------|------|
| `doc_meta` | 标题、作者、标签、创建时间、更新时间、知识库ID、摘要 | 轻量高频，列表展示用 |
| `doc_content` | 文档正文（可按段落/chunk 拆分存储） | 重量级，按需加载 |
| `user_behavior` | 浏览记录、收藏、点赞、搜索历史 | 写入频繁，用于推荐 |

### 3.2 水平分片策略

当单集合数据量超过亿级时，启用 MongoDB Sharding：

- **Shard Key 选择**：`knowledgeBaseId` + `docId` 组合键
  - 同一知识库的文档落在同一 shard，避免跨片查询
  - docId 保证散列均匀，防止单知识库热点
- **分片时机**：单集合超过 500GB 或查询 P99 > 100ms 时考虑分片
- **Chunk 大小**：默认 64MB，文档场景可调大到 128MB 减少迁移频率

### 3.3 副本集保证可靠性

- 三副本（1 Primary + 2 Secondary），write concern 设为 `majority`
- 开启 Journal，保证宕机不丢数据
- 读操作可配置 `readPreference: secondaryPreferred` 分散读压力

## 4. 搜索层设计（Elasticsearch）

### 4.1 索引设计

```json
{
  "mappings": {
    "properties": {
      "docId": { "type": "keyword" },
      "title": { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
      "content": { "type": "text", "analyzer": "ik_max_word" },
      "tags": { "type": "keyword" },
      "author": { "type": "keyword" },
      "knowledgeBaseId": { "type": "keyword" },
      "createdAt": { "type": "date" },
      "updatedAt": { "type": "date" },
      "viewCount": { "type": "integer" },
      "version": { "type": "long" }
    }
  }
}
```

### 4.2 搜索场景

- **搜索栏模糊查询**：直接走 ES 的 `match` / `multi_match`，支持标题+正文联合搜索
- **标签/分类筛选**：ES 的 `term` 过滤，结合 `bool query` 组合条件
- **热门文档排序**：ES 的 `function_score`，综合浏览量、时间衰减、相关性评分

### 4.3 索引管理

- 按知识库 ID 或时间做索引拆分（index per knowledge base 或 monthly rolling）
- 设置合理的 `refresh_interval`（写入密集时调到 30s，减少 segment 合并压力）
- 副本数设为 1（搜索层允许短暂不可用，MongoDB 兜底）

## 5. 缓存层设计（Redis）

### 5.1 缓存原则：只缓存轻量数据

文档正文**不缓存**（体积大、命中率低、长尾分布）。缓存的目标是减少对 ES 和 MongoDB 的查询次数。

### 5.2 缓存内容

| Key 模式 | Value | TTL | 用途 |
|----------|-------|-----|------|
| `hot:search:{keyword}` | 文档 ID 列表（Top 20） | 10min | 热门搜索词结果缓存 |
| `hot:recommend:{userId}` | 推荐文档 ID 列表 | 5min | 首页推荐缓存 |
| `doc:meta:{docId}` | 标题+摘要+标签 JSON | 30min | 文档元信息缓存 |
| `kb:catalog:{kbId}` | 知识库目录结构 | 10min | 目录树缓存 |
| `user:recent:{userId}` | 最近浏览文档 ID（ZSet） | 永久 | 浏览历史 |

### 5.3 典型查询链路

**搜索栏搜索**：
```
Redis(hot:search:{keyword}) → 命中 → 拿 ID 列表 → MongoDB 取详情
                            → 未命中 → ES 模糊查询 → 返回 ID + 写入缓存 → MongoDB 取详情
```

**首页推荐**：
```
Redis(hot:recommend:{userId}) → 命中 → 拿 ID → MongoDB 查文档元信息
                              → 未命中 → ES function_score 查热门 → 缓存 → MongoDB 查
```

## 6. 数据一致性设计

### 6.1 写入链路（Transactional Outbox）

文档写入时，利用 MongoDB 多文档事务保证原子性：

```
BEGIN Transaction
  1. 写入/更新 doc_meta 集合
  2. 写入/更新 doc_content 集合
  3. 插入 outbox 集合（事件记录：docId, eventType, version, createdAt）
COMMIT
```

事务提交后，后台进程（Change Stream 监听或定时轮询 outbox）将事件发送到 MQ：

- **ES Consumer**：消费事件，更新 ES 索引
- **MySQL Consumer**：消费事件，异步同步到 MySQL 备份库

投递成功后标记 outbox 记录为 `PROCESSED`，失败则重试（幂等设计，基于 version 字段）。

### 6.2 定期对账（兜底补偿）

每小时运行对账任务：

1. 从 MongoDB 查询最近 2 小时内变更的文档（按 `updatedAt`）
2. 批量查 ES 对应文档的 `version` 字段
3. 发现 version 不一致 → 重新从 MongoDB 读取最新数据 → 更新 ES
4. 同理对账 MySQL

对账确保即使 outbox 投递偶尔丢失，数据最终也能追平。

### 6.3 失败处理

- **ES 更新失败**：消息重新入队，指数退避重试，超过阈值告警人工介入
- **MySQL 同步失败**：同上，但不影响在线服务（MySQL 不参与读链路）
- **不回滚 MongoDB**：MongoDB 是 Source of Truth，已写入的文档不因下游同步失败而回滚（用户可能已经看到了），方向是"让下游追上来"而不是"让上游退回去"

## 7. MySQL 的定位

MySQL 在本架构中**不参与在线查询链路**，定位为：

- **冷备份**：定期从 MongoDB 同步，作为灾难恢复的最后一道防线
- **数据审计**：关系型结构方便做跨表关联分析、合规审计
- **对账基准**：某些场景下可作为对账参考

同步方式：MQ 异步消费，写入延迟可接受分钟级。不需要强一致，最终一致即可。

## 8. 扩展能力

### 8.1 语义搜索（未来演进）

当前架构基于 ES 的 BM25 关键词检索。后续可引入向量搜索：

- 文档入库时生成 embedding 向量，存入 ES 8.x 的 dense_vector 字段（或独立向量库如 Milvus）
- 搜索时双路召回：BM25 关键词 + kNN 向量语义，混合排序
- 解决"用户问法和文档用词不同"的语义鸿沟问题

### 8.2 读写分离

- MongoDB：Secondary 分担读流量
- ES：Coordinator Node 负责分发查询，Data Node 做实际检索
- Redis：主从 + Sentinel，读走从节点

### 8.3 降级策略

| 故障组件 | 降级方案 |
|---------|---------|
| Redis 不可用 | 跳过缓存，直接查 ES/MongoDB，性能降低但功能不受影响 |
| ES 不可用 | 搜索功能降级，可回退到 MongoDB 的 text index 做基础搜索 |
| MongoDB 主节点切换 | 短暂不可写（几秒），副本自动选主后恢复 |
| MySQL 不可用 | 无影响，不参与在线链路 |

## 9. 总结

```
写入：Client → MongoDB (文档 + Outbox 事务) → MQ → ES索引 + MySQL备份
搜索：Client → Redis缓存 → ES检索 → MongoDB取详情
推荐：Client → Redis缓存 → ES评分 → MongoDB取详情

Source of Truth: MongoDB
搜索视图:       Elasticsearch（派生，最终一致）
缓存加速:       Redis（轻量元数据，非正文）
冷备份:         MySQL（异步同步，不参与在线）
一致性保证:     Outbox + MQ + 定期对账
```

核心设计原则：各组件职责单一、故障隔离、缓存只做轻量加速、以最终一致性换取高可用和高性能。
