package com.psyagent.bootstrap.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.psyagent.store.entity.KnowledgeChunk;
import com.psyagent.store.entity.KnowledgeDoc;
import com.psyagent.store.mapper.KnowledgeChunkMapper;
import com.psyagent.store.mapper.KnowledgeDocMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 知识库导入：把 classpath 下的 Markdown 文档切分并入库。
 * <p>
 * 三个设计约束：
 * <p>
 * 1. **幂等**：以标题为唯一标识，已存在则跳过，重复启动不会产生重复切片。
 * 2. **原文与切片分离**：原文整体保留用于溯源，切片单独存表用于检索。
 * 3. **切片按段落聚合**：不按固定字数硬切，否则一个完整的论证会被切断成两句半截话，
 * 检索命中后拼进提示词也是残缺语义。段落本身就是作者的语义单元，优先尊重它。
 */
@Component
public class KnowledgeIngestService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestService.class);

    /** 单个切片的目标长度。中文约 1 字 1 token 量级，300 字以内的片段拼进提示词不会失控。 */
    private static final int CHUNK_MAX_CHARS = 320;

    /** 段落超长时的兜底切分单位：按句号切，再聚合。 */
    private static final String SENTENCE_DELIMITER = "(?<=[。！？；])";

    private final KnowledgeDocMapper docMapper;
    private final KnowledgeChunkMapper chunkMapper;

    public KnowledgeIngestService(KnowledgeDocMapper docMapper, KnowledgeChunkMapper chunkMapper) {
        this.docMapper = docMapper;
        this.chunkMapper = chunkMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int imported = ingestAll();
            if (imported > 0) {
                log.info("knowledge ingested docs={}", imported);
            }
        } catch (Exception e) {
            // 知识库导入失败不应阻止应用启动：检索不到内容时对话仍可正常进行
            log.error("knowledge ingest failed", e);
        }
    }

    /** @return 本次实际导入的文档数 */
    public int ingestAll() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:knowledge/*.md");
        if (resources.length == 0) {
            return 0;
        }
        Arrays.sort(resources, Comparator.comparing(Resource::getFilename,
                Comparator.nullsLast(Comparator.naturalOrder())));

        int imported = 0;
        for (Resource resource : resources) {
            if (ingestOne(resource)) {
                imported++;
            }
        }
        return imported;
    }

    private boolean ingestOne(Resource resource) throws IOException {
        String raw = read(resource);
        ParsedDoc doc = parse(raw, resource.getFilename());
        if (doc.body().isBlank()) {
            log.warn("knowledge skip empty file={}", resource.getFilename());
            return false;
        }

        Long existing = docMapper.selectCount(Wrappers.<KnowledgeDoc>lambdaQuery()
                .eq(KnowledgeDoc::getTitle, doc.title()));
        if (existing != null && existing > 0) {
            return false;
        }

        List<String> chunks = split(doc.body());
        KnowledgeDoc entity = new KnowledgeDoc();
        entity.setTitle(doc.title());
        entity.setCategory(doc.category());
        entity.setSource(doc.source());
        entity.setContent(doc.body());
        entity.setChunkCount(chunks.size());
        entity.setStatus(1);
        docMapper.insert(entity);

        for (int i = 0; i < chunks.size(); i++) {
            String content = chunks.get(i);
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setDocId(entity.getId());
            chunk.setChunkIndex(i);
            chunk.setTitle(doc.title());
            chunk.setContent(content);
            chunk.setCharCount(content.length());
            chunkMapper.insert(chunk);
        }
        log.info("knowledge imported title={} chunks={}", doc.title(), chunks.size());
        return true;
    }

    /**
     * 解析文档。约定格式如下，缺失字段按文件名兜底，避免格式错误导致整篇被丢弃：
     * <pre>
     * # 标题
     * 分类: sleep
     * 来源: xxx
     *
     * 正文...
     * </pre>
     */
    private ParsedDoc parse(String raw, String filename) {
        String normalized = raw.replace("\r\n", "\n").strip();
        String title = filename == null ? "未命名" : filename.replaceAll("\\.md$", "");
        String category = "general";
        String source = null;

        String[] lines = normalized.split("\n");
        int bodyStart = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (i == 0 && line.startsWith("#")) {
                title = line.replaceFirst("^#+\\s*", "").strip();
                bodyStart = i + 1;
                continue;
            }
            if (line.startsWith("分类:")) {
                category = line.substring(3).strip();
                bodyStart = i + 1;
            } else if (line.startsWith("来源:")) {
                source = line.substring(3).strip();
                bodyStart = i + 1;
            } else if (!line.isEmpty()) {
                break;
            }
        }
        String body = String.join("\n", Arrays.copyOfRange(lines, bodyStart, lines.length)).strip();
        return new ParsedDoc(title, category, source, body);
    }

    /**
     * 切片：先按空行分段，段落过长再按句子聚合。
     * 段落之间保留空行，让拼进提示词的片段仍然是可读的排版。
     */
    static List<String> split(String body) {
        List<String> units = new ArrayList<>();
        for (String paragraph : body.split("\n\\s*\n")) {
            String text = paragraph.strip();
            if (text.isEmpty()) {
                continue;
            }
            if (text.length() <= CHUNK_MAX_CHARS) {
                units.add(text);
                continue;
            }
            // 长段落拆成句子，逐句聚合到接近上限
            StringBuilder buffer = new StringBuilder();
            for (String sentence : text.split(SENTENCE_DELIMITER)) {
                if (sentence.isBlank()) {
                    continue;
                }
                if (buffer.length() > 0 && buffer.length() + sentence.length() > CHUNK_MAX_CHARS) {
                    units.add(buffer.toString().strip());
                    buffer.setLength(0);
                }
                buffer.append(sentence);
            }
            if (buffer.length() > 0) {
                units.add(buffer.toString().strip());
            }
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String unit : units) {
            if (current.length() > 0 && current.length() + unit.length() > CHUNK_MAX_CHARS) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(unit);
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private String read(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record ParsedDoc(String title, String category, String source, String body) {
    }
}
