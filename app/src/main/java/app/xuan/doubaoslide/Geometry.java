package app.xuan.doubaoslide;

/**
 * 26 键字母面的几何模型：把 {@code nativeTouch} 收到的按下点换算成字母。
 *
 * <p>为什么必须自己算：{@code KeyboardView} 的键由 native 绘制，没有命中测试的 Java
 * 入口，触点是唯一拿到手的坐标。模型是在一台 1080×909 的键盘视图上量出来的，
 * 这里全部写成比例，换分辨率时按比例缩放（实测 1080×909 下与真机按键中心逐一对齐）。
 *
 * <pre>
 *   纵向：顶部 151/909 是候选栏/工具条，不是键；其下 4 行等高的键区。
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

    /** 顶部非键区占视图高度的比例（实测 151 / 909）。 */
    private static final float STRIP_FRAC = 151f / 909f;

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

        float strip = h * STRIP_FRAC;
        if (y < strip) {
            return 0;                       // 候选栏 / 工具条
        }
        float rowHeight = (h - strip) / 4f;
        int row = (int) ((y - strip) / rowHeight);
        float unit = w / TOTAL_WEIGHT;      // 一个权重单位

        switch (row) {
            case 0: {
                int i = (int) (x / (w / 10f));
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
