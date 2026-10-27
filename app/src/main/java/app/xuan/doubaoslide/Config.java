package app.xuan.doubaoslide;

/**
 * 设置项的存储契约：**模块 App 写，注入进程读**。
 *
 * <p>两端必须用同一组名字；改名字等于让用户已有的设置失效。
 *
 * <p>跨进程怎么走通的（2026-10-27 spike 实测，本机 Android 16 + LSPosed 2.2.0）：
 * <ol>
 *   <li>清单里声明 {@code xposedsharedprefs}，LSPosed 会把本模块的
 *       {@code getSharedPreferences} 重定向到
 *       {@code /data/misc/apexdata/<id>/prefs/<pkg>/}（目录 0755，所有人可穿越）；</li>
 *   <li>注入进程用 {@link de.robv.android.xposed.XSharedPreferences} 读同一个路径；</li>
 *   <li><b>LSPosed 不改文件权限</b>（默认 0660，目标进程读不到），所以 App 每次写完
 *       必须自己 {@code setReadable(true, false)}。</li>
 * </ol>
 */
final class Config {

    /** 模块自己的 applicationId，也是共享 prefs 的目录名。 */
    static final String PKG = "app.xuan.doubaoslide";

    /** prefs 文件名，同时也是文件名（{@code shared_prefs/settings.xml} 的 settings）。 */
    static final String PREFS = "settings";

    static final String KEY_DEBUG = "debug";

    /** 注入进程往这个文件追加一行，设置页读它来显示「最近注入」。 */
    static final String HEARTBEAT = "heartbeat.log";

    /**
     * 每次快捷键执行的结果日志，设置页读它判断「模块还可用吗」。
     *
     * <p>为什么不比对豆包版本号：版本是个**代理信号**，豆包每发一个小版本就会误报，
     * 而手势未必真的坏了。真正要回答的是"模块此刻还能不能干活"，
     * 那只能由输入法进程里的运行时事实回答：钩子挂上没、动作通道接上没、
     * 上一次快捷键执行到了哪一步。
     */
    static final String FIRE = "fire.log";

    /**
     * 配置文件缺失或读不到时的默认值。
     *
     * <p><b>发版前改回 {@code false}</b>：交付包默认应该是安静的，用户要日志自己去设置页打开。
     * 现在保持 {@code true} 是因为排障期还没结束。
     */
    static final boolean DEFAULT_DEBUG = true;

    private Config() {
    }
}
