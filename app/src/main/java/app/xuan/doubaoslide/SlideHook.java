package app.xuan.doubaoslide;

import android.view.View;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import app.xuan.doubaoslide.GestureGate.Verdict;

import static app.xuan.doubaoslide.GestureGate.ACTION_CANCEL;
import static app.xuan.doubaoslide.GestureGate.ACTION_DOWN;
import static app.xuan.doubaoslide.GestureGate.ACTION_LEAVE;
import static app.xuan.doubaoslide.GestureGate.ACTION_POINTER_DOWN;
import static app.xuan.doubaoslide.GestureGate.ACTION_UP;

/**
 * 下滑快捷键的核心：在 {@code KeyboardView.nativeTouch} 上做两段式闸门。
 *
 * <p>手势约定：按住一个字母，向下滑再松手 → 执行该字母的快捷键；轻点 → 正常打字。
 *
 * <p><b>为什么不把 DOWN 交给引擎，而是扣住它。</b>最早的写法是"放 DOWN、吞 MOVE/UP"。
 * 那条路有一个看不见的泄漏：引擎在收到 DOWN 的那一刻就把字母放进它自己的预编辑缓冲区，
 * 吞掉后续事件并不能撤回它——实测被拦截的一次 C 在 {@code getCandlist()} 里留下了 "c"，
 * 下一次点击把它一起提交成 "cq"，而 {@code getTextBeforeCursor()} 看不到这段引擎内组合，
 * 所以用错探针的测试会假通过。扣住 DOWN 之后引擎什么都没收到：不需要 {@code leave(-1)} 收尾，
 * 不需要清理长按气泡（它的长按计时器根本没启动），也没有遗留组合。
 *
 * <p>因此凡是不触发快捷键的手势，都必须由我们<b>原样补发</b>：扣住 DOWN 的同时把后续
 * MOVE 逐个记下来，UP 时按原坐标、原时间戳、原顺序把 DOWN + 全部 MOVE + UP 送回
 * {@code nativeTouch}。只补一次合成点击是不够的——上滑输符号依赖引擎看到滑动过程，
 * 压平位移等于把上滑变成点击（实测导致五个被映射键的上滑符号失效）。
 *
 * <p><b>长按归还。</b>扣住 DOWN 有一个代价：引擎的 native 长按计时器从没启动过，
 * 于是别的字母长按会弹出「大写 / 字符 / 小写」选择框，被映射的五个键长按什么都不发生
 * （用户报告的 bug）。实测那个弹框在按住 350–450ms 就出现，早于引擎 501ms 发出的
 * {@code action=-1}，所以它由 native 层自己画，唯一的开关就是"引擎收到过 DOWN"。
 * 修法是按住 {@link GestureGate#HAND_BACK_MS} 还没滑过触发线时，把这一次按下补发给
 * 引擎，让它的计时器跑起来；之后 MOVE 仍然扣住不转发，抬手时再决定：
 * <ul>
 *   <li>滑过触发线 → 触发快捷键，然后给引擎发 {@code CANCEL} 撤回它刚建立的预编辑；</li>
 *   <li>没滑过 → 按原顺序补发 MOVE + UP，引擎看到的就是一次普通长按。</li>
 * </ul>
 * 归还之后必须继续扣住 MOVE，不能让引擎看到滑动过程：实测 {@code DOWN} 后跟一次
 * MOVE 再 {@code CANCEL} 撤不掉预编辑（候选栏仍在），只有"引擎只见过 DOWN"时
 * {@code CANCEL} 才干净地把组合区还原成空白。
 *
 * <p>三段判定（位移按视图高度等比缩放，实测 909 高时 GATE=12px、FIRE=60px）：
 * <pre>
 *   maxDy > FIRE              触发快捷键
 *   向下的 maxDy > GATE       作废：不打字也不触发（防止手抖的点击被当成快捷键）
 *   其余（轻点 / 上滑 / 横滑） 原样补发整段事件流，引擎照原生规则处理
 *   见过第二根手指（action=5）跳过快捷键判定，一律补发（位移归属不可信）
 * </pre>
 */
final class SlideHook {

    private static final String KEYBOARD_VIEW = "com.bytedance.android.input.keyboard.KeyboardView";
    private static final String IME_SERVICE = "com.bytedance.android.doubaoime.ImeService";

    /**
     * 映射：字母 → InputConnection 动作名。
     *
     * <p>选的是桌面端最顺手的五个：⌘C / ⌘V / ⌘X / ⌘A / ⌘Z。改这里就能换键位，
     * 动作名要和 {@link Actions} 认识的名字一致。
     */
    private static final Map<Character, String> SHORTCUTS;

    static {
        Map<Character, String> map = new HashMap<Character, String>();
        map.put('c', "copy");
        map.put('v', "paste");
        map.put('x', "cut");
        map.put('a', "selectAll");
        map.put('z', "undo");
        SHORTCUTS = Collections.unmodifiableMap(map);
    }

    /**
     * 补发时重入钩子的标记。
     *
     * <p>反射调用 {@code nativeTouch} 会再次走进这个钩子（Xposed 钩的是方法本身），
     * 没有这个标记，补发的 DOWN 会被自己再扣一次，形成死循环。
     */
    private static final ThreadLocal<Boolean> REPLAYING = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };

    /** 补发用的方法句柄，免得每次反射查找。 */
    private static volatile Method nativeTouch;

    /**
     * 判定状态机。
     *
     * <p>判据全在 {@link GestureGate} 里：纯 Java、不碰反射、有单元测试。这里只负责
     * 把引擎的事件喂给它，再按它给的结论去扣事件、触发动作或补发。
     *
     * <p>触摸事件在 UI 线程上串行到达，键盘也只有一个 KeyboardView（实测），
     * 所以一份状态足够。
     */
    private static final GestureGate GATE = new GestureGate();

    /**
     * 补发缓冲：扣住期间的那些事件。
     *
     * <p>只存 UP 时可能要用到的东西（起点、时间戳、每条 MOVE），判定不需要它们，
     * 所以不放进 {@link GestureGate}。
     */
    private static final Gesture GESTURE = new Gesture();

    private SlideHook() {
    }

    /**
     * @return 是否连输入连接也接好了。触摸钩子装不上就直接 false（模块不启用）；
     *         触摸钩子装上但 ImeService 监听失败时也返回 false——这时手势能判，
     *         但没有执行动作的通道，等于不完整。
     */
    static boolean install(ClassLoader classLoader) {
        Class<?> keyboardView;
        try {
            keyboardView = XposedHelpers.findClassIfExists(KEYBOARD_VIEW, classLoader);
        } catch (Throwable t) {
            Log.e("查找 " + KEYBOARD_VIEW + " 失败", t);
            return false;
        }
        if (keyboardView == null) {
            Log.w("找不到 " + KEYBOARD_VIEW + "，模块未挂载");
            return false;
        }

        Method target = findNativeTouch(keyboardView);
        if (target == null) {
            Log.w("找不到 nativeTouch(long,int,int,int,long)，模块未挂载");
            return false;
        }

        try {
            target.setAccessible(true);
            nativeTouch = target;
            XposedBridge.hookMethod(target, TOUCH_HOOK);
        } catch (Throwable t) {
            Log.e("挂载 nativeTouch 失败", t);
            return false;
        }

        boolean serviceReady = hookImeService(classLoader);

        if (serviceReady) {
            Log.always("手势闸门就绪：C 复制 / V 粘贴 / X 剪切 / A 全选 / Z 撤销");
        } else {
            // 手势能判、动作没通道：依旧是半装配，别让日志读起来像全好了。
            Log.w("手势钩子已装，动作通道未接上：手势会被判出来，但快捷键不会执行");
        }
        return serviceReady;
    }

    private static Method findNativeTouch(Class<?> keyboardView) {
        for (Method method : keyboardView.getDeclaredMethods()) {
            if (!"nativeTouch".equals(method.getName())) {
                continue;
            }
            Class<?>[] p = method.getParameterTypes();
            if (p.length == 5
                    && p[0] == long.class
                    && p[1] == int.class
                    && p[2] == int.class
                    && p[3] == int.class
                    && p[4] == long.class) {
                return method;
            }
        }
        return null;
    }

    /**
     * 记住 ImeService 实例，动作层靠它拿输入连接。
     *
     * <p>豆包会同时留不止一个实例，只有被绑定的那个有连接（实测），所以既要都留下，
     * 也要在使用时挨个问。
     */
    /** @return 是否接上了输入连接的来源；false 表示手势能判但没有执行动作的通道。 */
    private static boolean hookImeService(ClassLoader classLoader) {
        Class<?> service;
        try {
            service = XposedHelpers.findClassIfExists(IME_SERVICE, classLoader);
        } catch (Throwable t) {
            Log.e("查找 " + IME_SERVICE + " 失败", t);
            return false;
        }
        if (service == null) {
            Log.w("找不到 " + IME_SERVICE + "，快捷键将找不到输入连接");
            return false;
        }
        try {
            XposedBridge.hookAllConstructors(service, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Actions.captureService(param.thisObject);
                }
            });
        } catch (Throwable t) {
            Log.e("监听 ImeService 构造函数失败", t);
            return false;
        }
        return true;
    }

    private static final XC_MethodHook TOUCH_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (Boolean.TRUE.equals(REPLAYING.get())) {
                return;                     // 我们自己补发的事件，原样放行
            }
            try {
                dispatch(param);
            } catch (Throwable t) {
                // 处理失败就把状态清干净、把这一下交给引擎：宁可打出一个字母，
                // 也不要因为模块异常把用户的触摸吃掉。
                // 三件缺一不可：不撤任务，排队的归还会用上一手势的坐标补发一次幽灵按住；
                // 不重置 GATE，后续事件仍被当成"我们扣着的"；不重置 GESTURE，
                // 补发缓冲还留着旧动作名，UP 时会把 null 动作送进动作层。
                Log.e("手势处理异常，交回引擎", t);
                cancelHandBack();
                GATE.reset();
                GESTURE.reset();
            }
        }
    };

    private static void dispatch(XC_MethodHook.MethodHookParam param) {
        // 每根手指只查一次配置有没有变（一次 stat）。放在这里是为了让下面那行
        // DEBUG 事件日志也用上刚读到的值。
        if (((Number) param.args[3]).intValue() == ACTION_DOWN) {
            Log.refresh();
        }
        long handle = ((Number) param.args[0]).longValue();
        int x = ((Number) param.args[1]).intValue();
        int y = ((Number) param.args[2]).intValue();
        int action = ((Number) param.args[3]).intValue();
        long time = ((Number) param.args[4]).longValue();
        // 标定用：看清引擎实际透出哪些动作码（0 按下 / 1 抬起 / 2 移动 / 5 第二根手指）。
        if (Log.DEBUG) {
            Log.i("事件 action=" + action + " handle=" + handle + " " + x + "," + y
                    + " deferred=" + GATE.deferred());
        }

        if (action == ACTION_DOWN) {
            onDown(param, handle, x, y, time);
            return;
        }

        if (action == ACTION_UP) {
            cancelHandBack();
            Verdict verdict = GATE.onUp(x, y);
            if (verdict != Verdict.PASS) {
                finish(param, x, y, time, verdict);
            }
            return;                         // PASS = 不是我们扣下的手势，原样放行
        }

        // "该吞还是该放"只由 GestureGate 一处决定。这里不再自己判断 LEAVE，
        // 否则判据会读到一个刚被自己改写的状态（那个写法曾让"先滑过触发线、
        // 再停住"的手势静默失效）。
        boolean ownedBefore = GATE.engineOwned();
        Verdict verdict = GATE.onEvent(action, x, y);
        if (verdict == Verdict.PASS) {
            if (!ownedBefore && GATE.engineOwned() && Log.DEBUG) {
                // 长按浮层里开始横向选字：这一下从此归引擎，模块不再扣事件、不再触发。
                Log.i("长按选字：横向拖动交给引擎 " + x + "," + y + " maxDy=" + GATE.maxDy());
            }
            return;                         // 不是我们扣下的手势（含已归还时的 LEAVE），一律放行
        }
        // LEAVE 不进补发缓冲：它不是手指的位移，补发回去只会让引擎把它当成一次抖动。
        if (action != ACTION_LEAVE) {
            GESTURE.recordMove(x, y, action, time);
            if (Log.DEBUG) {
                Log.i("扣住 MOVE action=" + action + " " + x + "," + y);
                if (action == ACTION_POINTER_DOWN) {
                    Log.i("多指输入：本次手势不判快捷键");
                }
            }
        }
        param.setResult(null);              // 扣住：等 UP 时再决定补发哪段
    }

    private static void onDown(XC_MethodHook.MethodHookParam param, long handle,
                               int x, int y, long time) {
        // 只有第一根手指会走到这里（第二根是 action=5，由 GestureGate.onEvent 处理）。
        // 无论后面走哪条路，先清一次状态：把"上一条事件流丢了 UP"的残局收干净。
        GATE.reset();
        GESTURE.reset();

        if (!(param.thisObject instanceof View)) {
            return;
        }
        View view = (View) param.thisObject;
        int w = view.getWidth();
        int h = view.getHeight();

        // 非 26 键字母面（数字面、符号面、表情面……）原样交给引擎。
        // 棋盘号跟着 DOWN 一起记：标定时要看清每块棋盘返回什么（已知 0/2/5，
        // 符号面与表情面的取值一直没测过）。
        int kbd = BoardGuard.kbdType();
        boolean letterBoard = BoardGuard.isLetterBoard(kbd);
        char letter = letterBoard ? Geometry.letterAt(x, y, w, h) : 0;
        // DEBUG 期用来标定坐标：每个 DOWN 都记下视图尺寸、棋盘号和它落在哪个键上。
        if (Log.DEBUG) {
            Log.i("DOWN " + x + "," + y + " view=" + w + "x" + h + " kbd=" + kbd
                    + (letterBoard ? " 字母=" + letter : ""));
        }
        if (!letterBoard) {
            return;
        }
        String action = SHORTCUTS.get(letter);
        if (GATE.onDown(x, y, h, action) != Verdict.CAPTURE) {
            return;                         // 未映射的键，引擎全看
        }

        GESTURE.begin(view, handle, x, y, time, letter, action);
        scheduleHandBack(view);
        if (Log.DEBUG) {
            Log.i("扣住 DOWN " + x + "," + y + " 字母=" + letter + " (" + w + "x" + h + ")");
        }

        // 关键一步：引擎看不到这次按下，也就不会往它自己的组合缓冲区里放字母。
        param.setResult(null);
    }

    /**
     * 抬手之后按判定结论收尾。
     *
     * <p>三种结局各有落法：触发动作、明确作废、整段补发。判定本身在
     * {@link GestureGate}，这里只做动作。{@code PASS} 不会走到这里——那说明这一下
     * 从一开始就不是我们扣住的。
     */
    private static void finish(XC_MethodHook.MethodHookParam param,
                               int x, int y, long time, Verdict verdict) {
        Gesture g = GESTURE;
        boolean handed = GATE.handedBack();
        param.setResult(null);              // 无论哪种结局，这一下 UP 都不交给引擎

        switch (verdict) {
            case FIRE: {
                if (param.thisObject instanceof View) {
                    Actions.haptic((View) param.thisObject);
                }
                boolean ok = Actions.perform(g.action);
                if (Log.DEBUG) {
                    Log.i("手势 " + g.letter + " -> " + g.action + " ok=" + ok
                            + " dy=" + GATE.maxDy() + " (fire>" + GATE.fireAt() + ")"
                            + (handed ? " 归还后触发" : ""));
                }
                if (handed) {
                    // 引擎收到过归还的 DOWN，组合区里正躺着这个字母。撤掉它，
                    // 否则它会在下一次提交时一起进编辑框。
                    revokeComposition(param, x, y, time, g);
                }
                break;
            }
            case DEAD:
                // 向下滑过了死区、又没到触发线：明确作废。
                if (Log.DEBUG) {
                    Log.i("手势作废：dy=" + GATE.maxDy() + " 在 " + GATE.gateAt()
                            + " 与 " + GATE.fireAt() + " 之间");
                }
                if (handed) {
                    revokeComposition(param, x, y, time, g);
                }
                break;
            case REPLAY:
                // 轻点、上滑、横滑、多指：按原坐标、原时间戳、原顺序整段送回，引擎照
                // 它自己的规则处理。上滑输符号就是在这里恢复的；多指时位移归属不可信，
                // 只能这样。已经归还过的手势不再重发 DOWN——引擎手里已经有了。
                replayGesture(param, x, y, time, g, !handed);
                break;
            default:
                break;
        }

        GESTURE.reset();
        GATE.reset();
    }

    /**
     * 按住到点后把这一次按下补发给引擎，让它的 native 长按计时器启动。
     *
     * <p>为什么必须有定时器：手指不动时引擎不发任何事件（实测按住 700ms 只有
     * DOWN 和 501ms 的 LEAVE），所以"按住够久"这件事只能在 DOWN 时预约，不能等事件。
     *
     * <p>时间基准不拿来比较：引擎给的事件时钟与本进程 {@code SystemClock} 不一定同源，
     * 比较两者的差值会得到荒唐的结果。定时器负责时序，{@link GestureGate#onHandback()}
     * 只负责"现在还该不该归还"。
     */
    private static void scheduleHandBack(final View view) {
        final long handle = GESTURE.handle;
        final int x0 = GESTURE.x0;
        final int y0 = GESTURE.y0;
        final long downTime = GESTURE.downTime;
        final int generation = GESTURE.generation;
        Runnable task = new Runnable() {
            @Override
            public void run() {
                if (generation != GESTURE.generation) {
                    return;                 // 这一下早就结束了，队列里剩的是旧任务
                }
                if (GATE.onHandback() != Verdict.HANDBACK) {
                    return;                 // 已经在往下滑、或者多指：不归还
                }
                handBack(view, handle, x0, y0, downTime);
            }
        };
        GESTURE.handBack = task;
        if (!view.postDelayed(task, GestureGate.HAND_BACK_MS)) {
            GESTURE.handBack = null;
            Log.w("归还任务未能排队（视图可能已 detach），这次长按浮层不会出现");
        }
    }

    /** 抬手时撤掉还没跑到的归还任务。 */
    private static void cancelHandBack() {
        Runnable task = GESTURE.handBack;
        GESTURE.handBack = null;
        View view = GESTURE.view;
        if (task != null && view != null) {
            view.removeCallbacks(task);
        }
    }

    /**
     * 把这一次按下补发给引擎。
     *
     * <p>带原始时间戳：引擎据此认为这一下已经按了很久，不必再等一个完整的计时周期。
     */
    private static void handBack(View view, long handle, int x0, int y0, long downTime) {
        Method method = nativeTouch;
        if (method == null || view == null) {
            // 反射目标与调用目标都先判空：这条红线不是形式主义，输入法崩一次的代价太高。
            return;
        }
        REPLAYING.set(Boolean.TRUE);
        try {
            method.invoke(view, handle, x0, y0, ACTION_DOWN, downTime);
            if (Log.DEBUG) {
                Log.i("长按归还：" + GESTURE.letter + " 引擎开始长按计时");
            }
        } catch (Throwable t) {
            Log.e("长按归还失败，这一下继续按原规则处理", t);
        } finally {
            REPLAYING.remove();
        }
    }

    /**
     * 撤回引擎里的预编辑。
     *
     * <p>归还过 DOWN 之后，引擎的组合区里已经有这个字母了。触发快捷键或明确作废时
     * 必须把它撤掉，否则它会在下一次提交时跟着进编辑框（这是 v1 那个 `cq` 泄漏的
     * 同类问题）。
     *
     * <p>只在"引擎只见过 DOWN"时可靠：实测 DOWN 后跟一次 MOVE 再 CANCEL，候选栏
     * 不会回到空白。所以归还之后 MOVE 仍然扣住不转发。
     */
    private static void revokeComposition(XC_MethodHook.MethodHookParam param,
                                          int x, int y, long time, Gesture g) {
        Method method = nativeTouch;
        if (method == null || !(param.thisObject instanceof View)) {
            return;
        }
        View view = (View) param.thisObject;
        REPLAYING.set(Boolean.TRUE);
        try {
            method.invoke(view, g.handle, x, y, ACTION_CANCEL, time);
            if (Log.DEBUG) {
                Log.i("撤回预编辑：" + g.letter + "（CANCEL）");
            }
        } catch (Throwable t) {
            Log.e("撤回预编辑失败，字母可能留在引擎组合区", t);
        } finally {
            REPLAYING.remove();
        }
    }

    /**
     * 把扣下的整段手势补发给引擎。
     *
     * <p>顺序、坐标、时间戳都保持用户真实输入的样子：引擎靠相邻 MOVE 算方向，
     * 只发 DOWN+UP 会让它把上滑看成点击。
     */
    private static void replayGesture(XC_MethodHook.MethodHookParam param,
                                      int x, int y, long time, Gesture g, boolean sendDown) {
        Method method = nativeTouch;
        if (method == null || !(param.thisObject instanceof View)) {
            // 没有补发通道，至少把字母提交出去，别让这次点击凭空消失。
            Actions.commitLetter(g.letter);
            return;
        }
        View view = (View) param.thisObject;

        // 整段补发共用一个守卫：DOWN 与 MOVE 之间不能有"钩子重新武装"的窗口。
        // 已经归还过的手势，引擎手里本来就有 DOWN，这里只补后半段。
        // 这个名字是"引擎是否已经有 DOWN"，不是"我们是否发出过 DOWN"——异常分支靠它
        // 决定该不该直接提交字母，读反就会把字母提交两次。
        boolean engineHasDown = !sendDown;
        REPLAYING.set(Boolean.TRUE);
        try {
            if (sendDown) {
                method.invoke(view, g.handle, g.x0, g.y0, ACTION_DOWN, g.downTime);
                engineHasDown = true;
            }
            for (int i = 0; i < g.moveCount; i++) {
                method.invoke(view, g.handle, g.moveX[i], g.moveY[i], g.moveAction[i], g.moveTime[i]);
            }
            method.invoke(view, g.handle, x, y, ACTION_UP, time);
            if (Log.DEBUG) {
                Log.i("补发手势：" + g.letter + " moves=" + g.moveCount + " dy=" + (y - g.y0)
                        + (sendDown ? "" : " 后半段"));
            }
        } catch (Throwable t) {
            if (engineHasDown) {
                // DOWN 已送出而后续事件没送全，引擎里会残留一次按下。这里不再补别的事件
                // （任何补偿都可能把字母提交两次），等下一次 DOWN 让它自己收尾。
                Log.e("补发手势失败，引擎可能残留一次按下", t);
            } else {
                // DOWN 都没送出去：引擎手里是干净的，直接提交字母不会重复。
                Log.e("补发 DOWN 失败，改为直接提交字母", t);
                Actions.commitLetter(g.letter);
            }
        } finally {
            REPLAYING.remove();
        }
    }

    /** 补发一次手势所需的数据。判定状态在 {@link GestureGate}，不在这里。 */
    private static final class Gesture {

        /**
         * 中间事件最多记这么多条。
         *
         * <p>按住滑半秒大约 30 条；512 条足够覆盖任何真实手势。溢出后不再记录，
         * 补发时引擎仍能凭已有轨迹判断方向，不会把上滑变成点击。
         */
        private static final int MAX_MOVES = 512;

        long handle;
        long downTime;
        int x0;
        int y0;
        char letter;
        String action;

        View view;
        Runnable handBack;
        /**
         * 每按一次加一。队列里的旧归还任务靠它认出自己已经过期
         * （上一段事件流丢了 UP 时，旧任务会晚一步跑到现在这一下上）。
         */
        int generation;

        int moveCount;
        final int[] moveX = new int[MAX_MOVES];
        final int[] moveY = new int[MAX_MOVES];
        final int[] moveAction = new int[MAX_MOVES];
        final long[] moveTime = new long[MAX_MOVES];

        void begin(View view, long handle, int x, int y, long time,
                   char letter, String action) {
            this.view = view;
            this.generation++;
            this.handBack = null;
            this.handle = handle;
            this.downTime = time;
            this.x0 = x;
            this.y0 = y;
            this.letter = letter;
            this.action = action;
            this.moveCount = 0;
        }

        void recordMove(int x, int y, int action, long time) {
            if (moveCount >= MAX_MOVES) {
                // 显式降级：丢掉的是补发轨迹的尾巴，引擎可能因此看不出方向。
                Log.once("moves-overflow",
                        "补发缓冲已满（" + MAX_MOVES + " 条），后续 MOVE 被丢弃；长手势的补发轨迹会不完整");
                return;
            }
            moveX[moveCount] = x;
            moveY[moveCount] = y;
            moveAction[moveCount] = action;
            moveTime[moveCount] = time;
            moveCount++;
        }

        /**
         * 清空这一份缓冲。
         *
         * <p>{@code generation++} 不是可选项：排队中的归还任务只靠"生成号没变"来判断
         * 自己还属于当前手势。不递增，异常路径清完状态后旧任务照样会跑，用上一手势的
         * 坐标补发一次用户没做过的按住。
         *
         * <p>{@code view = null} 顺手放掉静态字段对 {@code View} 的长期强引用。
         */
        void reset() {
            generation++;
            letter = 0;
            action = null;
            moveCount = 0;
            handBack = null;
            view = null;
        }
    }
}
