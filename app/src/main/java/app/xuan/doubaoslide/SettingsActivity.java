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
import java.lang.reflect.Field;

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
        // 两个诊断文件都得预建成 0666：共享目录是 0755，输入法进程（另一个 UID）
        // 建不了新文件，只能往已存在的文件里追加。
        ensureShared(f.getParentFile(), Config.HEARTBEAT, "心跳");
        ensureShared(f.getParentFile(), Config.FIRE, "执行结果");
    }

    private void ensureShared(File dir, String name, String what) {
        File file = new File(dir, name);
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (Throwable t) {
                // 建不出来时下面的 setX 也会失败，诊断区会把状态显示出来；这里留一行记录。
                android.util.Log.w(TAG, "创建" + what + "文件失败：" + t);
            }
        }
        file.setReadable(true, false);
        file.setWritable(true, false);                  // 0666：豆包进程要往里追加
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
        Diagnostics.Snapshot hb = Diagnostics.read(f);

        statusView.setText(statusSentence(hb));
        // 没注入时这句要弱下来，别让人以为出现了错误；注入成功才是正文色。
        statusView.setTextColor(hb.found ? colorPrimary : colorSecondary);

        String self = versionOf(getPackageName());
        addRow("模块", self == null ? "读取失败" : self, false);
        String ime = versionOf(Diagnostics.IME_PKG);
        addRow("豆包输入法", ime == null ? "未安装或读取失败" : ime, false);
        addRow("当前值", stored ? (value ? "开，已保存" : "关，已保存")
                : "还没写入过，用的是默认值", false);
        addRow("最近快捷键", hb.fireText == null ? "还没有执行过" : hb.fireText, true);
        addRow("配置文件", configState(f), true);
        addRow("键盘进程", hb.detail, true);
    }

    /**
     * 页面最上面那句话：模块此刻**能不能干活**。
     *
     * <p>不拿版本号当判据——版本是代理信号，豆包每发一个小版本就误报，
     * 而手势未必真的坏了。这里只用输入法进程报上来的运行时事实：
     * 钩子挂上没、动作通道接上没、上一次快捷键执行到了哪一步。
     *
     * <p>四种失败各说各的话，因为修法完全不同：
     * 没注入要去 LSPosed 里开；钩子没挂上说明豆包改了钩子目标；
     * 动作通道没接上说明 ImeService 变了；执行失败说明调用链断了。
     */
    private String statusSentence(Diagnostics.Snapshot hb) {
        if (!hb.found) {
            return "还没有记录到模块注入键盘进程。在 LSPosed 里启用本模块、"
                    + "作用域勾上「豆包输入法」，然后重启一次输入法。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("模块已注入键盘进程，最后一次是 ").append(hb.ageText).append("。");
        if (!hb.configReadable) {
            sb.append("\n但配置文件读不到——开关改了也传不过去。");
        }
        // 钩子没挂上：进程在、手势不在，而"注入成功"这句话会把人往错的方向引。
        if (hb.hookedKnown && !hb.hooked) {
            sb.append("\n但手势钩子没挂上——豆包输入法的触摸接口变了，"
                    + "模块抓不到按键，下滑不会触发。");
        } else if (hb.svcKnown && !hb.svc) {
            // 钩子挂上了但动作通道没接上：手势判得出来，快捷键执行不了。
            sb.append("\n但动作通道没接上——手势能判出来，快捷键不会执行。");
        } else if (isBrokenFire(hb.fireResult)) {
            // 运行时证据：上一次快捷键确实执行失败了，且失败在模块这一侧。
            sb.append("\n上一次快捷键没执行成功（").append(hb.fireText)
                    .append("）——动作通道可能已经不通了。");
        }
        return sb.toString();
    }

    /**
     * 执行结果里哪些属于"模块坏了"。
     *
     * <p>{@code ok} 正常；{@code editor} 是编辑器自己拒绝（没选区时复制、空撤销栈时撤销），
     * 日常就会发生，**不能报警**——把它当故障和拿版本号当判据是同一个错误。
     */
    private static boolean isBrokenFire(String result) {
        return "nochannel".equals(result) || "noresid".equals(result)
                || "exception".equals(result);
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

    /**
     * 读已安装包的版本号。
     *
     * @return 版本号；**没装**返回 {@code null}；读取出异常也返回 {@code null}，
     *         但会记一行日志——区分"没装"和"读不到"靠日志，不靠往返回值里塞哨兵字符串
     *         （哨兵和真版本号混在同一个通道里，调用方迟早会把"读取失败"当成版本号用）。
     */
    private String versionOf(String pkg) {
        try {
            return getPackageManager().getPackageInfo(pkg, 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            android.util.Log.i(TAG, pkg + " 未安装");
            return null;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "读取 " + pkg + " 版本号失败：" + t);
            return null;
        }
    }
}
