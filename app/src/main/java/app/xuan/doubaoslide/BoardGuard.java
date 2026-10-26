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

    static void install(ClassLoader classLoader) {
        Class<?> cls;
        try {
            cls = XposedHelpers.findClassIfExists(JNI_CLASS, classLoader);
        } catch (Throwable t) {
            Log.e("查找 " + JNI_CLASS + " 失败", t);
            return;
        }
        if (cls == null) {
            Log.w("找不到 " + JNI_CLASS + "，模块将不启用（输入法版本可能变了）");
            return;
        }

        try {
            getCurrentKbdType = XposedHelpers.findMethodExactIfExists(cls, "getCurrentKbdType");
        } catch (Throwable t) {
            Log.e("查找 getCurrentKbdType 失败", t);
        }
        if (getCurrentKbdType == null) {
            Log.w("找不到 getCurrentKbdType，模块将不启用");
            return;
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
            return;
        }

        Log.always("棋盘守卫就绪：只武装 kPinyin26 / kEnglish26");
    }

    /** 当前棋盘是否是 26 键字母面；读不到就当作"不是"，交给引擎。 */
    static boolean isLetterBoard() {
        Object target = instance;
        Method method = getCurrentKbdType;
        if (target == null || method == null) {
            return false;
        }
        try {
            Object value = method.invoke(target);
            if (!(value instanceof Integer)) {
                return false;
            }
            int type = (Integer) value;
            return type == KBD_PINYIN_26 || type == KBD_ENGLISH_26;
        } catch (Throwable t) {
            Log.e("读取 getCurrentKbdType 失败", t);
            return false;
        }
    }
}
