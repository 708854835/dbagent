package com.dbagent.engine.comment;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * COMMENT 确定性清洗：全/半角、空白、分隔符归一 + 常见代码清单模式提取；
 * 无法确定性解析返回 null（交 LLM 兜底）。
 */
class CommentNormalizerTest {

    @Test
    void fullWidthSpaceBetweenChineseIsRemoved() {
        CommentNormalizer.Normalized n = CommentNormalizer.clean("客户　编号");
        assertEquals("客户编号", n.name());
        assertNull(n.codes());
    }

    @Test
    void halfWidthSpaceBetweenChineseIsRemoved() {
        CommentNormalizer.Normalized n = CommentNormalizer.clean("客户 编号");
        assertEquals("客户编号", n.name());
    }

    @Test
    void fullWidthAlnumIsConverted() {
        CommentNormalizer.Normalized n = CommentNormalizer.clean("ＣＵＳＴ编号");
        assertEquals("CUST编号", n.name());
    }

    @Test
    void codeListWithColonAndDashesIsExtracted() {
        CommentNormalizer.Normalized n = CommentNormalizer.clean("性别：01-男，02-女");
        assertEquals("性别", n.name());
        assertEquals(Map.of("01", "男", "02", "女"), n.codes());
    }

    @Test
    void codeListWithParensEqualsAndSemicolonsIsExtracted() {
        CommentNormalizer.Normalized n = CommentNormalizer.clean("性别（01=男；02=女）");
        assertEquals("性别", n.name());
        assertEquals(Map.of("01", "男", "02", "女"), n.codes());
    }

    @Test
    void codeListWithThreeItemsIsExtracted() {
        CommentNormalizer.Normalized n = CommentNormalizer.clean("额度状态: 0-正常, 1-冻结, 2-注销");
        assertEquals("额度状态", n.name());
        assertEquals(Map.of("0", "正常", "1", "冻结", "2", "注销"), n.codes());
    }

    @Test
    void plainNameWithParensIsAccepted() {
        CommentNormalizer.Normalized n = CommentNormalizer.clean("客户编号(主)");
        assertEquals("客户编号(主)", n.name());
        assertNull(n.codes());
    }

    @Test
    void garbageWithUnresolvedMarkersIsNotDeterministic() {
        assertNull(CommentNormalizer.clean("性别？？详见附件【2023版】"));
        assertNull(CommentNormalizer.clean("（备注：以核心为准）"));
    }

    @Test
    void codeListWithoutNameIsNotDeterministic() {
        assertNull(CommentNormalizer.clean("01-男，02-女"));
    }

    @Test
    void blankOrNullIsNotDeterministic() {
        assertNull(CommentNormalizer.clean("   "));
        assertNull(CommentNormalizer.clean(null));
    }
}
