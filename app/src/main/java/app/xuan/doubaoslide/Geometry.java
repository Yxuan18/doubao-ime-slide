package app.xuan.doubaoslide;

/**
 * 26 键字母面的几何模型：把 {@code nativeTouch} 收到的按下点换算成字母。
 *
 * <p>为什么必须自己算：{@code KeyboardView} 的键由 native 绘制，没有命中测试的 Java
 * 入口，触点是唯一拿到手的坐标。纵向常数由布局 XML 推导、再用截屏逐像素核对；
 * 横向权重来自布局 XML。全部写成比例，换分辨率时按比例缩放
 * （1080×909 下与真机键面逐行对齐，见 ACCEPTANCE 第 46 项）。
 *
 * <pre>
 *   纵向：顶部 156.75/909 是候选栏 + 工具条 + 棋盘上内边距，不是键；
 *         底部还有 3.75/909 的内边距；中间 4 行等高。
 *   横向权重合计 100：
 *     第 1 行  q w e r t y u i o p        每键 10
 *     第 2 行  a  s d f g h j k  l         a=15，中间 7 键各 10，l=15
 *     第 3 行  ⇧  z x c v b n m  ⌫        ⇧=15，7 键在中间 70 的容器里，⌫=15
 *     第 4 行  123 / 空格 / 双英 等       不是字母，不参与映射
 * </pre>
 *
 * <p>坐标系与 {@code nativeTouch} 一致：视图内坐标，原点在键盘视图左上角。
 */
final class Geometry {

    /**
     * 顶部非键区占视图高度的比例。
     *
     * <p>来源是布局 XML 而不是量出来的近似值：棋盘类 {@code cls_input_kbd_adaptive}
     * 的 {@code padding1x="0,57,0,1.25"} 减去 {@code cls_input_kbd_inset_adaptive} 的
     * 上内缩 {@code 4.75}，即 (57 − 4.75) 个设计单位。设计单位 × 3.0 = 像素
     * （{@code min(屏宽,屏高)/360}，1080 宽即 3.0），得 156.75 px。
     *
     * <p>2026-10-27 用截屏逐像素核过：键面首行顶边在屏幕 y=1572，视图顶 1401，
     * 减上 margin 14.25 后得 156.75，与解析值一致。旧值 151 比真值小 5.75 px。
     */
    private static final float TOP_FRAC = 156.75f / 909f;

    /** 底部内边距占视图高度的比例：{@code padding1x} 的下边距 1.25 × 3.0 = 3.75 px。 */
    private static final float BOTTOM_FRAC = 3.75f / 909f;

    /** 滑动阈值占视图高度的比例：超过 GATE 才算滑动，超过 FIRE 才触发快捷键。 */
    static final float GATE_FRAC = 12f / 909f;
    static final float FIRE_FRAC = 60f / 909f;

    private static final String ROW1 = "qwertyuiop";
    /** 第 2 行中间 7 键；两端 a / l 单独占 15 权重。 */
    private static final String ROW2_MID = "sdfghjk";
    private static final String ROW3_MID = "zxcvbnm";

    private static final float LEFT_BIG = 15f;
    private static final float RIGHT_BIG = 85f;
    private static final float KEY_WEIGHT = 10f;
    private static final float TOTAL_WEIGHT = 100f;

    private Geometry() {
    }

    /**
     * 返回按下点对应的字母；不是字母键就返回 {@code 0}。
     *
     * <p>越界一律返回 0：宁可让这次触摸原样交给引擎，也不要在边界上猜一个键。
     */
    static char letterAt(int x, int y, int w, int h) {
        if (w <= 0 || h <= 0 || x < 0 || x >= w || y < 0 || y >= h) {
            return 0;
        }

        float top = h * TOP_FRAC;
        if (y < top) {
            return 0;                       // 候选栏 / 工具条
        }
        // 上下内边距都要扣掉：只扣上边会把行高压大 0.9 px，四行累计能差出一个边缘。
        float rowHeight = (h - top - h * BOTTOM_FRAC) / 4f;
        int row = (int) ((y - top) / rowHeight);
        float unit = w / TOTAL_WEIGHT;      // 一个权重单位

        switch (row) {
            case 0: {
                // 第 1 行每键 10 个权重；和下面两行一样用 unit 表达，别单独写 w/10f。
                int i = (int) (x / (KEY_WEIGHT * unit));
                return i >= 0 && i < ROW1.length() ? ROW1.charAt(i) : 0;
            }
            case 1: {
                if (x < LEFT_BIG * unit) {
                    return 'a';
                }
                if (x >= RIGHT_BIG * unit) {
                    return 'l';
                }
                int i = (int) ((x - LEFT_BIG * unit) / (KEY_WEIGHT * unit));
                return i >= 0 && i < ROW2_MID.length() ? ROW2_MID.charAt(i) : 0;
            }
            case 2: {
                if (x < LEFT_BIG * unit || x >= RIGHT_BIG * unit) {
                    return 0;               // ⇧ / ⌫
                }
                int i = (int) ((x - LEFT_BIG * unit) / (KEY_WEIGHT * unit));
                return i >= 0 && i < ROW3_MID.length() ? ROW3_MID.charAt(i) : 0;
            }
            default:
                return 0;                   // 底部功能行
        }
    }
}
