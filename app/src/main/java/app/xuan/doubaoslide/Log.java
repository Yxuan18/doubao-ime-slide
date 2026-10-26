package app.xuan.doubaoslide;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;

/**
 * 模块日志。
 *
 * <p>逐手势日志受 {@link #DEBUG} 控制：键盘上的每个触摸都会走到这里，全打出来会淹掉
 * LSPosed 日志。安装成功那一行和所有异常始终输出。
 *
 * <p><b>当前固定为 {@code true}</b>（排障期）。下一步要做成"装完 APK 之后可运行时切换"——
 * 那需要模块 App 进程与输入法进程之间的跨进程通信，编译期常量做不到。
 * 在开关落地之前，<b>发版前必须改回 {@code false}</b>，否则交付包每次触摸都往
 * LSPosed 日志里写几行。
 */
final class Log {

    static final boolean DEBUG = true;

    private static final String TAG = "[DoubaoSlide] ";

    /** {@link #once} 已经打过的 key。预期内的失败不该每次触摸都刷屏。 */
    private static final Set<String> ONCE = Collections.synchronizedSet(new HashSet<String>());

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

    /**
     * 只打一次的警告。
     *
     * <p>给"预期内、会反复发生"的失败用：服务未绑定、设备不支持触感、补发缓冲溢出。
     * 这类事件每次触摸都可能出现，逐次打会把日志淹掉；一次不打就是静默吞掉。
     *
     * @param key 去重键。不同来源要用不同 key，否则一个来源的失败会压掉另一个的。
     */
    static void once(String key, String msg) {
        if (ONCE.add(key)) {
            XposedBridge.log(TAG + "W " + msg);
        }
    }

    /**
     * 异常。消息一行，堆栈单独一行（API 82 有 {@code log(Throwable)} 重载）。
     *
     * <p>堆栈只给真异常用：拿异常兜业务分支还打堆栈是 P1 红线，日志会变成堆栈垃圾场。
     * 这里几个调用点全是 catch 到的不该发生的错误。
     */
    static void e(String msg, Throwable t) {
        XposedBridge.log(TAG + "E " + msg);
        if (t != null) {
            XposedBridge.log(t);
        }
    }
}
