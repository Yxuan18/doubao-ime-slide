package app.xuan.doubaoslide;

import static app.xuan.doubaoslide.GestureGate.ACTION_LEAVE;
import static app.xuan.doubaoslide.GestureGate.ACTION_POINTER_DOWN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import app.xuan.doubaoslide.GestureGate.Verdict;

/**
 * {@link GestureGate} 的判定测试。
 *
 * <p>这里锁的是「用户能不能打出错误的动作」。`v1.0.0` 的多指误触发就是这个类要防的
 * 那一个 bug：手指1 按 A、手指2 按 C，`dy` 变成 221px，UP 时触发了 selectAll。
 * {@code multiPointerNeverFires()} 就是它的回归用例——去掉 {@code onUp} 里的多指短路，
 * 这条会红（红→绿证据记在 ACCEPTANCE.md）。
 *
 * <p>视图尺寸取实测值 1080×909：FIRE=60px、GATE=12px。
 */
public class GestureGateTest {

    private static final int H = 909;

    /** 第 3 行 C 的纵坐标（实测 DOWN 431,629 字母=c）。 */
    private static final int Y_C = 629;

    /** 第 2 行 A 的纵坐标（实测 DOWN 104,408 字母=a）。 */
    private static final int Y_A = 408;

    // ---------- DOWN ----------

    @Test
    public void unmappedKeyPassesThrough() {
        GestureGate gate = new GestureGate();
        assertEquals(Verdict.PASS, gate.onDown(Y_C, H, null));
        assertFalse("没绑定的键不能武装", gate.deferred());
    }

    @Test
    public void mappedKeyIsCaptured() {
        GestureGate gate = new GestureGate();
        assertEquals(Verdict.CAPTURE, gate.onDown(Y_C, H, "copy"));
        assertTrue(gate.deferred());
    }

    // ---------- 三种收尾 ----------

    @Test
    public void tapReplays() {
        GestureGate gate = new GestureGate();
        gate.onDown(Y_C, H, "copy");
        assertEquals(Verdict.REPLAY, gate.onUp(Y_C));
    }

    @Test
    public void downSlideFires() {
        GestureGate gate = new GestureGate();
        gate.onDown(Y_C, H, "copy");
        assertEquals(Verdict.CAPTURE, gate.onEvent(2, Y_C + 100));
        assertEquals(Verdict.FIRE, gate.onUp(Y_C + 200));
    }

    @Test
    public void shortDownSlideIsDead() {
        GestureGate gate = new GestureGate();
        gate.onDown(Y_C, H, "copy");
        // 12 < 30 < 60：手抖，明确作废
        assertEquals(Verdict.DEAD, gate.onUp(Y_C + 30));
    }

    @Test
    public void upSlideReplays() {
        GestureGate gate = new GestureGate();
        gate.onDown(Y_C, H, "copy");
        assertEquals(Verdict.CAPTURE, gate.onEvent(2, Y_C - 100));
        assertEquals(Verdict.REPLAY, gate.onUp(Y_C - 160));
    }

    // ---------- 阈值边界 ----------

    @Test
    public void thresholdsComeFromViewHeight() {
        GestureGate gate = new GestureGate();
        gate.onDown(0, H, "copy");
        assertEquals(60, gate.fireAt());
        assertEquals(12, gate.gateAt());

        GestureGate tall = new GestureGate();
        tall.onDown(0, H * 2, "copy");
        assertEquals(120, tall.fireAt());
        assertEquals(24, tall.gateAt());
    }

    @Test
    public void exactlyGateDistanceStillReplays() {
        GestureGate gate = new GestureGate();
        gate.onDown(Y_C, H, "copy");
        // maxDy == gateAt 不算超过，仍按轻点补发
        assertEquals(Verdict.REPLAY, gate.onUp(Y_C + 12));
    }

    @Test
    public void exactlyFireDistanceIsStillDead() {
        GestureGate gate = new GestureGate();
        gate.onDown(Y_C, H, "copy");
        // maxDy == fireAt 不算超过：既不触发，也还没回到起点上方
        assertEquals(Verdict.DEAD, gate.onUp(Y_C + 60));
    }

    @Test
    public void slidingDownThenBackUpFires() {
        GestureGate gate = new GestureGate();
        gate.onDown(Y_C, H, "copy");
        gate.onEvent(2, Y_C + 20);              // 下探 20px
        gate.onEvent(2, Y_C + 5);               // 又回来了
        // 最大位移仍是 20（在死区），终点也没抬到起点上方 → 作废
        assertEquals(Verdict.DEAD, gate.onUp(Y_C + 5));
    }

    // ---------- 多指：v1.0.0 的回归用例 ----------

    @Test
    public void multiPointerNeverFires() {
        GestureGate gate = new GestureGate();
        // 手指1 按 A
        assertEquals(Verdict.CAPTURE, gate.onDown(Y_A, H, "selectAll"));
        // 手指2 按 C：比手指1 低 221px
        assertEquals(Verdict.CAPTURE, gate.onEvent(ACTION_POINTER_DOWN, Y_C));
        // 手指1 抬手（引擎报的坐标可能是另一根手指的）
        assertEquals("多指手势位移归属不可信，绝不能触发快捷键",
                Verdict.REPLAY, gate.onUp(Y_C));
    }

    @Test
    public void multiPointerBlocksDeadZoneToo() {
        GestureGate gate = new GestureGate();
        gate.onDown(Y_A, H, "selectAll");
        gate.onEvent(ACTION_POINTER_DOWN, Y_A + 30);
        // 30px 落在死区，但多指一律补发，不做"作废"这种判断
        assertEquals(Verdict.REPLAY, gate.onUp(Y_A + 30));
    }

    // ---------- 状态复位 ----------

    @Test
    public void leaveClearsStateAndGuards() {
        GestureGate gate = new GestureGate();
        gate.onDown(Y_C, H, "copy");
        assertEquals(Verdict.CAPTURE, gate.onEvent(ACTION_LEAVE, Y_C));
        assertFalse("离开键盘后不能再扣事件", gate.deferred());
        // 后续事件一律放行
        assertEquals(Verdict.PASS, gate.onEvent(2, Y_C + 50));
        assertEquals(Verdict.PASS, gate.onUp(Y_C + 50));
    }

    @Test
    public void eventsWithoutDownPassThrough() {
        GestureGate gate = new GestureGate();
        assertEquals(Verdict.PASS, gate.onEvent(2, Y_C));
        assertEquals(Verdict.PASS, gate.onUp(Y_C));
    }

    @Test
    public void upClearsDeferredSoTheNextEventPasses() {
        GestureGate gate = new GestureGate();
        gate.onDown(Y_C, H, "copy");
        gate.onUp(Y_C);
        assertFalse(gate.deferred());
        assertEquals(Verdict.PASS, gate.onEvent(2, Y_C + 10));
    }

    @Test
    public void secondGestureStartsFromZero() {
        GestureGate gate = new GestureGate();
        gate.onDown(Y_C, H, "copy");
        gate.onEvent(2, Y_C + 200);
        gate.onUp(Y_C + 200);                   // 第一次：FIRE
        gate.reset();

        gate.onDown(Y_C, H, "copy");
        assertEquals("上一次的位移不能带到下一次", 0, gate.maxDy());
        assertEquals(Verdict.REPLAY, gate.onUp(Y_C));
    }
}
