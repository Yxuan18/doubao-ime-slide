package app.xuan.doubaoslide;

/**
 * 手势判定：一个纯状态机。
 *
 * <p><b>为什么单独一个类。</b>{@link SlideHook} 里唯一一处「错了会让用户打出错误动作」的
 * 逻辑就是这里——`v1.0.0` 的多指误触发（A + C 两指触发 selectAll）出在这一段；
 * 而同时它又是整个模块里唯一可以脱离 Android 与 Xposed 测试的东西。所以把它做成
 * 纯 Java（不 import Android、不碰反射、不分配对象），就能用 JVM 单元测试锁住，
 * {@code SlideHook} 只留反射与补发。
 *
 * <p><b>职责边界。</b>这个类只判定，不记账。中间事件（MOVE）的缓冲留在
 * {@link SlideHook.Gesture}，因为它只在补发时用到，判定不需要。
 *
 * <p><b>判定规则</b>（位移按视图高度等比缩放，实测 909 高时 GATE=12px、FIRE=60px）：
 * <pre>
 *   maxDy &gt; fireAt                      FIRE   触发快捷键
 *   向下的 maxDy &gt; gateAt 且终点没回到上方  DEAD   作废：不打字也不触发
 *   其余（轻点 / 上滑 / 横滑）            REPLAY 整段补发，引擎按原生规则处理
 *   见过第二根手指（action=5）           REPLAY 一律补发：位移归属不可信
 * </pre>
 *
 * <p>多指那条来自实测：第二根手指按下时引擎发的是 {@code action=5}，而 {@code handle}
 * 在整段多点事件里是同一个值、{@code MOVE} 也不带指针下标，所以无从区分某条坐标属于
 * 哪根手指。不做这条限制时，手指1 按 A、手指2 按 C 会让 `dy` 变成 221px 并触发 selectAll。
 */
final class GestureGate {

    /** 与 {@code nativeTouch(long handle, int x, int y, int action, long time)} 的动作码一致。 */
    static final int ACTION_DOWN = 0;
    static final int ACTION_UP = 1;
    /** 第二根手指按下。实测引擎原样透出 5，而不是又发一个 0。 */
    static final int ACTION_POINTER_DOWN = 5;
    /** 手指离开键盘区域时引擎收到 -1。 */
    static final int ACTION_LEAVE = -1;

    /** 一条事件处理完之后，调用方该做什么。 */
    enum Verdict {
        /** 引擎的事，原样放行。 */
        PASS,
        /** 扣住，引擎看不到。 */
        CAPTURE,
        /** 抬手了，触发快捷键。 */
        FIRE,
        /** 抬手了，明确作废：不打字也不触发。 */
        DEAD,
        /** 抬手了，把扣住的整段事件流补发回引擎。 */
        REPLAY
    }

    private boolean deferred;
    private boolean multiPointer;
    private int y0;
    private int h;
    private int maxDy;

    /**
     * 第一根手指按下。
     *
     * <p>顺手清一次状态，是为了把「上一条事件流丢了 UP」的残局收干净。
     *
     * @param actionName 这个键绑定的动作名；{@code null} 表示没绑定，原样交给引擎。
     *                   「当前是不是 26 键字母面」由调用方先判（那里要靠反射问引擎），
     *                   这里只认这个键有没有绑定动作。
     */
    Verdict onDown(int y, int h, String actionName) {
        reset();
        if (actionName == null) {
            return Verdict.PASS;
        }
        deferred = true;
        this.y0 = y;
        this.h = h;
        return Verdict.CAPTURE;
    }

    /**
     * DOWN 与 UP 之外的事件：MOVE、第二根手指按下、离开键盘。
     *
     * <p>返回 {@link Verdict#CAPTURE} 表示扣住；{@code ACTION_LEAVE} 也会返回 CAPTURE，
     * 但它已经把状态清空了——调用方据此不要再记 MOVE。
     */
    Verdict onEvent(int action, int y) {
        if (!deferred) {
            return Verdict.PASS;
        }
        if (action == ACTION_LEAVE) {
            reset();
            return Verdict.CAPTURE;
        }
        if (action == ACTION_POINTER_DOWN) {
            // 从这里开始坐标归属不可信，位移不再累计。
            multiPointer = true;
        }
        if (!multiPointer) {
            track(y);
        }
        return Verdict.CAPTURE;
    }

    /**
     * 抬手：给出这一次手势怎么收尾。
     *
     * <p>调用后 {@link #deferred()} 变成 false，但 {@link #maxDy()} / {@link #fireAt()} /
     * {@link #gateAt()} 仍读得到这次手势的值，直到调用方 {@link #reset()}。
     */
    Verdict onUp(int y) {
        if (!deferred) {
            return Verdict.PASS;
        }
        if (!multiPointer) {
            track(y);
        }
        deferred = false;

        if (multiPointer) {
            return Verdict.REPLAY;
        }
        if (maxDy > fireAt()) {
            return Verdict.FIRE;
        }
        if (maxDy > gateAt() && y - y0 >= -gateAt()) {
            return Verdict.DEAD;
        }
        return Verdict.REPLAY;
    }

    /** 这一下是不是我们扣住的。 */
    boolean deferred() {
        return deferred;
    }

    /** 本次手势向下的最大位移。 */
    int maxDy() {
        return maxDy;
    }

    /** 触发线，按视图高度缩放（实测 909 高时 60px）。 */
    int fireAt() {
        return Math.round(h * Geometry.FIRE_FRAC);
    }

    /** 死区线，按视图高度缩放（实测 909 高时 12px）。 */
    int gateAt() {
        return Math.round(h * Geometry.GATE_FRAC);
    }

    void reset() {
        deferred = false;
        multiPointer = false;
        maxDy = 0;
        y0 = 0;
        h = 0;
    }

    private void track(int y) {
        int dy = y - y0;
        if (dy > maxDy) {
            maxDy = dy;
        }
    }
}
