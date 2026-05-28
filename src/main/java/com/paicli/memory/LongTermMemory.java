package com.paicli.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 长期记忆 - 跨对话持久化的关键信息
 *
 * 💡 通俗理解：长期记忆就像是一个"笔记本"，把重要的跨会话信息（用户偏好、项目事实等）
 * 写到磁盘 JSON 文件里，下次启动程序时还能读回来。
 *
 * 职责：
 * 1. 持久化用户偏好、项目事实、关键决策等
 * 2. 支持关键词检索（比如搜"Maven"能找到"项目用 Maven 构建"这条记忆）
 * 3. 自动去重（同样的内容只保存一条）
 * 4. 每次改动都立即保存到磁盘文件
 */
public class LongTermMemory implements Memory {
    private static final Logger log = LoggerFactory.getLogger(LongTermMemory.class);

    /** 通过 -Dpaicli.memory.dir=xxx 指定的自定义存储目录 */
    private static final String STORAGE_DIR_PROPERTY = "paicli.memory.dir";
    /** 通过环境变量 PAICLI_MEMORY_DIR 指定的自定义存储目录 */
    private static final String STORAGE_DIR_ENV = "PAICLI_MEMORY_DIR";
    /** 存储文件名 */
    private static final String STORAGE_FILE = "long_term_memory.json";

    /**
     * 核心数据结构：用 ConcurrentHashMap 存所有记忆条目
     * - 声明类型是 Map 接口（面向接口编程，隐藏实现细节）
     * - 实际运行时是 ConcurrentHashMap（支持多线程安全读写）
     * - Key = 记忆 ID（如 "fact-a1b2c3d4"）
     * - Value = MemoryEntry 对象（包含内容、类型、时间戳等信息）
     */
    private final Map<String, MemoryEntry> entries;

    /**
     * 所有记忆的 token 总数（原子计数器）
     * - 用 AtomicInteger 而不是 int，是因为多线程环境下要防止计数冲突
     * - 比如线程 A 和线程 B 同时写入，int++ 可能丢失一次计数，addAndGet 不会
     */
    private final AtomicInteger tokenCounter;

    /**
     * JSON 序列化/反序列化工具
     * - 用来把记忆数据写成 JSON 文件，或从 JSON 文件读回内存
     */
    private final ObjectMapper mapper;

    /** 磁盘上的 JSON 文件路径 */
    private final File storageFile;

    /**
     * 无参构造函数 - 使用默认存储路径
     * 默认路径：~/.paicli/memory/ （Mac/Linux） 或 C:\Users\用户名\.paicli\memory\ （Windows）
     */
    public LongTermMemory() {
        this(resolveStorageDir());
    }

    /**
     * 带参构造函数 - 指定存储目录
     */
    public LongTermMemory(File storageDir) {
        // ① 初始化核心数据结构
        this.entries = new ConcurrentHashMap<>();
        this.tokenCounter = new AtomicInteger(0);
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);  // JSON 输出时带缩进，方便人类阅读

        // ② 确保存储目录存在，不存在就创建
        File dir = storageDir;
        if (!dir.exists()) {
            dir.mkdirs();
        }
        // ③ 拼接完整文件路径，比如：~/.paicli/memory/long_term_memory.json
        this.storageFile = new File(dir, STORAGE_FILE);

        // ④ 程序启动时，尝试从磁盘加载之前保存的记忆
        loadFromDisk();
    }

    /**
     * 存储一条新记忆
     *
     * @param entry 要存储的记忆条目
     */
    @Override
    public void store(MemoryEntry entry) {
        // ① 去重：检查是否已存在内容完全相同的记忆
        // entries.values() → 拿到所有已保存的记忆
        // .stream() → 转成流，方便遍历
        // .anyMatch(e -> e.getContent().equals(entry.getContent())) → 只要有一条内容相同，就返回 true
        boolean duplicate = entries.values().stream()
                .anyMatch(e -> e.getContent().equals(entry.getContent()));
        if (duplicate) {
            return;  // 已存在相同内容，不重复保存，直接返回
        }

        // ② 存入内存 Map
        entries.put(entry.getId(), entry);

        // ③ 更新 token 总数（原子操作，多线程安全）
        tokenCounter.addAndGet(entry.getTokenCount());

        // ④ 立即写入磁盘，确保程序崩溃也不丢失
        saveToDisk();
    }

    /**
     * 根据 ID 查找一条记忆
     *
     * @param id 记忆 ID
     * @return Optional，如果找不到返回空 Optional
     */
    @Override
    public Optional<MemoryEntry> retrieve(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    /**
     * 关键词搜索记忆
     * 比如用户问"项目的构建工具是什么"，会搜到包含"Maven"关键词的记忆
     *
     * @param query 搜索关键词（如 "Maven"）
     * @param limit 最多返回几条
     */
    @Override
    public List<MemoryEntry> search(String query, int limit) {
        // ① 先把搜索关键词用 Jieba 分词，拆成多个小词
        // 比如 "我喜欢用 Maven 构建项目" → ["喜欢", "Maven", "构建", "项目"]
        Set<String> queryTokens = MemoryQueryTokenizer.tokenize(query);

        // ② 遍历所有记忆，筛选出命中的条目
        return entries.values().stream()
                .filter(entry -> {
                    // 先在记忆正文（content）中搜索
                    if (MemoryQueryTokenizer.matches(entry.getContent(), queryTokens)) {
                        return true;  // 正文中命中，直接保留
                    }
                    // 正文没命中，再搜索 metadata 的每个值
                    // 比如 metadata 可能有 {"source": "fact"}，也参与匹配
                    return entry.getMetadata().values().stream()
                            .anyMatch(value -> MemoryQueryTokenizer.matches(value, queryTokens));
                })
                .limit(limit)  // 只取前 limit 条
                .collect(Collectors.toList());  // 转成 List 返回
    }

    /**
     * 获取所有记忆
     * 返回新 List，避免外部直接修改内部数据
     */
    @Override
    public List<MemoryEntry> getAll() {
        return new ArrayList<>(entries.values());
    }

    /**
     * 删除指定记忆
     *
     * @param id 记忆 ID
     * @return 是否删除成功
     */
    @Override
    public boolean delete(String id) {
        MemoryEntry removed = entries.remove(id);  // 从 Map 中移除，返回被删除的条目
        if (removed != null) {
            // 从 token 总数中减去被删除条目的 token 数
            tokenCounter.addAndGet(-removed.getTokenCount());
            saveToDisk();  // 立即写入磁盘
            return true;
        }
        return false;  // ID 不存在，删除失败
    }

    /**
     * 清空所有记忆
     */
    @Override
    public void clear() {
        entries.clear();         // 清空内存中的 Map
        tokenCounter.set(0);     // token 总数归零
        saveToDisk();            // 写入空文件（覆盖旧数据）
    }

    /**
     * 获取当前所有记忆的 token 总数
     */
    @Override
    public int getTokenCount() {
        return tokenCounter.get();
    }

    /**
     * 获取记忆条数
     */
    @Override
    public int size() {
        return entries.size();
    }

    /**
     * 按类型筛选记忆
     * 比如只查 FACT 类型（事实类记忆）
     *
     * @param type 记忆类型（FACT / SUMMARY / TOOL_RESULT / CONVERSATION）
     */
    public List<MemoryEntry> getByType(MemoryEntry.MemoryType type) {
        return entries.values().stream()
                .filter(entry -> entry.getType() == type)  // 只保留类型匹配的
                .collect(Collectors.toList());
    }

    /**
     * 持久化到磁盘 - 把内存中的所有记忆写成 JSON 文件
     *
     * 文件内容示例：
     * [
     *   {
     *     "id": "fact-a1b2c3d4",
     *     "content": "用户喜欢使用 JDK 17",
     *     "type": "FACT",
     *     "timestamp": "2026-05-27T10:00:00Z",
     *     "metadata": {"source": "fact"},
     *     "tokenCount": 8
     *   },
     *   ...
     * ]
     */
    private void saveToDisk() {
        try {
            // ① 把 Map 中的 MemoryEntry 对象转成 Map<String, Object> 列表
            // 因为 Jackson 默认序列化 JavaBean 时可能带出多余字段，手动转 Map 更可控
            List<Map<String, Object>> dataList = entries.values().stream()
                    .map(this::entryToMap)  // 对每个 MemoryEntry 调用 entryToMap 方法
                    .collect(Collectors.toList());

            // ② 用 Jackson 写成 JSON 文件
            mapper.writeValue(storageFile, dataList);
        } catch (IOException e) {
            // 写入失败只打日志，不抛异常（不影响程序正常运行）
            log.warn("长期记忆持久化失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 解析存储目录的优先级：
     * 1. 先看 -Dpaicli.memory.dir=xxx 启动参数
     * 2. 再看环境变量 PAICLI_MEMORY_DIR
     * 3. 最后用默认 ~/.paicli/memory/
     */
    private static File resolveStorageDir() {
        String configuredDir = System.getProperty(STORAGE_DIR_PROPERTY);
        if (configuredDir == null || configuredDir.isBlank()) {
            configuredDir = System.getenv(STORAGE_DIR_ENV);
        }
        if (configuredDir != null && !configuredDir.isBlank()) {
            return new File(configuredDir);
        }
        // 默认路径：用户主目录/.paicli/memory/
        return new File(new File(System.getProperty("user.home"), ".paicli"), "memory");
    }

    /**
     * 从磁盘加载记忆 - 程序启动时调用
     * 把 JSON 文件中的数据读回内存 Map
     */
    @SuppressWarnings("unchecked")
    private void loadFromDisk() {
        // 如果文件不存在（第一次启动），直接返回，不报错
        if (!storageFile.exists()) return;

        try {
            // ① 读 JSON 文件，转成 List<Map<String, Object>>
            // Jackson 会自动把 JSON 数组解析成 List，每个对象解析成 Map
            List<Map<String, Object>> dataList = mapper.readValue(storageFile, List.class);

            // ② 遍历每个 Map，转成 MemoryEntry 对象
            for (Map<String, Object> data : dataList) {
                MemoryEntry entry = mapToEntry(data);
                if (entry != null) {  // 如果转换成功（不为 null）
                    entries.put(entry.getId(), entry);           // 存入 Map
                    tokenCounter.addAndGet(entry.getTokenCount());  // 累加 token 数
                }
            }
            log.info("加载了 {} 条长期记忆", entries.size());
        } catch (IOException e) {
            // 读取失败只打日志（可能是文件损坏），不影响程序启动
            log.warn("加载长期记忆失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 把 MemoryEntry 对象转成 Map（用于写 JSON）
     * 比如：{ "id": "fact-xxx", "content": "...", "type": "FACT", ... }
     */
    private Map<String, Object> entryToMap(MemoryEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();  // 用 LinkedHashMap 保证字段顺序
        map.put("id", entry.getId());
        map.put("content", entry.getContent());
        map.put("type", entry.getType().name());  // 枚举转字符串，如 "FACT"
        map.put("timestamp", entry.getTimestamp().toString());  // Instant 转 ISO 8601 字符串
        map.put("metadata", entry.getMetadata());
        map.put("tokenCount", entry.getTokenCount());
        return map;
    }

    /**
     * 把 Map（从 JSON 读来的数据）转成 MemoryEntry 对象
     * 这个方法有点复杂，因为 JSON 解析出来的类型不一定是我们想要的，需要手动转换
     *
     * 比如 JSON 里 "type" 存的是字符串 "FACT"，但我们需要转成 MemoryEntry.MemoryType.FACT 枚举
     * timestamp 存的是字符串 "2026-05-27T10:00:00Z"，需要转成 Instant 对象
     */
    @SuppressWarnings("unchecked")
    private MemoryEntry mapToEntry(Map<String, Object> map) {
        try {
            // ① 直接取 String 类型的字段
            String id = (String) map.get("id");
            String content = (String) map.get("content");

            // ② 把字符串 "FACT" 转成枚举 MemoryEntry.MemoryType.FACT
            MemoryEntry.MemoryType type = MemoryEntry.MemoryType.valueOf((String) map.get("type"));

            // ③ 解析时间戳（JSON 里是字符串，需要转成 Instant）
            Instant timestamp = null;
            Object timestampObj = map.get("timestamp");
            // instanceof 模式匹配：检查 timestampObj 是不是 String 类型，如果是就赋值给 timestampValue
            if (timestampObj instanceof String timestampValue && !timestampValue.isBlank()) {
                timestamp = Instant.parse(timestampValue);  // 解析 ISO 8601 格式，如 "2026-05-27T10:00:00Z"
            }

            // ④ 解析 metadata（JSON 里是一个对象，需要转成 Map<String, String>）
            Map<String, String> metadata = new HashMap<>();
            Object metaObj = map.get("metadata");
            if (metaObj instanceof Map) {
                // 遍历 Map 的每个 key-value，把 value 都转成 String
                ((Map<String, Object>) metaObj).forEach((k, v) -> metadata.put(k, String.valueOf(v)));
            }

            // ⑤ 解析 tokenCount（JSON 里是数字，但 Jackson 可能解析成 Integer/Long/Double）
            // instanceof Number n 是 Java 16+ 的模式匹配写法
            int tokenCount = map.get("tokenCount") instanceof Number n ? n.intValue() : MemoryEntry.estimateTokens(content);

            // ⑥ 用解析好的字段创建 MemoryEntry 对象
            return new MemoryEntry(id, content, type, timestamp, metadata, tokenCount);
        } catch (Exception e) {
            // 如果某条数据格式不对（比如缺少 id 字段），返回 null，跳过这条数据
            // 不会影响其他正常数据的加载
            return null;
        }
    }

    /**
     * 生成记忆状态摘要（用于运行时展示给用户看）
     *
     * 输出示例：
     * "长期记忆: 5条 / 320 tokens (事实: 3, 摘要: 1, 工具结果: 1)"
     */
    public String getStatusSummary() {
        // 按类型分组统计：{ FACT=3, SUMMARY=1, TOOL_RESULT=1 }
        Map<MemoryEntry.MemoryType, Long> typeCounts = entries.values().stream()
                .collect(Collectors.groupingBy(MemoryEntry::getType, Collectors.counting()));

        return String.format("长期记忆: %d条 / %d tokens (事实: %d, 摘要: %d, 工具结果: %d)",
                entries.size(), tokenCounter.get(),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.FACT, 0L),      // 如果没有 FACT 类型，默认 0
                typeCounts.getOrDefault(MemoryEntry.MemoryType.SUMMARY, 0L),   // 如果没有 SUMMARY 类型，默认 0
                typeCounts.getOrDefault(MemoryEntry.MemoryType.TOOL_RESULT, 0L));
    }
}
