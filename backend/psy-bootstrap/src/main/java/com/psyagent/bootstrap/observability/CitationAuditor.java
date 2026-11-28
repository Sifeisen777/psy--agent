package com.psyagent.bootstrap.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引用编号校验。
 * <p>
 * 只做**越界检查**：回答里出现 {@code [4]} 但本轮只有 3 条参考资料，说明模型编造了引用。
 * <p>
 * 刻意不做的两件事：
 * <p>
 * 1. **不做语义校验**（判断这条资料是否真的支撑那句话）——需要再调一次模型，
 *    成本高于错误本身，而且结论同样不可靠；
 * 2. **不阻断、不改写正文**——流式内容已经推给用户了，撤回不存在。
 *    校验结果只进日志与 done 帧，作为"这段回答的引用可不可信"的信号。
 */
public final class CitationAuditor {

    private static final Logger log = LoggerFactory.getLogger(CitationAuditor.class);

    /** 只匹配单个或两位数编号，避免把 markdown 链接的 [text] 误判成引用。 */
    private static final Pattern REF = Pattern.compile("\\[(\\d{1,2})]");

    private CitationAuditor() {
    }

    /**
     * @param text          模型回答全文
     * @param citationCount 本轮实际提供的参考资料条数
     * @return 越界引用的个数；0 表示全部合法
     */
    public static int outOfRange(String text, int citationCount) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int invalid = 0;
        StringBuilder bad = new StringBuilder();
        Matcher matcher = REF.matcher(text);
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            if (index < 1 || index > citationCount) {
                invalid++;
                if (bad.length() < 40) {
                    bad.append('[').append(index).append(']');
                }
            }
        }
        if (invalid > 0) {
            log.warn("citation out of range count={} refs={} tokens={}", invalid, citationCount, bad);
        }
        return invalid;
    }
}
