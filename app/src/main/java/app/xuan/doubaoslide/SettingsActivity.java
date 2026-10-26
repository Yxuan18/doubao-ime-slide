package app.xuan.doubaoslide;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.util.Locale;

/**
 * 设置页：一个运行时开关 + 一屏诊断。
 *
 * <p><b>界面原则</b>：跟随系统主题取色（不自己发明配色），只用一个字号阶梯和一条
 * 发丝线做层次，不堆卡片、阴影、图标。开关用平台控件，字体用系统字体——这样在
 * MIUI 上看起来像系统自带设置，而不是一个外挂 App。
 *
 * <p>这个 Activity 跑在**模块自己的进程**里，不在豆包进程里。它写的是普通
 * {@code SharedPreferences}，由 LSPosed 重定向到与作用域共享的路径——见 {@link Config}。
 */
public class SettingsActivity extends Activity {

    private static final String IME_PKG = "com.bytedance.android.doubaoime";

    /** 本 Activity 跑在模块自己的进程里，没有 XposedBridge，所以用系统日志。 */
    private static final String TAG = "DoubaoSlideSettings";

    private SharedPreferences prefs;

    private int colorAccent;
    private int colorPrimary;
    private int colorSecondary;
    private int colorDivider;

    private Switch debugSwitch;
    private LinearLayout diagnosticRows;

    /** 程序化 setChecked 会触发监听器，用这个标记把「回填」和「用户操作」分开。 */
    private boolean binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        resolveThemeColors();
        prefs = getSharedPreferences(Config.PREFS, MODE_PRIVATE);
        setContentView(buildUi());
        ensureSharedFiles();
    }

    @Override
    protected void onResume() {
        super.onResume();
        binding = true;
        debugSwitch.setChecked(prefs.getBoolean(Config.KEY_DEBUG, Config.DEFAULT_DEBUG));
        binding = false;
        refreshDiagnostics();
    }

    // ------------------------------------------------------------------ 主题

    private void resolveThemeColors() {
        colorPrimary = themeColor(android.R.attr.textColorPrimary, 0xFF1A1A1A);
        // 不用 ?android:attr/colorAccent：本机 MIUI 把 DeviceDefault 的强调色改成了灰，
        // 开关"开"和"关"就只差拨杆位置。用自己定的赭石色。
        colorAccent = getResources().getColor(R.color.accent, getTheme());
        colorSecondary = themeColor(android.R.attr.textColorSecondary, 0xFF6B6B6B);
        // 分割线用正文色的 10% 透明度，深浅色下都自然
        colorDivider = (colorPrimary & 0x00FFFFFF) | 0x1A000000;
    }

    private int themeColor(int attr, int fallback) {
        TypedValue tv = new TypedValue();
        if (!getTheme().resolveAttribute(attr, tv, true)) {
            return fallback;
        }
        if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return tv.data;
        }
        if (tv.resourceId != 0) {
            return getResources().getColor(tv.resourceId, getTheme());
        }
        return fallback;
    }

    // ------------------------------------------------------------------ 布局

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int side = dp(24);
        root.setPadding(side, dp(28), side, dp(32));

        // 标题
        TextView title = text(getString(R.string.app_name), 26, colorPrimary);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        root.addView(title);

        TextView subtitle = text("按住字母下滑，执行复制 / 粘贴 / 剪切 / 全选 / 撤销", 13, colorSecondary);
        root.addView(subtitle, paramsTop(dp(6)));

        // 日志
        root.addView(sectionLabel("日志"), paramsTop(dp(36)));
        root.addView(buildSwitchRow(), paramsTop(dp(4)));
        root.addView(hairline(), hairlineParams(dp(16), 0));

        // 诊断
        root.addView(sectionLabel("诊断"), paramsTop(dp(28)));
        diagnosticRows = new LinearLayout(this);
        diagnosticRows.setOrientation(LinearLayout.VERTICAL);
        root.addView(diagnosticRows, paramsTop(dp(4)));

        // 脚注
        root.addView(hairline(), hairlineParams(dp(12), 0));
        TextView note = text(
                "诊断值由输入法进程写进共享目录，仅供排障参考，不具安全性。"
                        + "若「键盘进程」一直为空，说明模块没有注入到豆包——先去看 LSPosed 的日志。",
                12, colorSecondary);
        note.setLineSpacing(dp(3), 1f);
        root.addView(note, paramsTop(dp(14)));

        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private View buildSwitchRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        row.setMinimumHeight(dp(56));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text("详细日志", 16, colorPrimary));

        TextView desc = text("每次按键都写入 LSPosed 日志。改完立刻生效，不需要重启输入法。",
                13, colorSecondary);
        desc.setLineSpacing(dp(2), 1f);
        col.addView(desc, paramsTop(dp(3)));

        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        debugSwitch = new Switch(this);
        debugSwitch.setShowText(false);            // 文字放在左边，开关只要拨杆
        // 平台默认的轨道在深浅两套主题里都是灰的，"开"和"关"只靠拨杆位置区分，
        // 一瞥之下容易读错。接系统强调色，状态就一眼可辨。
        // 轨道与拨杆用同一套状态色：开=赭石底+白拨杆，关=浅灰底+灰拨杆。
        // 只上轨道的话，MIUI 默认的蓝色拨杆会跟赭石撞色。
        debugSwitch.setTrackTintList(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{colorAccent, (colorPrimary & 0x00FFFFFF) | 0x33000000}));
        // 拨杆始终是白的：状态只由轨道颜色和拨杆位置表达，少一个变量。
        // （不设的话 MIUI 会给一个系统蓝的拨杆，跟赭石撞色。）
        debugSwitch.setThumbTintList(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{0xFFFFFFFF, 0xFFFFFFFF}));
        debugSwitch.setChecked(prefs.getBoolean(Config.KEY_DEBUG, Config.DEFAULT_DEBUG));
        debugSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (binding) {
                    return;
                }
                apply(checked);
            }
        });
        LinearLayout.LayoutParams sw = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sw.leftMargin = dp(16);
        row.addView(debugSwitch, sw);

        // 整行可点，不只靠那个小拨杆
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                debugSwitch.toggle();
            }
        });
        return row;
    }

    private TextView sectionLabel(String s) {
        TextView tv = text(s, 12, colorSecondary);
        tv.setLetterSpacing(0.08f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return tv;
    }

    /** 发丝线。高度必须是固定值——用 WRAP_CONTENT 会被撑满整屏。 */
    private View hairline() {
        View v = new View(this);
        v.setBackgroundColor(colorDivider);
        return v;
    }

    private LinearLayout.LayoutParams hairlineParams(int topMarginDp, int insetLeftDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
        lp.topMargin = topMarginDp;
        lp.leftMargin = insetLeftDp;
        return lp;
    }

    private TextView text(String s, int sp, int color) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        tv.setGravity(Gravity.START);
        return tv;
    }

    private LinearLayout.LayoutParams paramsTop(int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMarginDp;
        return lp;
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
        if (prefsFile() != null) {
            makeSharedFilesReadable();
        }
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
        if (diagnosticRows == null) {
            return;
        }
        diagnosticRows.removeAllViews();

        boolean stored = prefs.contains(Config.KEY_DEBUG);
        boolean value = prefs.getBoolean(Config.KEY_DEBUG, Config.DEFAULT_DEBUG);

        addRow("模块版本", versionOf(getPackageName()));
        addRow("豆包输入法", versionOf(IME_PKG) == null ? "未安装" : versionOf(IME_PKG));
        addRow("当前值", (value ? "开" : "关")
                + (stored ? " · 已保存" : " · 默认（尚未设置）"));

        File f = prefsFile();
        String state;
        if (f == null) {
            state = "未知";
        } else if (!f.exists()) {
            state = "尚未写入，当前用默认值";
        } else {
            state = f.canRead() ? "可读" : "读不到";
        }
        addRow("配置文件", f == null ? state : f.getAbsolutePath() + "\n" + state);

        addRow("键盘进程", heartbeatSummary(f));
    }

    private void addRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, dp(10));

        // 只在行与行之间画线，最后一行下面不留悬空的线
        if (diagnosticRows.getChildCount() > 0) {
            diagnosticRows.addView(hairline(), hairlineParams(0, dp(88)));
        }

        TextView l = text(label, 13, colorSecondary);
        l.setWidth(dp(88));
        l.setPadding(0, dp(1), dp(8), 0);
        row.addView(l);

        TextView v = text(value, 13, colorPrimary);
        v.setTypeface(Typeface.MONOSPACE);
        v.setTextIsSelectable(true);
        v.setLineSpacing(dp(3), 1f);
        row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        diagnosticRows.addView(row);
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
            String content;
            try {
                long len = raf.length();
                long start = Math.max(0, len - 4096);
                raf.seek(start);
                byte[] buf = new byte[(int) (len - start)];
                raf.readFully(buf);
                content = new String(buf, "UTF-8");
            } finally {
                raf.close();
            }

            String main = null;
            String last = null;
            java.util.Set<String> procs = new java.util.HashSet<String>();
            for (String line : content.split("\\n")) {
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
            return shown + "\n共 " + procs.size() + " 个进程写过";
        } catch (Throwable t) {
            return "读取失败：" + t;
        }
    }

    /** 心跳行是「epochMillis 进程名 pid=…」，把时间戳换成可读时间。 */
    private String prettyHeartbeat(String line) {
        String[] parts = line.split(" ", 3);
        if (parts.length < 2) {
            return line;
        }
        try {
            long ms = Long.parseLong(parts[0]);
            String time = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                    .format(new java.util.Date(ms));
            long ageSec = (System.currentTimeMillis() - ms) / 1000;
            return ageSec + " 秒前 · " + parts[1] + (parts.length > 2 ? " " + parts[2] : "")
                    + "\n" + time;
        } catch (Throwable t) {
            return line;
        }
    }
}
