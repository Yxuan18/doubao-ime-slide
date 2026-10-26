package app.xuan.doubaoslide;

import android.view.View;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

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

    /** 与 {@code nativeTouch(long handle, int x, int y, int action, long time)} 的动作码一致。 */
    private static final int ACTION_DOWN = 0;
    private static final int ACTION_UP = 1;
    /** 第二根手指按下。实测引擎原样透出 5，而不是又发一个 0。 */
    private static final int ACTION_POINTER_DOWN = 5;
    /** 手指离开键盘区域时引擎收到 -1。 */
    private static final int ACTION_LEAVE = -1;

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
     * 手势状态。
     *
     * <p>触摸事件在 UI 线程上串行到达，键盘也只有一个 KeyboardView（实测），
     * 所以一份状态足够。
     *
     * <p>多指是实测出来的，不是推的：第二根手指按下时引擎发的是 {@code action=5}
     * （{@code ACTION_POINTER_DOWN}），而 {@code handle} 在整段多点事件里是同一个值，
     * 不能靠它区分指针；{@code MOVE} 也不带指针下标。也就是说，第二根手指按下之后，
     * 我们收到的任何一条坐标都可能属于另一根手指——拿它算位移会把一次多指输入判成
     * 从第一根手指起点滑到第二根手指位置的"下滑"，实测触发过错误的 selectAll。
     * 因此一旦见到 {@code action=5}，这次手势就不再判快捷键，UP 时整段补发。
     */
    private static final Gesture GESTURE = new Gesture();

    private SlideHook() {
    }

    static void install(ClassLoader classLoader) {
        Class<?> keyboardView;
        try {
            keyboardView = XposedHelpers.findClassIfExists(KEYBOARD_VIEW, classLoader);
        } catch (Throwable t) {
            Log.e("查找 " + KEYBOARD_VIEW + " 失败", t);
            return;
        }
        if (keyboardView == null) {
            Log.w("找不到 " + KEYBOARD_VIEW + "，模块未挂载");
            return;
        }

        Method target = findNativeTouch(keyboardView);
        if (target == null) {
            Log.w("找不到 nativeTouch(long,int,int,int,long)，模块未挂载");
            return;
        }

        try {
            target.setAccessible(true);
            nativeTouch = target;
            XposedBridge.hookMethod(target, TOUCH_HOOK);
        } catch (Throwable t) {
            Log.e("挂载 nativeTouch 失败", t);
            return;
        }

        hookImeService(classLoader);

        Log.always("手势闸门就绪：C 复制 / V 粘贴 / X 剪切 / A 全选 / Z 撤销");
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
    private static void hookImeService(ClassLoader classLoader) {
        Class<?> service;
        try {
            service = XposedHelpers.findClassIfExists(IME_SERVICE, classLoader);
        } catch (Throwable t) {
            Log.e("查找 " + IME_SERVICE + " 失败", t);
            return;
        }
        if (service == null) {
            Log.w("找不到 " + IME_SERVICE + "，快捷键将找不到输入连接");
            return;
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
        }
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
        Log.i("事件 action=" + action + " handle=" + handle + " " + x + "," + y
                + " deferred=" + GESTURE.deferred);

        if (action == ACTION_DOWN) {
            onDown(param, handle, x, y, time);
            return;
        }

        if (!GESTURE.deferred) {
            return;                         // 不是我们扣下的手势，一律放行
        }

        if (action == ACTION_LEAVE) {
            GESTURE.reset();
            param.setResult(null);          // 引擎没收到 DOWN，也不需要 LEAVE
            return;
        }

        if (action == ACTION_POINTER_DOWN) {
            // 第二根手指按下了。从这里开始坐标归属不可信，本次手势一律走补发。
            GESTURE.multiPointer = true;
            Log.i("多指输入：本次手势不判快捷键");
        }

        if (!GESTURE.multiPointer) {
            int dy = y - GESTURE.y0;
            if (dy > GESTURE.maxDy) {
                GESTURE.maxDy = dy;
            }
        }

        if (action == ACTION_UP) {
            onUp(param, x, y, time);
            return;
        }

        GESTURE.recordMove(x, y, action, time);
        Log.i("扣住 MOVE action=" + action + " " + x + "," + y);
        param.setResult(null);              // 手势中间的 MOVE 全部扣住，等 UP 时决定补发哪段
    }

    private static void onDown(XC_MethodHook.MethodHookParam param, long handle,
                               int x, int y, long time) {
        // 只有第一根手指会走到这里（第二根是 action=5，见 dispatch）。这里清一次
        // 状态，是为了把"上一条事件流丢了 UP"的残局收干净。
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
        Log.i("DOWN " + x + "," + y + " view=" + w + "x" + h + " 字母=" + letter);
        String action = SHORTCUTS.get(letter);
        if (action == null) {
            return;                         // 未映射的键，引擎全看
        }

        GESTURE.begin(handle, x, y, time, w, h, letter, action);
        Log.i("扣住 DOWN " + x + "," + y + " 字母=" + letter + " (" + w + "x" + h + ")");

        // 关键一步：引擎看不到这次按下，也就不会往它自己的组合缓冲区里放字母。
        param.setResult(null);
    }

    private static void onUp(XC_MethodHook.MethodHookParam param, int x, int y, long time) {
        Gesture g = GESTURE;
        g.deferred = false;
        param.setResult(null);              // 无论哪种结局，这一下 UP 都不交给引擎

        int fireAt = Math.round(g.h * Geometry.FIRE_FRAC);
        int gateAt = Math.round(g.h * Geometry.GATE_FRAC);
        int dy = y - g.y0;

        if (g.multiPointer) {
            // 多指手势不判快捷键：这一段位移属于哪根手指已经分不清了，
            // 猜错的代价是一次莫名其妙的复制/全选，比"这次不触发"糟得多。
            replayGesture(param, x, y, time, g);
        } else if (g.maxDy > fireAt) {
            if (param.thisObject instanceof View) {
                Actions.haptic((View) param.thisObject);
            }
            boolean ok = Actions.perform(g.action);
            Log.i("手势 " + g.letter + " -> " + g.action + " ok=" + ok
                    + " dy=" + g.maxDy + " (fire>" + fireAt + ")");
        } else if (g.maxDy > gateAt && dy >= -gateAt) {
            // 向下滑过了死区、又没到触发线：明确作废。
            Log.i("手势作废：dy=" + g.maxDy + " 在 " + gateAt + " 与 " + fireAt + " 之间");
        } else {
            // 轻点、上滑、横滑：把原始事件流原样送回去，引擎按它自己的规则处理
            // （上滑符号就是在这里恢复的）。
            replayGesture(param, x, y, time, g);
        }

        g.reset();
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
            Log.i("补发手势：" + g.letter + " moves=" + g.moveCount + " dy=" + (y - g.y0));
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

    /** 一次手势的全部状态。 */
    private static final class Gesture {

        /**
         * 中间事件最多记这么多条。
         *
         * <p>按住滑半秒大约 30 条；512 条足够覆盖任何真实手势。溢出后不再记录，
         * 补发时引擎仍能凭已有轨迹判断方向，不会把上滑变成点击。
         */
        private static final int MAX_MOVES = 512;

        boolean deferred;
        /** 见过第二根手指（action=5）。一旦为真，maxDy 不再可信，也不判快捷键。 */
        boolean multiPointer;
        long handle;
        long downTime;
        int x0;
        int y0;
        int w;
        int h;
        int maxDy;
        char letter;
        String action;

        int moveCount;
        final int[] moveX = new int[MAX_MOVES];
        final int[] moveY = new int[MAX_MOVES];
        final int[] moveAction = new int[MAX_MOVES];
        final long[] moveTime = new long[MAX_MOVES];

        void begin(long handle, int x, int y, long time, int w, int h, char letter, String action) {
            this.deferred = true;
            this.multiPointer = false;
            this.handle = handle;
            this.downTime = time;
            this.x0 = x;
            this.y0 = y;
            this.w = w;
            this.h = h;
            this.maxDy = 0;
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
            deferred = false;
            multiPointer = false;
            letter = 0;
            action = null;
            maxDy = 0;
            moveCount = 0;
        }
    }
}
