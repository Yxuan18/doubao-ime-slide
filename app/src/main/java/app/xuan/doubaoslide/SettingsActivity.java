package app.xuan.doubaoslide;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;

/**
 * 设置页：一个运行时开关 + 一屏诊断信息。
 *
 * <p>为什么不用 layout XML / androidx：只有两个控件。纯代码布局让 APK 不变大，也保持
 * 「离线可构建、不引 Kotlin」这条既有约束。
 *
 * <p>这个 Activity 跑在**模块自己的进程**里，不在豆包进程里。它写的是普通
 * {@code SharedPreferences}，由 LSPosed 重定向到与作用域共享的路径——见 {@link Config}。
 */
public class SettingsActivity extends Activity {

    private static final String IME_PKG = "com.bytedance.android.doubaoime";

    /** 本 Activity 跑在模块自己的进程里，没有 XposedBridge，所以用系统日志。 */
    private static final String TAG = "DoubaoSlideSettings";

    private SharedPreferences prefs;
    private Switch debugSwitch;
    private TextView diagnostics;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(Config.PREFS, MODE_PRIVATE);
        setContentView(buildUi());
        ensureSharedFiles();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 可能被别的地方改过，回来时以文件为准
        if (debugSwitch != null) {
            debugSwitch.setChecked(prefs.getBoolean(Config.KEY_DEBUG, Config.DEFAULT_DEBUG));
        }
        refreshDiagnostics();
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        int pad = dp(20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = text(getString(R.string.app_name), 22, true);
        root.addView(title);
        root.addView(text("模块设置", 13, false));

        debugSwitch = new Switch(this);
        debugSwitch.setText("详细日志（DEBUG）");
        debugSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        debugSwitch.setPadding(0, dp(24), 0, 0);
        debugSwitch.setChecked(prefs.getBoolean(Config.KEY_DEBUG, Config.DEFAULT_DEBUG));
        debugSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton button, boolean checked) {
                apply(checked);
            }
        });
        root.addView(debugSwitch);

        root.addView(text("打开后，每次按键都会往 LSPosed 日志里写几行。"
                + "改完不需要重启输入法：下一次按键就会用新值。", 13, false));

        TextView divider = new TextView(this);
        divider.setHeight(dp(1));
        divider.setBackgroundColor(0x22888888);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(24);
        lp.bottomMargin = dp(16);
        root.addView(divider, lp);

        root.addView(text("诊断信息", 15, true));

        diagnostics = text("", 13, false);
        diagnostics.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(8);
        root.addView(diagnostics, dlp);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) {
            tv.setTypeface(null, android.graphics.Typeface.BOLD);
        }
        tv.setGravity(Gravity.START);
        return tv;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    // ------------------------------------------------------------------ 写入

    private void apply(boolean value) {
        prefs.edit().putBoolean(Config.KEY_DEBUG, value).commit();   // 同步落盘，紧接着要改权限
        makeSharedFilesReadable();
        refreshDiagnostics();
    }

    /**
     * 把配置文件与心跳文件置成目标进程能访问的权限。
     *
     * <p>这一步不能省：LSPosed 只负责把路径共享出来，文件本身仍是 0660（模块自己的 UID），
     * 豆包进程读不到（spike 实测：可读=false）。每次 {@code commit()} 会重写文件、
     * 权限回到 0660，所以要在**每次写入之后**都调一次。
     */
    private void makeSharedFilesReadable() {
        File f = prefsFile();
        if (f == null) {
            return;
        }
        f.setReadable(true, false);                     // 0644：让豆包进程能读
        File hb = new File(f.getParentFile(), Config.HEARTBEAT);
        if (!hb.exists()) {
            try {
                hb.createNewFile();
            } catch (Throwable t) {
                // 建不出来时下面的 setX 也会失败，诊断区会把状态显示出来；这里留一行记录。
                android.util.Log.w(TAG, "创建心跳文件失败：" + t);
            }
        }
        hb.setReadable(true, false);
        hb.setWritable(true, false);                    // 0666：豆包进程要往里追加心跳
    }

    /** 首屏就把心跳文件准备好，否则注入进程没有可写的目标。 */
    private void ensureSharedFiles() {
        File f = prefsFile();
        if (f == null) {
            return;
        }
        makeSharedFilesReadable();
    }

    /**
     * 取 prefs 对应的文件。
     *
     * <p>不能自己拼路径：LSPosed 会把 prefs 重定向到
     * {@code /data/misc/apexdata/…/prefs/<pkg>/}，那个 id 只有框架知道。
     * {@code SharedPreferencesImpl.mFile} 是唯一拿到真实路径的办法。
     */
    private File prefsFile() {
        try {
            // 必须是实例的类（SharedPreferencesImpl），接口上没有 mFile。
            Field f = prefs.getClass().getDeclaredField("mFile");
            f.setAccessible(true);
            Object v = f.get(prefs);
            if (v instanceof File) {
                return (File) v;
            }
        } catch (Throwable t) {
            // 反射被限制时退到常规路径。走这条说明 LSPosed 大概率没重定向，
            // 写进去的设置输入法读不到——诊断区会显示成"读不到"。
            android.util.Log.w(TAG, "取 prefs 真实路径失败，退回常规路径：" + t);
        }
        return new File(getDataDir(), "shared_prefs/" + Config.PREFS + ".xml");
    }

    // ------------------------------------------------------------------ 诊断

    private void refreshDiagnostics() {
        if (diagnostics == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();

        sb.append("模块版本   ").append(versionOf(getPackageName())).append('\n');

        String imeVersion = versionOf(IME_PKG);
        sb.append("豆包输入法 ").append(imeVersion == null ? "未安装" : imeVersion).append('\n');

        boolean stored = prefs.contains(Config.KEY_DEBUG);
        sb.append("文件里的值 ")
                .append(stored ? String.valueOf(prefs.getBoolean(Config.KEY_DEBUG, false))
                        : "未设置（用默认 " + Config.DEFAULT_DEBUG + "）").append('\n');

        File f = prefsFile();
        String state;
        if (f == null) {
            state = "未知";
        } else if (!f.exists()) {
            state = "（尚未写入，当前用默认值）";
        } else {
            state = f.canRead() ? "（可读）" : "（读不到）";
        }
        sb.append("配置文件   ").append(f == null ? "未知" : f.getAbsolutePath() + state).append('\n');

        sb.append("键盘进程   ").append(heartbeatSummary(f)).append('\n');

        sb.append('\n').append("诊断值由输入法进程写进共享目录，仅供排障参考，不具安全性。"
                + "若「最近注入」一直为空，说明模块没有注入到豆包——去看 LSPosed 的日志，"
                + "而不是怀疑这个开关。");

        diagnostics.setText(sb.toString());
    }

    private String versionOf(String pkg) {
        try {
            return getPackageManager().getPackageInfo(pkg, 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        } catch (Throwable t) {
            return "读取失败";
        }
    }

    /**
     * 心跳摘要。
     *
     * <p>四个进程（主进程、{@code :upgrade}、{@code :push}、{@code :sandboxed_process0}）
     * 都会往同一个文件追加，所以"最后一行"经常是无关的子进程。这里优先找**键盘所在的
     * 主进程**，再把其它进程的数量报出来。
     */
    private String heartbeatSummary(File prefsFile) {
        if (prefsFile == null) {
            return "未知";
        }
        File hb = new File(prefsFile.getParentFile(), Config.HEARTBEAT);
        if (!hb.exists() || hb.length() == 0) {
            return "无记录（模块可能还没注入过）";
        }
        try {
            RandomAccessFile raf = new RandomAccessFile(hb, "r");
            String text;
            try {
                long len = raf.length();
                long start = Math.max(0, len - 4096);
                raf.seek(start);
                byte[] buf = new byte[(int) (len - start)];
                raf.readFully(buf);
                text = new String(buf, "UTF-8");
            } finally {
                raf.close();
            }

            String main = null;
            String last = null;
            java.util.Set<String> procs = new java.util.HashSet<String>();
            for (String line : text.split("\n")) {
                String s = line.trim();
                if (s.isEmpty()) {
                    continue;
                }
                last = s;
                String[] parts = s.split(" ", 3);
                if (parts.length >= 2) {
                    procs.add(parts[1]);
                    if (IME_PKG.equals(parts[1])) {
                        main = s;               // 主进程：键盘在这里
                    }
                }
            }
            if (last == null) {
                return "无记录";
            }
            String shown = main != null ? prettyHeartbeat(main) : "尚未记录到（只有子进程写过）";
            return shown + "  ·  共 " + procs.size() + " 个进程写过";
        } catch (Throwable t) {
            return "读取失败：" + t;
        }
    }

    /** 心跳行是「epochMillis 进程名 pid=…」，把时间戳换成可读时间。 */

    private String prettyHeartbeat(String line) {
        String[] parts = line.split(" ", 2);
        if (parts.length < 2) {
            return line;
        }
        try {
            long ms = Long.parseLong(parts[0]);
            String time = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date(ms));
            long ageSec = (System.currentTimeMillis() - ms) / 1000;
            return time + "（" + ageSec + " 秒前） " + parts[1];
        } catch (Throwable t) {
            return line;
        }
    }
}
