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
 *   按住 HANDBACK_MS 没滑过触发线        HANDBACK 把这一次按下归还引擎，长按弹框才能出现
 * </pre>
 *
 * <p>多指那条来自实测：第二根手指按下时引擎发的是 {@code action=5}，而 {@code handle}
 * 在整段多点事件里是同一个值、{@code MOVE} 也不带指针下标，所以无从区分某条坐标属于
 * 哪根手指。不做这条限制时，手指1 按 A、手指2 按 C 会让 `dy` 变成 221px 并触发 selectAll。
 *
 * <p>归还那条来自用户报告的 bug：其他字母长按会弹出「大写 / 字符 / 小写」选择框，
 * 被映射的 C、V 长按没反应。原因是这个模块扣住了 DOWN，而那个弹框由引擎的 native
 * 长按计时器驱动——引擎没收到 DOWN，计时器就不会启动（实测弹框在 350–450ms 出现，
 * 早于引擎 501ms 发出的 {@code action=-1}，说明它由 native 层自己画）。所以按住
 * 一小段时间仍没下滑时，把这次按下补发给引擎，让它的计时器跑起来。
 */
final class GestureGate {

    /** 与 {@code nativeTouch(long handle, int x, int y, int action, long time)} 的动作码一致。 */
    static final int ACTION_DOWN = 0;
    static final int ACTION_UP = 1;
    static final int ACTION_MOVE = 2;
    /** 取消。实测引擎收到它会把预编辑缓冲整个撤掉，连长按弹框一起收起。 */
    static final int ACTION_CANCEL = 3;
    /** 第二根手指按下。实测引擎原样透出 5，而不是又发一个 0。 */
    static final int ACTION_POINTER_DOWN = 5;
    /** 手指离开键盘区域时引擎收到 -1。 */
    static final int ACTION_LEAVE = -1;

    /**
     * 按住多久没有滑过触发线就把这一次按下归还给引擎。
     *
     * <p>取值要在两件事之间：短到弹框不至于比未映射键晚太多，长到普通下滑不会被误判成长按。
     * 普通下滑从按下到越过 60px 通常不到 200ms；300ms 留了余量。归还后引擎从收到 DOWN
     * 那一刻起算它自己的长按计时，所以弹框约在 300 + 350 = 650ms 出现（实测弹框相对
     * 引擎收到 DOWN 约 350–450ms）。
     */
    static final long HAND_BACK_MS = 300L;

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
        REPLAY,
        /** 按住太久没下滑：把这次按下补发给引擎，让它的长按计时器启动。 */
        HANDBACK
    }

    private boolean deferred;
    private boolean multiPointer;
    private boolean handedBack;
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
     * <p>返回 {@link Verdict#CAPTURE} 表示扣住，调用方据此不要把它记进补发缓冲。
     *
     * <p>{@code ACTION_LEAVE} 单独一条规则：<b>它永远不清判定状态</b>。扣住的手势里，
     * 这条 LEAVE 由 Java 层 501ms 的计时器发出（{@code KeyboardView.delaySendLongPressMessage}），
     * 与引擎是否见过 DOWN 无关，所以它对"这一下该怎么收尾"没有任何信息量；清掉状态
     * 反而会让抬手时判不出来。实测反例：先滑过触发线、再停住、超过 501ms 才抬手，
     * 清状态的那一版快捷键静默失效。
     *
     * <p>已归还时返回 {@link Verdict#PASS} 让引擎看到它——引擎要靠这条 LEAVE 画长按
     * 浮层的第二格；未归还时吞掉（引擎根本没收到过 DOWN，没有东西可收尾）。
     */
    Verdict onEvent(int action, int y) {
        if (!deferred) {
            return Verdict.PASS;
        }
        if (action == ACTION_LEAVE) {
            return handedBack ? Verdict.PASS : Verdict.CAPTURE;
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
     * 长按计时到点：按住够久又没滑过触发线，就把这一次按下还给引擎。
     *
     * <p>只在还扣着、还没归还、且没滑出触发线时成立。已经在往下滑的那一下不归还：
     * 它要走快捷键，不该惊动引擎。呼叫方的定时器负责"按住够久"这件事——这里不比较
     * 时间戳，因为引擎给的事件时钟与本进程的 {@code SystemClock} 基准不一定相同。
     */
    Verdict onHandback() {
        if (!deferred || handedBack) {
            return Verdict.PASS;
        }
        if (multiPointer) {
            // 多指时一律补发，不单独归还：归还后引擎会看到半截手势。
            return Verdict.PASS;
        }
        if (maxDy > fireAt()) {
            return Verdict.PASS;
        }
        handedBack = true;
        return Verdict.HANDBACK;
    }

    /**
     * 抬手：给出这一次手势怎么收尾。
     *
     * <p>调用后 {@link #deferred()} 变成 false，但 {@link #maxDy()} / {@link #fireAt()} /
     * {@link #gateAt()} / {@link #handedBack()} 仍读得到这次手势的值，直到调用方 {@link #reset()}。
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

    /** 这一次按下是不是已经归还给引擎了。归还后抬手可能要用 CANCEL 撤回引擎里的预编辑。 */
    boolean handedBack() {
        return handedBack;
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
        handedBack = false;
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
