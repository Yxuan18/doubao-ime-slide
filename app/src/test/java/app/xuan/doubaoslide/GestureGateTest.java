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

    @Test
    public void returningAboveTheOriginByMoreThanGateReplays() {
        GestureGate gate = new GestureGate();
        gate.onDown(Y_C, H, "copy");
        gate.onEvent(2, Y_C + 40);              // 下探 40（死区内）
        // 停在起点上方 13px：超过 gateAt，算"往上走了"，按轻点/上滑补发
        assertEquals(Verdict.REPLAY, gate.onUp(Y_C - 13));
    }

    @Test
    public void returningJustAboveTheOriginStaysDead() {
        GestureGate gate = new GestureGate();
        gate.onDown(Y_C, H, "copy");
        gate.onEvent(2, Y_C + 40);
        // 停在起点上方 12px：刚好卡在 gateAt 边界，仍算"没离开死区"
        assertEquals(Verdict.DEAD, gate.onUp(Y_C - 12));
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
    public void leaveWithoutHandbackSwallowsButKeepsState() {
        // 扣住的手势里，这条 LEAVE 由 Java 层 501ms 的计时器发出，与引擎是否见过 DOWN
        // 无关。它不能清掉判定状态，否则抬手时判不出来该触发还是补发（见下面那条回归）。
        GestureGate gate = new GestureGate();
        gate.onDown(Y_C, H, "copy");
        assertEquals("引擎没见过 DOWN，这条 LEAVE 要吞掉", Verdict.CAPTURE,
                gate.onEvent(ACTION_LEAVE, Y_C));
        assertTrue("LEAVE 不能把判定状态清掉", gate.deferred());
        // 轻点：抬手时仍然按原规则补发
        assertEquals(Verdict.REPLAY, gate.onUp(Y_C));
    }

    @Test
    public void leaveAfterSlidingPastFireKeepsState() {
        // 回归：先滑过触发线、再停住、超过引擎 501ms 的 LEAVE 才抬手。
        // 改之前 LEAVE 会 reset()，抬手被判成"不是我们扣下的手势"，快捷键静默失效。
        GestureGate gate = new GestureGate();
        gate.onDown(Y_C, H, "copy");
        assertEquals(Verdict.CAPTURE, gate.onEvent(2, Y_C + 100));
        assertEquals(Verdict.CAPTURE, gate.onEvent(ACTION_LEAVE, Y_C));
        assertEquals("LEAVE 之后抬手仍要触发快捷键", Verdict.FIRE, gate.onUp(Y_C + 120));
    }

    // ---------- 长按归还：用户报告的 bug（C/V 长按没有大小写弹框） ----------

    @Test
    public void longPressHandsBackToEngine() {
        GestureGate gate = new GestureGate();
        gate.onDown(Y_C, H, "copy");
        // 定时器到点：按住够久又没下滑，把这次按下还给引擎，让它的长按计时器启动。
        assertEquals(Verdict.HANDBACK, gate.onHandback());
        assertTrue("归还之后仍然扣着事件，还欠一个 UP", gate.deferred());
        assertTrue(gate.handedBack());
    }

    @Test
    public void handbackHappensOnlyOnce() {
        GestureGate gate = new GestureGate();
        gate.onDown(Y_C, H, "copy");
        assertEquals(Verdict.HANDBACK, gate.onHandback());
        // 队列里可能还排着一个旧任务：不能再归还一次，否则引擎会看到两个 DOWN。
        assertEquals(Verdict.PASS, gate.onHandback());
    }

    @Test
    public void noHandbackOnceSliding() {
        // 已经滑过触发线：这一下要走快捷键，不该惊动引擎。
        GestureGate gate = new GestureGate();
        gate.onDown(Y_C, H, "copy");
        gate.onEvent(2, Y_C + 100);
        assertEquals(Verdict.PASS, gate.onHandback());
        assertFalse(gate.handedBack());
    }

    @Test
    public void noHandbackForMultiPointer() {
        GestureGate gate = new GestureGate();
        gate.onDown(Y_C, H, "copy");
        gate.onEvent(ACTION_POINTER_DOWN, Y_C);
        assertEquals(Verdict.PASS, gate.onHandback());
        assertFalse(gate.handedBack());
    }

    @Test
    public void upAfterHandbackStillFires() {
        // 用户明确要求：长按之后这一下仍要能当快捷键用。
        GestureGate gate = new GestureGate();
        gate.onDown(Y_C, H, "copy");
        assertEquals(Verdict.HANDBACK, gate.onHandback());
        assertEquals(Verdict.CAPTURE, gate.onEvent(2, Y_C + 70));
        assertEquals(Verdict.FIRE, gate.onUp(Y_C + 120));
        assertTrue("抬手时调用方要据此补发 CANCEL", gate.handedBack());
    }

    @Test
    public void deadZoneAfterHandback() {
        GestureGate gate = new GestureGate();
        gate.onDown(Y_C, H, "copy");
        assertEquals(Verdict.HANDBACK, gate.onHandback());
        assertEquals(Verdict.DEAD, gate.onUp(Y_C + 35));
    }

    @Test
    public void leaveAfterHandbackPassesThroughButKeepsState() {
        // 引擎收到归还的 DOWN 之后 501ms 会发自己的 LEAVE。
        // 放行：引擎要靠它画长按浮层的第二格（大写 / 字符 / 小写）。
        // 但状态不能清，否则抬手时判定不出来。
        GestureGate gate = new GestureGate();
        gate.onDown(Y_C, H, "copy");
        assertEquals(Verdict.HANDBACK, gate.onHandback());
        assertEquals("归还后这条 LEAVE 要交给引擎", Verdict.PASS,
                gate.onEvent(ACTION_LEAVE, Y_C));
        assertTrue("LEAVE 不能把归还后的判定状态清掉", gate.deferred());
        assertEquals(Verdict.FIRE, gate.onUp(Y_C + 120));
    }

    @Test
    public void tapBeforeHandbackReplays() {
        GestureGate gate = new GestureGate();
        gate.onDown(Y_C, H, "copy");
        assertEquals(Verdict.REPLAY, gate.onUp(Y_C));
        assertFalse(gate.handedBack());
    }

    @Test
    public void resetClearsHandback() {
        GestureGate gate = new GestureGate();
        gate.onDown(Y_C, H, "copy");
        gate.onHandback();
        gate.reset();
        assertFalse(gate.handedBack());
        assertFalse(gate.deferred());
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
