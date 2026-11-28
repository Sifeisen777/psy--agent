package com.psyagent.bootstrap.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 引用编号越界校验。
 * <p>
 * 这层的价值在于"抓住编造引用"：回答里出现 [4] 但只有 3 条资料，说明模型在虚构出处。
 * 只校验编号范围，不判断内容是否匹配——语义校验要再调一次模型，成本高于错误本身。
 */
class CitationAuditorTest {

    @ParameterizedTest(name = "[{index}] {0} / {1} 条 → 越界 {2}")
    @CsvSource(delimiter = '|', value = {
            "参考[1]和[2]              | 3 | 0",
            "只有一条[1]               | 1 | 0",
            "[3]                       | 3 | 0",
            "[4]                       | 3 | 1",
            "[0]                       | 3 | 1",
            "[10]                      | 9 | 1",
            "[1][2][9]                 | 2 | 1",
            "[5][5]                    | 3 | 2",
            "没有编号                   | 0 | 0"
    })
    @DisplayName("越界计数")
    void countsOutOfRange(String text, int citationCount, int expected) {
        assertThat(CitationAuditor.outOfRange(text, citationCount)).isEqualTo(expected);
    }

    @Test
    @DisplayName("空文本返回 0，不因 null 抛异常")
    void nullOrEmptyIsSafe() {
        assertThat(CitationAuditor.outOfRange(null, 3)).isZero();
        assertThat(CitationAuditor.outOfRange("", 3)).isZero();
    }

    @Test
    @DisplayName("Markdown 链接不算引用编号")
    void markdownLinkIsNotCitation() {
        String text = "可以看看[这篇文章](https://example.com)，再试试[呼吸练习](https://example.com/b)";
        assertThat(CitationAuditor.outOfRange(text, 0)).isZero();
    }

    @Test
    @DisplayName("零条资料时任何编号都算编造")
    void anyIndexIsInvalidWithoutReferences() {
        assertThat(CitationAuditor.outOfRange("研究表明[1]有效", 0)).isEqualTo(1);
    }
}
