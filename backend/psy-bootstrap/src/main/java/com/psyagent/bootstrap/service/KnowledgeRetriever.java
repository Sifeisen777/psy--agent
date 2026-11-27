package com.psyagent.bootstrap.service;

import com.psyagent.store.mapper.KnowledgeChunkMapper;
import com.psyagent.store.search.KnowledgeHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识检索。
 * <p>
 * 当前实现是**稀疏检索**：MySQL ngram 倒排索引 + 类 TF-IDF 相关度。
 * 与向量检索同属 RAG 的检索层，差别只在相似度度量——倒排依赖字面重叠，
 * 向量依赖语义距离。前者零基础设施，后者需要 embedding 服务。
 * <p>
 * 两个已知短板与应对：
 * <ul>
 *   <li>同义表达匹配不上（"睡不着" vs "失眠"）→ 查询扩展，维护一张口语到书面语的映射</li>
 *   <li>无法处理纯语义相似（"我晚上脑子停不下来"）→ 只能靠扩展词表接近，根治需要向量检索</li>
 * </ul>
 */
@Service
public class KnowledgeRetriever {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetriever.class);

    /** 拼进提示词的片段条数上限。3 条约 900 字，加上历史与系统提示词仍在安全范围。 */
    private static final int DEFAULT_TOP_K = 3;

    /** 同一文档最多取几条，避免三条款式相同的片段挤占上下文。 */
    private static final int MAX_PER_DOC = 2;

    /**
     * 相关度下限（经验值）。实测相关片段多在 1.2 以上，而「今天天气怎么样」这类
     * 与领域无关的查询因虚词重叠也能拿到 0.9 左右，需要用绝对阈值 + 相对阈值叠加过滤。
     */
    private static final double MIN_SCORE = 0.8;

    /** 相对阈值：低于最高分这个比例的视为噪音，应对长查询分数整体偏低的情况。 */
    private static final double RELATIVE_RATIO = 0.2;

    private static final int MAX_QUERY_CHARS = 120;

    /**
     * 领域词门槛。
     * <p>
     * 中文没有分词器，ngram 会把「今天天气怎么样」切成「今天 / 天天 / 天气 / 怎么 / 么样」，
     * 其中若干虚词在心理科普文本里也出现过，于是无意义查询同样能拿到分数。
     * 与其不断调高阈值（会误伤短查询），不如在检索前先判断问题是否属于本领域：
     * 一个心理咨询知识库，本来就不该回答天气问题。
     */
    private static final List<String> DOMAIN_TERMS = List.of(
            "睡", "失眠", "熬夜", "梦", "累", "疲", "精力",
            "焦虑", "紧张", "慌", "害怕", "担心", "恐惧", "压力", "烦",
            "难过", "低落", "抑郁", "情绪", "心情", "开心", "兴趣", "没意思", "空虚",
            "孤独", "寂寞", "崩溃", "撑", "绝望", "哭", "委屈", "生气", "愤怒",
            "考试", "学习", "复习", "成绩", "作业", "工作", "上班", "加班", "领导", "同事", "失业",
            "头痛", "胃", "胸闷", "气短", "呼吸", "心跳", "手抖", "出汗", "食欲", "体重",
            "咨询", "医生", "看病", "吃药", "治疗", "求助", "热线", "自杀", "自残", "死", "活",
            "关系", "吵架", "分手", "父母", "家人", "朋友", "孩子", "恋爱", "结婚",
            "放松", "冥想", "运动", "作息", "调节", "怎么办"
    );

    /** 口语表达 → 书面表达的映射，键为用户可能说的，值为知识库中出现的。 */
    private static final Map<String, String[]> SYNONYMS = buildSynonyms();

    private final KnowledgeChunkMapper chunkMapper;

    public KnowledgeRetriever(KnowledgeChunkMapper chunkMapper) {
        this.chunkMapper = chunkMapper;
    }

    /**
     * 检索与问题相关的知识片段。
     *
     * @return 按相关度降序，可能为空 —— 空结果不是错误，此时应退化为普通对话
     */
    public List<KnowledgeHit> retrieve(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (!inDomain(query)) {
            log.debug("knowledge skip, out of domain query={}", query);
            return List.of();
        }
        String expanded = expand(query);
        List<KnowledgeHit> raw = chunkMapper.search(expanded, DEFAULT_TOP_K * 3);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }

        // 两条阈值叠加：绝对下限挡整体噪音，相对阈值挡"远低于最高分"的伴随命中
        double topScore = raw.get(0).getScore() == null ? 0 : raw.get(0).getScore();
        double cutoff = Math.max(MIN_SCORE, topScore * RELATIVE_RATIO);

        List<KnowledgeHit> picked = new ArrayList<>(DEFAULT_TOP_K);
        Map<Long, Integer> perDoc = new LinkedHashMap<>();
        for (KnowledgeHit hit : raw) {
            if (hit.getScore() == null || hit.getScore() < cutoff) {
                continue;
            }
            int used = perDoc.getOrDefault(hit.getDocId(), 0);
            if (used >= MAX_PER_DOC) {
                continue;
            }
            perDoc.put(hit.getDocId(), used + 1);
            picked.add(hit);
            if (picked.size() >= DEFAULT_TOP_K) {
                break;
            }
        }
        log.info("knowledge retrieve query={} candidates={} cutoff={} picked={} titles={}",
                expanded, raw.size(), String.format("%.2f", cutoff), picked.size(),
                picked.stream().map(KnowledgeHit::getTitle).toList());
        return picked;
    }

    /** 判断问题是否落在本知识库的领域内。宁可漏检交给模型常识回答，也不要给出无中生有的引用。 */
    public static boolean inDomain(String query) {
        return query != null && DOMAIN_TERMS.stream().anyMatch(query::contains);
    }

    /**
     * 查询扩展：口语补充为书面语，原词保留。
     * 扩展词与原词之间是「或」的关系，由全文索引的相关度排序决定谁更匹配。
     */
    static String expand(String query) {
        String base = query.strip();
        if (base.length() > MAX_QUERY_CHARS) {
            base = base.substring(0, MAX_QUERY_CHARS);
        }
        Set<String> extra = new LinkedHashSet<>();
        for (Map.Entry<String, String[]> entry : SYNONYMS.entrySet()) {
            if (base.contains(entry.getKey())) {
                extra.addAll(List.of(entry.getValue()));
            }
        }
        if (extra.isEmpty()) {
            return base;
        }
        return base + " " + String.join(" ", extra);
    }

    private static Map<String, String[]> buildSynonyms() {
        Map<String, String[]> map = new LinkedHashMap<>();
        map.put("睡不着", new String[]{"失眠", "入睡"});
        map.put("睡不好", new String[]{"失眠", "睡眠"});
        map.put("失眠", new String[]{"睡眠", "入睡"});
        map.put("心慌", new String[]{"焦虑", "心跳"});
        map.put("心跳", new String[]{"心慌", "焦虑"});
        map.put("胸闷", new String[]{"焦虑", "呼吸"});
        map.put("喘不上", new String[]{"呼吸", "焦虑"});
        map.put("紧张", new String[]{"焦虑", "考试"});
        map.put("压力", new String[]{"焦虑", "考试"});
        map.put("考不好", new String[]{"考试", "焦虑"});
        map.put("考试", new String[]{"学业", "压力"});
        map.put("不想上班", new String[]{"耗竭", "低落"});
        map.put("没意思", new String[]{"快感缺失", "低落"});
        map.put("提不起", new String[]{"快感缺失", "精力"});
        map.put("难过", new String[]{"低落", "抑郁"});
        map.put("开心不起来", new String[]{"低落", "快感缺失"});
        map.put("想不开", new String[]{"自杀", "危机"});
        map.put("不想活", new String[]{"自杀", "危机", "热线"});
        map.put("心理咨询", new String[]{"咨询", "转介"});
        map.put("看医生", new String[]{"就医", "精神科"});
        return map;
    }
}
