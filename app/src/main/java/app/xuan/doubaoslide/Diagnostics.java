package app.xuan.doubaoslide;

import java.io.File;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 读诊断文件（心跳 / 执行结果），翻译成设置页能直接用的说法。
 *
 * <p>为什么单独成类：这是**文件格式的解析**，不依赖 {@code Activity}，也不该和排版混在一起。
 * 混在设置页里时，一个六百行的类同时管 UI、文件权限和解析，改哪一处都要先读完另外两处。
 *
 * <p>写入方是输入法进程（另一个 UID），读取方是设置页。格式一旦改动两边都要跟着改——
 * 所以格式只在这里和 {@link Log} 里各出现一次。
 */
final class Diagnostics {

    /** 键盘所在的主进程名。心跳文件里四个进程都会写，只有这一行代表键盘。 */
    static final String IME_PKG = "com.bytedance.android.doubaoime";

    private Diagnostics() {
    }

    /** 一次读到的全部诊断状态。字段直接对应设置页上的一句话或一行。 */
    static final class Snapshot {
        /** 找到键盘主进程那一行。心跳只在注入时写一次，所以"有这一行"≈"注入过"。 */
        boolean found;
        String ageText = "未知时间";
        String detail = "无记录（模块可能还没注入过）";
        /** 配置文件此刻读不读得到。两种失败要在状态句里说成不同的话。 */
        boolean configReadable;
        /** 钩子挂上了没有。false = 豆包改了钩子目标，模块抓不到按键。 */
        boolean hooked = true;
        /** 旧版心跳没有 hooked= 字段，这时不能判"没挂上"，只能说不知道。 */
        boolean hookedKnown;
        /** 动作通道（ImeService 构造钩子）接上了没有。 */
        boolean svc = true;
        boolean svcKnown;
        /** 最近一次快捷键执行；null = 还没执行过。 */
        String fireText;
        /** 最近一次执行的结果分类，见 {@code Actions.LAST_RESULT}。null = 无记录。 */
        String fireResult;
    }

    /**
     * 读心跳与执行结果。
     *
     * <p>心跳里四个进程（主进程、{@code :upgrade}、{@code :push}、{@code :sandboxed_process0}）
     * 都会往同一个文件追加，"最后一行"经常是无关的子进程。这里只认键盘所在的**主进程**
     * 那一行，其它进程只报个数。
     */
    static Snapshot read(File prefsFile) {
        Snapshot s = new Snapshot();
        if (prefsFile == null) {
            s.detail = "未知";
            return s;
        }
        s.configReadable = prefsFile.exists() && prefsFile.canRead();

        File file = new File(prefsFile.getParentFile(), Config.HEARTBEAT);
        if (!file.exists() || file.length() == 0) {
            return s;
        }
        try {
            String main = null;
            Set<String> procs = new HashSet<String>();
            for (String line : tail(file).split("\n")) {
                String t = line.trim();
                if (t.isEmpty()) {
                    continue;
                }
                String[] parts = t.split(" ", 3);
                if (parts.length < 2) {
                    continue;
                }
                procs.add(parts[1]);
                if (IME_PKG.equals(parts[1])) {
                    main = t;                   // 主进程：键盘在这里
                }
            }
            if (main == null) {
                s.detail = "只记录到子进程，还没记录到键盘进程\n共 " + procs.size() + " 个进程写过";
                return s;
            }
            s.found = true;
            s.ageText = ageTextOf(main);
            s.detail = detailOf(main) + "\n共 " + procs.size() + " 个进程写过";
            // 钩子与动作通道状态从主进程那一行里取，形如 "pid=123 hooked=true svc=true"。
            s.hookedKnown = true;
            s.hooked = main.contains("hooked=true");
            s.svcKnown = true;
            s.svc = main.contains("svc=true");
            readLastFire(file.getParentFile(), s);
        } catch (Throwable t) {
            // 设置页的诊断区是只读展示，读坏了要说出来，不能让它显示成"一切正常"。
            s.detail = "读取失败：" + t;
        }
        return s;
    }

    /**
     * 只读文件末尾一个窗口。
     *
     * <p>窗口大小与写侧共用 {@link Config#LOG_WINDOW}：不一致会切在半行上，
     * 解析出半个 {@code result=}，设置页就会误报。
     */
    private static String tail(File file) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - Config.LOG_WINDOW);
            byte[] buf = new byte[(int) (len - start)];
            raf.seek(start);
            raf.readFully(buf);
            return new String(buf, "UTF-8");
        }
    }

    /**
     * 读执行结果日志的最后一行：「epochMillis 动作 result=分类」。
     *
     * <p>只取最后一行——设置页要回答的是"最近一次怎么样"，不是历史统计。
     */
    private static void readLastFire(File dir, Snapshot s) {
        File file = new File(dir, Config.FIRE);
        if (!file.exists() || file.length() == 0) {
            return;
        }
        try {
            String last = null;
            for (String line : tail(file).split("\n")) {
                if (!line.trim().isEmpty()) {
                    last = line.trim();
                }
            }
            if (last == null) {
                return;
            }
            String[] parts = last.split(" ", 3);
            s.fireResult = parts.length > 2 && parts[2].startsWith("result=")
                    ? parts[2].substring("result=".length()) : null;
            String action = parts.length > 1 ? parts[1] : "?";
            String when = "";
            try {
                when = new SimpleDateFormat("MM-dd HH:mm", Locale.US)
                        .format(new Date(Long.parseLong(parts[0])));
            } catch (Throwable t) {
                // 时间坏了只影响这一行的可读性；记一行，别留空 catch。
                android.util.Log.w("DoubaoSlideDiag", "执行结果的时间戳解析失败：" + t);
            }
            // 用逗号而不是括号：这句话后面会被嵌进状态句的括号里，套两层括号不好读。
            s.fireText = action + " → " + s.fireResult + (when.isEmpty() ? "" : "，" + when);
        } catch (Throwable t) {
            s.fireText = "读取失败：" + t;
        }
    }

    /** 心跳行是「epochMillis 进程名 pid=…」。 */
    private static String ageTextOf(String line) {
        String[] parts = line.split(" ", 3);
        if (parts.length < 1) {
            return "未知时间";
        }
        try {
            long ageSec = (System.currentTimeMillis() - Long.parseLong(parts[0])) / 1000;
            if (ageSec < 60) {
                return "不到一分钟前";
            }
            if (ageSec < 3600) {
                return (ageSec / 60) + " 分钟前";
            }
            if (ageSec < 86400) {
                return (ageSec / 3600) + " 小时前";
            }
            return (ageSec / 86400) + " 天前";
        } catch (Throwable t) {
            android.util.Log.w("DoubaoSlideDiag", "心跳时间戳解析失败：" + t);
            return "未知时间";
        }
    }

    /** 详情行：pid 与启动时刻。这两个值要字符对齐，所以设置页用等宽字体显示。 */
    private static String detailOf(String line) {
        String[] parts = line.split(" ", 3);
        String pid = parts.length > 2 ? parts[2] : "";
        String time = "";
        try {
            long ms = Long.parseLong(parts[0]);
            time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date(ms));
        } catch (Throwable t) {
            // 时间解析不出来只影响这一行的可读性，pid 照常显示；记一行，别留空 catch。
            android.util.Log.w("DoubaoSlideDiag", "心跳启动时刻解析失败：" + t);
        }
        return (pid.isEmpty() ? "" : pid + "，") + (time.isEmpty() ? "" : time + " 启动");
    }
}
