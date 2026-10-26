package app.xuan.doubaoslide;

import java.io.File;
import java.io.FileWriter;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

/**
 * 模块日志。
 *
 * <p>{@link #DEBUG} 是**运行时开关**：默认取 {@link Config#DEFAULT_DEBUG}，之后由
 * {@link #refresh()} 从模块 App 写的配置文件里读。设置页改完，下一次按键就生效，
 * 不需要重启输入法。
 *
 * <p>为什么不能是 {@code static final}：编译期常量会被 javac 内联，取 {@code false} 时
 * {@code if (Log.DEBUG)} 整块被删掉，运行时就再也打开不了了。
 *
 * <p>安装成功那一行和所有异常始终输出。
 */
final class Log {

    static volatile boolean DEBUG = Config.DEFAULT_DEBUG;

    private static final String TAG = "[DoubaoSlide] ";

    /** {@link #once} 已经打过的 key。预期内的失败不该每次触摸都刷屏。 */
    private static final Set<String> ONCE = Collections.synchronizedSet(new HashSet<String>());

    /** 只在注入进程里可用；为 null 表示还没打开过。 */
    private static XSharedPreferences prefs;

    private Log() {
    }

    /**
     * 重新读一次设置。每根手指按下时调一次，代价是一次 {@code stat}。
     *
     * <p>只做一件事：把文件里的值搬进 {@link #DEBUG}。读不到就沿用当前值——
     * 设置坏了不该让键盘变哑。
     */
    static void refresh() {
        try {
            XSharedPreferences p = prefs;
            if (p == null) {
                p = new XSharedPreferences(Config.PKG, Config.PREFS);
                p.reload();                 // 首次必须显式读，hasFileChanged 只反映"之后变没变"
                prefs = p;
            } else if (p.hasFileChanged()) {
                p.reload();
            }
            DEBUG = p.getBoolean(Config.KEY_DEBUG, Config.DEFAULT_DEBUG);
        } catch (Throwable t) {
            // 类不在（模块 App 进程）、文件不可读、格式坏了，都走这里。
            once("config-read", "读设置失败，沿用当前值 DEBUG=" + DEBUG + "：" + t);
        }
    }

    /**
     * 记一行心跳，供设置页显示「最近注入」。
     *
     * <p>注入进程与模块 App 是两个 UID，唯一的共用可写位置就是共享 prefs 目录里的
     * 这个文件（设置页会把它预建成 0666）。写失败只影响诊断显示，不影响手势。
     */
    static void heartbeat(String processName) {
        try {
            File dir = prefsDir();
            if (dir == null) {
                return;
            }
            File hb = new File(dir, Config.HEARTBEAT);
            long len = hb.length();
            // 超过 4KB 就从头写，避免长期累积；正常情况下每次注入只有一行。
            boolean append = len > 0 && len < 4096;
            FileWriter w = new FileWriter(hb, append);
            try {
                w.write(System.currentTimeMillis() + " " + processName
                        + " pid=" + android.os.Process.myPid() + "\n");
            } finally {
                w.close();
            }
        } catch (Throwable t) {
            once("heartbeat", "写心跳失败，设置页的「最近注入」会不准：" + t);
        }
    }

    private static File prefsDir() {
        XSharedPreferences p = prefs;
        if (p == null) {
            p = new XSharedPreferences(Config.PKG, Config.PREFS);
            p.reload();
            prefs = p;
        }
        File f = p.getFile();
        return f == null ? null : f.getParentFile();
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
     */
    static void e(String msg, Throwable t) {
        XposedBridge.log(TAG + "E " + msg);
        if (t != null) {
            XposedBridge.log(t);
        }
    }
}
