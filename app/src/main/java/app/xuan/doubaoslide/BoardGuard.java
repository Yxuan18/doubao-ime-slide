package app.xuan.doubaoslide;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 棋盘守卫：只在 26 键字母面上启用下滑手势。
 *
 * <p>为什么需要它：几何模型在数字面同样能"算出"字母——数字面的左侧符号列正好压着
 * a 的位置，纯几何无法区分两块棋盘。视图树、视图字段、服务对象图、InputViewModel
 * 的静态量在换棋盘时逐字节相同（实测），Java 层拿不到可靠信号。
 *
 * <p>引擎自己知道当前是哪块棋盘：{@code KeyboardJni.getCurrentKbdType()} 返回
 * 0 = kPinyin26、2 = kEnglish26、5 = kNumber（实测：26 键字母面 → 0，点 123 → 5，
 * 点返回 → 0）。只有两块 26 键字母面被武装，其余棋盘原样交给引擎。
 *
 * <p>两条硬约束，来自实测：
 * <ul>
 *   <li>只调实例方法，绝不调 {@code KeyboardJni} 的静态入口——没有实例时那样调会把
 *       输入法进程打挂（实测一次）。</li>
 *   <li>每次按下都重新读，不缓存：棋盘随时会换，缓存值会在切换后误判。</li>
 * </ul>
 *
 * <p>失败模式是安全的：类找不到、实例没拿到、调用抛异常，一律返回 false（不武装），
 * 用户得到的是原样的键盘。
 */
final class BoardGuard {

    private static final String JNI_CLASS = "com.bytedance.android.doubaoime.KeyboardJni";

    /** 26 键字母面的两个取值。 */
    private static final int KBD_PINYIN_26 = 0;
    private static final int KBD_ENGLISH_26 = 2;

    private static volatile Object instance;
    private static volatile Method getCurrentKbdType;

    private BoardGuard() {
    }

    /** @return 是否装好；false 表示模块会整体保持不启用（键盘原样）。 */
    static boolean install(ClassLoader classLoader) {
        Class<?> cls;
        try {
            cls = XposedHelpers.findClassIfExists(JNI_CLASS, classLoader);
        } catch (Throwable t) {
            Log.e("查找 " + JNI_CLASS + " 失败", t);
            return false;
        }
        if (cls == null) {
            Log.w("找不到 " + JNI_CLASS + "，模块将不启用（输入法版本可能变了）");
            return false;
        }

        try {
            getCurrentKbdType = XposedHelpers.findMethodExactIfExists(cls, "getCurrentKbdType");
        } catch (Throwable t) {
            Log.e("查找 getCurrentKbdType 失败", t);
        }
        if (getCurrentKbdType == null) {
            Log.w("找不到 getCurrentKbdType，模块将不启用");
            return false;
        }
        try {
            // 现在它是 public，不设也能调；设一次是为了它哪天变成包私有时不会静默失效
            // （静默失效的表现是模块悄悄不工作，排查起来很费劲）。
            getCurrentKbdType.setAccessible(true);
        } catch (Throwable t) {
            Log.w("getCurrentKbdType 无法设为可访问，继续按 public 调用：" + t);
        }

        try {
            XposedBridge.hookAllConstructors(cls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // 构造函数里对象还没初始化完，这里只留引用，业务逻辑放到用的时候。
                    instance = param.thisObject;
                }
            });
        } catch (Throwable t) {
            Log.e("监听 KeyboardJni 构造函数失败", t);
            return false;
        }

        Log.always("棋盘守卫就绪：只武装 kPinyin26 / kEnglish26");
        return true;
    }

    /** 读不到棋盘号时的返回值。不是引擎的合法取值（实测只有 0/2/5 一类非负数）。 */
    static final int UNKNOWN = -1;

    /**
     * 当前棋盘号；读不到返回 {@link #UNKNOWN}。
     *
     * <p>判定与标定共用这一次调用：日志里看到的号必须是判定用的那个号，
     * 否则"日志说 kbd=5、模块却武装了"这种矛盾要查很久。
     */
    static int kbdType() {
        Object target = instance;
        Method method = getCurrentKbdType;
        if (target == null || method == null) {
            // 静默返回会让"装上了但按键没反应"完全无线索：日志里只有注入行，
            // 分不清是没装上、还是装上了没拿到引擎实例。这里补一条只打一次的线索。
            Log.once("board-guard",
                    "还没拿到 KeyboardJni 实例，本次触摸不武装（装了模块但按键没反应时看这条）");
            return UNKNOWN;
        }
        try {
            Object value = method.invoke(target);
            return value instanceof Integer ? (Integer) value : UNKNOWN;
        } catch (Throwable t) {
            Log.once("board-read", "读取 getCurrentKbdType 失败：" + t);
            return UNKNOWN;
        }
    }

    /** 当前棋盘是否是 26 键字母面；读不到就当作"不是"，交给引擎。 */
    static boolean isLetterBoard() {
        return isLetterBoard(kbdType());
    }

    /** 同上，但棋盘号由调用方读过。 */
    static boolean isLetterBoard(int type) {
        return type == KBD_PINYIN_26 || type == KBD_ENGLISH_26;
    }
}
