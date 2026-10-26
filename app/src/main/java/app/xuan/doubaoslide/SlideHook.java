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

        Log.always("手势闸门就绪：C 复制 / V 粘贴 / X 剪切 / A 全选 / Z 撤销");
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
                // 处理失败就重置状态、把这一下交给引擎：宁可打出一个字母，
                // 也不要因为模块异常把用户的触摸吃掉。
                Log.e("手势处理异常，交回引擎", t);
                GESTURE.reset();
            }
        }
    };

    private static void dispatch(XC_MethodHook.MethodHookParam param) {
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
            Verdict verdict = GATE.onUp(y);
            if (verdict != Verdict.PASS) {
                finish(param, x, y, time, verdict);
            }
            return;                         // PASS = 不是我们扣下的手势，原样放行
        }

        Verdict verdict = GATE.onEvent(action, y);
        if (verdict == Verdict.PASS) {
            return;                         // 不是我们扣下的手势，一律放行
        }
        if (action == ACTION_LEAVE) {
            GESTURE.reset();                // 引擎没收到 DOWN，也不需要 LEAVE
        } else {
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
        if (!BoardGuard.isLetterBoard()) {
            return;
        }

        char letter = Geometry.letterAt(x, y, w, h);
        // DEBUG 期用来标定坐标：每个 DOWN 都记下视图尺寸和它落在哪个键上。
        if (Log.DEBUG) {
            Log.i("DOWN " + x + "," + y + " view=" + w + "x" + h + " 字母=" + letter);
        }
        String action = SHORTCUTS.get(letter);
        if (GATE.onDown(y, h, action) != Verdict.CAPTURE) {
            return;                         // 未映射的键，引擎全看
        }

        GESTURE.begin(handle, x, y, time, w, h, letter, action);
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
        param.setResult(null);              // 无论哪种结局，这一下 UP 都不交给引擎

        switch (verdict) {
            case FIRE: {
                if (param.thisObject instanceof View) {
                    Actions.haptic((View) param.thisObject);
                }
                boolean ok = Actions.perform(g.action);
                if (Log.DEBUG) {
                    Log.i("手势 " + g.letter + " -> " + g.action + " ok=" + ok
                            + " dy=" + GATE.maxDy() + " (fire>" + GATE.fireAt() + ")");
                }
                break;
            }
            case DEAD:
                // 向下滑过了死区、又没到触发线：明确作废。
                if (Log.DEBUG) {
                    Log.i("手势作废：dy=" + GATE.maxDy() + " 在 " + GATE.gateAt()
                            + " 与 " + GATE.fireAt() + " 之间");
                }
                break;
            case REPLAY:
                // 轻点、上滑、横滑、多指：按原坐标、原时间戳、原顺序整段送回，引擎照
                // 它自己的规则处理。上滑输符号就是在这里恢复的；多指时位移归属不可信，
                // 只能这样。
                replayGesture(param, x, y, time, g);
                break;
            default:
                break;
        }

        GESTURE.reset();
        GATE.reset();
    }

    /**
     * 把扣下的整段手势补发给引擎。
     *
     * <p>顺序、坐标、时间戳都保持用户真实输入的样子：引擎靠相邻 MOVE 算方向，
     * 只发 DOWN+UP 会让它把上滑看成点击。
     */
    private static void replayGesture(XC_MethodHook.MethodHookParam param,
                                      int x, int y, long time, Gesture g) {
        Method method = nativeTouch;
        if (method == null || !(param.thisObject instanceof View)) {
            // 没有补发通道，至少把字母提交出去，别让这次点击凭空消失。
            Actions.commitLetter(g.letter);
            return;
        }
        View view = (View) param.thisObject;

        // 整段补发共用一个守卫：DOWN 与 MOVE 之间不能有"钩子重新武装"的窗口。
        boolean downSent = false;
        REPLAYING.set(Boolean.TRUE);
        try {
            method.invoke(view, g.handle, g.x0, g.y0, ACTION_DOWN, g.downTime);
            downSent = true;
            for (int i = 0; i < g.moveCount; i++) {
                method.invoke(view, g.handle, g.moveX[i], g.moveY[i], g.moveAction[i], g.moveTime[i]);
            }
            method.invoke(view, g.handle, x, y, ACTION_UP, time);
            if (Log.DEBUG) {
                Log.i("补发手势：" + g.letter + " moves=" + g.moveCount + " dy=" + (y - g.y0));
            }
        } catch (Throwable t) {
            if (downSent) {
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
        int w;
        int h;
        char letter;
        String action;

        int moveCount;
        final int[] moveX = new int[MAX_MOVES];
        final int[] moveY = new int[MAX_MOVES];
        final int[] moveAction = new int[MAX_MOVES];
        final long[] moveTime = new long[MAX_MOVES];

        void begin(long handle, int x, int y, long time, int w, int h, char letter, String action) {
            this.handle = handle;
            this.downTime = time;
            this.x0 = x;
            this.y0 = y;
            this.w = w;
            this.h = h;
            this.letter = letter;
            this.action = action;
            this.moveCount = 0;
        }

        void recordMove(int x, int y, int action, long time) {
            if (moveCount >= MAX_MOVES) {
                return;
            }
            moveX[moveCount] = x;
            moveY[moveCount] = y;
            moveAction[moveCount] = action;
            moveTime[moveCount] = time;
            moveCount++;
        }

        void reset() {
            letter = 0;
            action = null;
            moveCount = 0;
        }
    }
}
