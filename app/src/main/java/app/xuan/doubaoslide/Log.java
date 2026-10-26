package app.xuan.doubaoslide;

import de.robv.android.xposed.XposedBridge;

/**
 * 模块日志。
 *
 * <p>逐手势日志默认关闭：键盘上的每个触摸都会走到这里，全打出来会淹掉 LSPosed 日志。
 * 排障时把 {@link #DEBUG} 改成 true 重新构建。安装成功那一行和所有异常始终输出。
 */
final class Log {

    static final boolean DEBUG = false;

    private static final String TAG = "[DoubaoSlide] ";

    private Log() {
    }

    static void i(String msg) {
        if (DEBUG) {
            XposedBridge.log(TAG + msg);
        }
    }

    /** 始终输出的生命周期行：用来确认模块真的注入了这个进程。 */
    static void always(String msg) {
        XposedBridge.log(TAG + msg);
    }

    static void w(String msg) {
        XposedBridge.log(TAG + "W " + msg);
    }

    static void e(String msg, Throwable t) {
        XposedBridge.log(TAG + "E " + msg + (t == null ? "" : ": " + t));
    }
}
