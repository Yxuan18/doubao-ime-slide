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
     * 模块验收过的豆包输入法版本。
     *
     * <p>钩的是 {@code KeyboardView.nativeTouch(long,int,int,int,long)} 这个**具体签名**，
     * 豆包一改它，模块就挂不上——而且是**静默**的：进程照常注入，键盘照常能用，
     * 只是手势没了。用户看到的是"功能坏了"，不是"版本不兼容"。
     *
     * <p>所以设置页要比对版本，并对不上时明说。这不是要锁死版本，是要让不兼容可见。
     */
    static final String VERIFIED_IME_VERSION = "1.5.3";

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
