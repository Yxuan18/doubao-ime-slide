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
 * <p><b>界面原则</b>：整页只在回答两件事——「怎么设」和「现在成不成」。上半是开关，
 * 一条线之后是状态句与详情。状态句排在第一个：这一页真正的问题是"注入成功了吗"，
 * 不该埋在版本号和路径后面。
 *
 * <p>颜色跟系统主题走，不自己发明配色；全页只有一个彩色元素（开关轨道）。
 * 规则线只画在"换了一件事说"的地方，不逐行画。等宽字体只给需要字符对齐的数据
 * （路径、pid、时间），版本号和大白话用系统字族。
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
    private TextView statusView;
    private LinearLayout detailRows;

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
        root.setPadding(side, dp(28), side, dp(36));

        TextView title = text(getString(R.string.app_name), 24, colorPrimary);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        root.addView(title);

        TextView subtitle = text("按住字母下滑，执行复制、粘贴、剪切、全选、撤销。", 14, colorSecondary);
        subtitle.setLineSpacing(dp(2), 1f);
        root.addView(subtitle, paramsTop(dp(8)));

        root.addView(buildSwitchRow(), paramsTop(dp(22)));

        // 这条线分开两件事：上面是"怎么设"，下面是"现在怎么样"。
        root.addView(hairline(), hairlineParams(dp(18), 0));

        // 状态句。整页真正的问题是"注入成功了吗"，所以它排在第一个。
        statusView = text("", 15, colorPrimary);
        statusView.setLineSpacing(dp(5), 1f);
        root.addView(statusView, paramsTop(dp(18)));

        detailRows = new LinearLayout(this);
        detailRows.setOrientation(LinearLayout.VERTICAL);
        root.addView(detailRows, paramsTop(dp(10)));

        root.addView(hairline(), hairlineParams(dp(22), 0));
        TextView note = text(
                "这些值由输入法进程写进共享目录，仅供排障。"
                        + "「配置文件」显示“读不到”时，设置写进去了、键盘读不出来。",
                12, colorSecondary);
        note.setLineSpacing(dp(3), 1f);
        root.addView(note, paramsTop(dp(12)));

        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private View buildSwitchRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        row.setMinimumHeight(dp(52));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text("详细日志", 15, colorPrimary));

        TextView desc = text("每次按键都写入 LSPosed 日志。改完立刻生效，不用重启输入法。",
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
        if (detailRows == null) {
            return;
        }
        detailRows.removeAllViews();

        File f = prefsFile();
        boolean stored = prefs.contains(Config.KEY_DEBUG);
        boolean value = prefs.getBoolean(Config.KEY_DEBUG, Config.DEFAULT_DEBUG);
        Heartbeat hb = readHeartbeat(f);

        statusView.setText(statusSentence(hb, versionOf(IME_PKG)));
        // 没注入时这句要弱下来，别让人以为出现了错误；注入成功才是正文色。
        statusView.setTextColor(hb.found ? colorPrimary : colorSecondary);

        addRow("模块", versionOf(getPackageName()), false);
        addRow("豆包输入法", imeRow(), false);
        addRow("当前值", stored ? (value ? "开，已保存" : "关，已保存")
                : "还没写入过，用的是默认值", false);
        addRow("配置文件", configState(f), true);
        addRow("键盘进程", hb.detail, true);
    }

    /**
     * 豆包版本那一行：装没装、版本对不对，一行说完。
     *
     * <p>不写"已验收 1.5.3"是因为用户看不出"没装"和"装了但版本对不上"的区别——
     * 后者才是真正会让人白折腾的那种。写清楚比写"兼容"有用。
     */
    private String imeRow() {
        String v = versionOf(IME_PKG);
        if (v == null) {
            return "未安装";
        }
        if (Config.VERIFIED_IME_VERSION.equals(v)) {
            return v + "（已验收）";
        }
        return v + "（模块验收于 " + Config.VERIFIED_IME_VERSION + "，未验证）";
    }

    /**
     * 页面最上面那句话：模块现在到底在不在键盘里。
     *
     * <p>两种失败各说各的话：完全没注入，和注入了但配置文件读不到。后者更隐蔽——
     * 开关看起来拨了，键盘那边收不到。
     */
    private String statusSentence(Heartbeat hb, String imeVersion) {
        if (!hb.found) {
            return "还没有记录到模块注入键盘进程。在 LSPosed 里启用本模块、"
                    + "作用域勾上「豆包输入法」，然后重启一次输入法。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("模块已注入键盘进程，最后一次是 ").append(hb.ageText).append("。");
        if (!hb.configReadable) {
            sb.append("\n但配置文件读不到——开关改了也传不过去。");
        }
        // 钩子没挂上：这是最难查的一种坏法。进程在、手势不在，而"注入成功"这句话
        // 会把人往错的方向引。必须单独说清楚。
        if (hb.hookedKnown && !hb.hooked) {
            sb.append("\n但手势钩子没挂上——豆包输入法可能已升级，"
                    + "模块抓不到按键了。");
        }
        // 版本对不上要说在这句里，而不是只写在下面那行小字里：用户先看到的是这句。
        if (imeVersion != null && !Config.VERIFIED_IME_VERSION.equals(imeVersion)) {
            sb.append("\n豆包输入法是 ").append(imeVersion)
                    .append("，模块验收的是 ").append(Config.VERIFIED_IME_VERSION)
                    .append("——手势可能不工作。");
        }
        return sb.toString();
    }

    private String configState(File f) {
        if (f == null) {
            return "未知";
        }
        String state = !f.exists() ? "尚未写入，当前用默认值" : (f.canRead() ? "可读" : "读不到");
        return f.getAbsolutePath() + "\n" + state;
    }

    private void addRow(String label, String value, boolean mono) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(7), 0, dp(7));

        TextView l = text(label, 13, colorSecondary);
        l.setWidth(dp(88));
        l.setPadding(0, dp(1), dp(8), 0);
        row.addView(l);

        TextView v = text(value, mono ? 12 : 13, mono ? colorSecondary : colorPrimary);
        if (mono) {
            v.setTypeface(Typeface.MONOSPACE);
            v.setTextIsSelectable(true);
        }
        v.setLineSpacing(dp(3), 1f);
        row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        detailRows.addView(row);
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

    /** 心跳文件里读到的东西，全部翻译成页面能直接用的说法。 */
    private static final class Heartbeat {
        /** 找到键盘主进程那一行。心跳只在注入时写一次，所以"有这一行"≈"注入过"。 */
        boolean found;
        String ageText = "未知时间";
        String detail = "无记录（模块可能还没注入过）";
        /** 配置文件此刻读不读得到。两种失败要在状态句里说成不同的话。 */
        boolean configReadable;
        /** 钩子挂上了没有。false = 豆包可能已升级，钩子目标变了。 */
        boolean hooked = true;
        /** 旧版心跳没有 hooked= 字段，这时不能判"没挂上"，只能说不知道。 */
        boolean hookedKnown;
    }

    /**
     * 读心跳。
     *
     * <p>四个进程（主进程、{@code :upgrade}、{@code :push}、{@code :sandboxed_process0}）
     * 都会往同一个文件追加，所以"最后一行"经常是无关的子进程。这里只认**键盘所在的
     * 主进程**那一行，其它进程只报个数。
     */
    private Heartbeat readHeartbeat(File prefsFile) {
        Heartbeat hb = new Heartbeat();
        if (prefsFile == null) {
            hb.detail = "未知";
            return hb;
        }
        hb.configReadable = prefsFile.exists() && prefsFile.canRead();

        File file = new File(prefsFile.getParentFile(), Config.HEARTBEAT);
        if (!file.exists() || file.length() == 0) {
            return hb;
        }
        try {
            String content;
            RandomAccessFile raf = new RandomAccessFile(file, "r");
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
            java.util.Set<String> procs = new java.util.HashSet<String>();
            for (String line : content.split("\\n")) {
                String s = line.trim();
                if (s.isEmpty()) {
                    continue;
                }
                String[] parts = s.split(" ", 3);
                if (parts.length < 2) {
                    continue;
                }
                procs.add(parts[1]);
                if (IME_PKG.equals(parts[1])) {
                    main = s;               // 主进程：键盘在这里
                }
            }
            // 钩子状态从主进程那一行里取。第三段形如 "pid=123 hooked=true"。
            if (main != null) {
                hb.hookedKnown = true;
                hb.hooked = main.contains("hooked=true");
            }
            if (main == null) {
                hb.detail = "只记录到子进程，还没记录到键盘进程\n共 " + procs.size() + " 个进程写过";
                return hb;
            }
            hb.found = true;
            hb.ageText = ageTextOf(main);
            hb.detail = detailOf(main) + "\n共 " + procs.size() + " 个进程写过";
            return hb;
        } catch (Throwable t) {
            hb.detail = "读取失败：" + t;
            return hb;
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
            return "未知时间";
        }
    }

    /** 详情行：pid 与启动时刻。这两个值要字符对齐，所以走等宽。 */
    private static String detailOf(String line) {
        String[] parts = line.split(" ", 3);
        String pid = parts.length > 2 ? parts[2] : "";
        String time = "";
        try {
            long ms = Long.parseLong(parts[0]);
            time = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                    .format(new java.util.Date(ms));
        } catch (Throwable t) {
            // 时间解析不出来只影响这一行的可读性，pid 照常显示。
        }
        return (pid.isEmpty() ? "" : pid + "，") + (time.isEmpty() ? "" : time + " 启动");
    }
}
