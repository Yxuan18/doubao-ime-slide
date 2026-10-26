package app.xuan.doubaoslide;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * {@link Geometry} 的回归测试。
 *
 * <p>这些用例锁的是两件事：**真机实测过的坐标必须仍然命中同一个键**，
 * 以及**比例换算在不同视图尺寸下给出同样的结果**。前者防止改权重把已知坐标
 * 改错（真机复验一次要装包、重启输入法、截图对位），后者防止把比例写死成
 * 1080×909 的绝对值。
 *
 * <p>没有覆盖的部分：候选栏出现时整块键盘会上移，那不是 Geometry 的责任，
 * 也没法在纯 JVM 里复现——那部分只能真机验收（见 ACCEPTANCE.md）。
 */
public class GeometryTest {

    /** 实测视图尺寸：1080×909，视图顶边在屏幕 y=1401。 */
    private static final int W = 1080;
    private static final int H = 909;

    // ---------- 真机实测坐标（ACCEPTANCE.md 的日志原文） ----------

    @Test
    public void measuredPointsHitTheSameKey() {
        // DOWN 403,663 view=1080x909 字母=c
        assertEquals('c', Geometry.letterAt(403, 663, W, H));
        // DOWN 485,471 view=1080x909 字母=f
        assertEquals('f', Geometry.letterAt(485, 471, W, H));
        // 扣住 DOWN 431,629 字母=c（v1.0.1 多指注入时的起点）
        assertEquals('c', Geometry.letterAt(431, 629, W, H));
        // 手指1 按 A：104,408 → 字母=a
        assertEquals('a', Geometry.letterAt(104, 408, W, H));
        // 手指2 按 C：431,629 已在上方覆盖，这里补 G 行对照
        assertEquals('g', Geometry.letterAt(540, 471, W, H));
    }

    // ---------- 纵向分区 ----------

    @Test
    public void topStripIsNotAKey() {
        // 151/909 以下是候选栏/工具条
        assertEquals(0, Geometry.letterAt(540, 0, W, H));
        assertEquals(0, Geometry.letterAt(540, 100, W, H));
        assertEquals(0, Geometry.letterAt(540, 150, W, H));
        // 刚进入键区就是第 1 行，x 落在第 6 个键（索引 5）
        assertEquals('y', Geometry.letterAt(540, 160, W, H));
    }

    @Test
    public void bottomFunctionRowIsNotAKey() {
        // 四行等高：(909-151)/4 = 189.5，第 4 行从 719.5 开始
        assertEquals(0, Geometry.letterAt(540, 730, W, H));
        assertEquals(0, Geometry.letterAt(540, 900, W, H));
    }

    @Test
    public void rowBoundariesFollowTheMeasuredStrip() {
        // 第 1 行 q..p：151.0–340.5
        assertEquals('q', Geometry.letterAt(10, 160, W, H));
        // 第 2 行 a..l：340.5–530.0
        assertEquals('a', Geometry.letterAt(10, 400, W, H));
        // 第 3 行 ⇧/z..m/⌫：530.0–719.5
        assertEquals('z', Geometry.letterAt(200, 540, W, H));
    }

    // ---------- 横向分区 ----------

    @Test
    public void firstRowSplitsIntoTenEqualKeys() {
        assertEquals('q', Geometry.letterAt(0, 246, W, H));
        assertEquals('q', Geometry.letterAt(107, 246, W, H));
        assertEquals('w', Geometry.letterAt(108, 246, W, H));
        assertEquals('p', Geometry.letterAt(1079, 246, W, H));
    }

    @Test
    public void secondRowHasWideAAndL() {
        // a 占前 15%（0–162）
        assertEquals('a', Geometry.letterAt(161, 435, W, H));
        // 中间 7 键各 10%（162–918）
        assertEquals('s', Geometry.letterAt(162, 435, W, H));
        assertEquals('k', Geometry.letterAt(917, 435, W, H));
        // l 占后 15%（918–1080）
        assertEquals('l', Geometry.letterAt(918, 435, W, H));
        assertEquals('l', Geometry.letterAt(1079, 435, W, H));
    }

    @Test
    public void thirdRowLeavesShiftAndBackspaceUnmapped() {
        // ⇧ 占前 15%，不映射
        assertEquals(0, Geometry.letterAt(80, 625, W, H));
        // 中间 70% 是 z x c v b n m
        assertEquals('z', Geometry.letterAt(162, 625, W, H));
        assertEquals('c', Geometry.letterAt(432, 625, W, H));
        assertEquals('m', Geometry.letterAt(917, 625, W, H));
        // ⌫ 占后 15%，不映射
        assertEquals(0, Geometry.letterAt(1000, 625, W, H));
    }

    // ---------- 边界与非法输入：一律返回 0 ----------

    @Test
    public void outOfRangeAlwaysReturnsZero() {
        assertEquals(0, Geometry.letterAt(-1, 246, W, H));
        assertEquals(0, Geometry.letterAt(W, 246, W, H));
        assertEquals(0, Geometry.letterAt(540, -1, W, H));
        assertEquals(0, Geometry.letterAt(540, H, W, H));
    }

    @Test
    public void degenerateViewSizeReturnsZero() {
        assertEquals(0, Geometry.letterAt(0, 0, 0, H));
        assertEquals(0, Geometry.letterAt(0, 0, W, 0));
        assertEquals(0, Geometry.letterAt(0, 0, -1080, -909));
    }

    // ---------- 比例不变性 ----------

    @Test
    public void sameRelativePointHitsTheSameKeyAtDoubleSize() {
        // 2 倍视图：2160×1818
        assertEquals('c', Geometry.letterAt(806, 1326, 2160, 1818));
        assertEquals('a', Geometry.letterAt(208, 816, 2160, 1818));
        // 半尺寸视图：540×454（不是真机尺寸，只验证比例换算）
        assertEquals('c', Geometry.letterAt(201, 331, 540, 454));
        assertEquals('a', Geometry.letterAt(52, 204, 540, 454));
    }

    // ---------- 阈值常量 ----------

    @Test
    public void thresholdsScaleWithViewHeight() {
        // 文档与验收线都按 909 高写成 12px / 60px
        assertEquals(12, Math.round(H * Geometry.GATE_FRAC));
        assertEquals(60, Math.round(H * Geometry.FIRE_FRAC));
        // 换到 1818 高必须自己翻倍，而不是保持 12/60
        assertEquals(24, Math.round(1818 * Geometry.GATE_FRAC));
        assertEquals(120, Math.round(1818 * Geometry.FIRE_FRAC));
    }
}
