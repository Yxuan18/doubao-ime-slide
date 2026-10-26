package app.xuan.doubaoslide;

import android.os.Process;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 入口（{@code assets/xposed_init} 指向本类）。
 *
 * <p>去重：LSPosed 在同一个进程里可能用不同的 ClassLoader 加载模块，那样类里的静态量
 * 会各有一份、钩子会被装两遍。这里用 {@code System.setProperty} 做进程级标志
 * （System 由 boot classloader 加载，跨 ClassLoader 共享）。
 */
public final class XposedEntry implements IXposedHookLoadPackage {

    private static final String TARGET_PACKAGE = "com.bytedance.android.doubaoime";
    private static final String HOOK_FLAG = "doubao.slide.hooked";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        if (System.getProperty(HOOK_FLAG) != null) {
            Log.i("本进程已注入，跳过重复注入：" + lpparam.processName);
            return;
        }

        System.setProperty(HOOK_FLAG, "1");
        try {
            Log.always("注入 " + lpparam.processName + " pid=" + Process.myPid());
            BoardGuard.install(lpparam.classLoader);
            SlideHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            // 初始化失败就把标志撤掉，允许后续重试。
            System.clearProperty(HOOK_FLAG);
            Log.e("模块初始化失败", t);
        }
    }
}
