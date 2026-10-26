package app.xuan.doubaoslide;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Resources;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.inputmethod.InputConnection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XposedHelpers;

/**
 * 快捷键动作层。
 *
 * <p>全部走 Android 标准 {@link InputConnection} 通道（{@code performContextMenuAction}），
 * 不碰豆包自己的编辑逻辑：输入法升级后这条通道一般不变。
 *
 * <p>资源 id 运行时解析：{@code android.R.id} 的字段是 static final int，编译期被内联，
 * 运行时类表里没有这些字段（frida 侧实测 {@code Java.use('android.R.id')} 直接抛），
 * 所以以系统资源解析为准，解析不到再退回编译期常量。
 */
final class Actions {

    /** 豆包会同时留不止一个 ImeService，只有被绑定的那个有输入连接（实测），所以都留着挨个问。 */
    private static final List<Object> SERVICES =
            Collections.synchronizedList(new ArrayList<Object>(4));

    private static final int MAX_SERVICES = 4;

    private static final Map<String, Integer> RES_IDS = new HashMap<String, Integer>();

    private Actions() {
    }

    // ---------------------------------------------------------------- 服务实例

    static void captureService(Object service) {
        if (service == null) {
            return;
        }
        synchronized (SERVICES) {
            for (Object existing : SERVICES) {
                if (existing == service) {
                    return;
                }
            }
            SERVICES.add(service);
            while (SERVICES.size() > MAX_SERVICES) {
                SERVICES.remove(0);
            }
        }
    }

    private static InputConnection connection() {
        List<Object> snapshot;
        synchronized (SERVICES) {
            snapshot = new ArrayList<Object>(SERVICES);
        }
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            Object service = snapshot.get(i);
            try {
                Object candidate = XposedHelpers.callMethod(service, "getCurrentInputConnection");
                if (candidate instanceof InputConnection) {
                    return (InputConnection) candidate;
                }
            } catch (Throwable t) {
                // 某个实例拿不到连接是常态（未被绑定），继续问下一个。
                if (Log.DEBUG) Log.i("getCurrentInputConnection 失败：" + t);
            }
        }
        return null;
    }

    private static Context contextOf() {
        List<Object> snapshot;
        synchronized (SERVICES) {
            snapshot = new ArrayList<Object>(SERVICES);
        }
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            if (snapshot.get(i) instanceof Context) {
                return (Context) snapshot.get(i);
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 动作

    /** @return 动作是否执行成功；失败时已经尝试过兜底通道。 */
    static boolean perform(String action) {
        InputConnection ic = connection();
        if (ic == null) {
            Log.w("没有输入连接，" + action + " 未执行");
            return false;
        }
        if ("copy".equals(action)) {
            return copy(ic);
        }
        if ("paste".equals(action)) {
            return paste(ic);
        }
        return contextMenuAction(ic, action);
    }

    private static boolean contextMenuAction(InputConnection ic, String name) {
        int id = resId(name);
        if (id <= 0) {
            Log.w("找不到资源 id：" + name);
            return false;
        }
        try {
            boolean ok = ic.performContextMenuAction(id);
            if (Log.DEBUG) Log.i("performContextMenuAction(" + name + ", " + id + ") = " + ok);
            return ok;
        } catch (Throwable t) {
            Log.e("performContextMenuAction(" + name + ") 失败", t);
            return false;
        }
    }

    private static boolean copy(InputConnection ic) {
        if (contextMenuAction(ic, "copy")) {
            return true;
        }
        // 兜底：拿选中文本自己写系统剪贴板。
        try {
            CharSequence selected = ic.getSelectedText(0);
            if (selected == null || selected.length() == 0) {
                if (Log.DEBUG) Log.i("没有选中内容，复制未执行");
                return false;
            }
            Context ctx = contextOf();
            ClipboardManager cm = ctx == null
                    ? null
                    : (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                return false;
            }
            cm.setPrimaryClip(ClipData.newPlainText("doubao-slide", selected));
            if (Log.DEBUG) Log.i("复制走兜底通道（" + selected.length() + " 字符）");
            return true;
        } catch (Throwable t) {
            Log.e("复制兜底失败", t);
            return false;
        }
    }

    private static boolean paste(InputConnection ic) {
        if (contextMenuAction(ic, "paste")) {
            return true;
        }
        try {
            Context ctx = contextOf();
            ClipboardManager cm = ctx == null
                    ? null
                    : (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || cm.getPrimaryClip() == null || cm.getPrimaryClip().getItemCount() == 0) {
                if (Log.DEBUG) Log.i("剪贴板为空，粘贴未执行");
                return false;
            }
            CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(ctx);
            if (text == null || text.length() == 0) {
                return false;
            }
            ic.commitText(text, 1);
            if (Log.DEBUG) Log.i("粘贴走兜底通道（" + text.length() + " 字符）");
            return true;
        } catch (Throwable t) {
            Log.e("粘贴兜底失败", t);
            return false;
        }
    }

    /**
     * 最后一道保险：补发轻点时引擎调用失败，直接把字母提交进输入框。
     *
     * <p>只在补发 DOWN 就没送出去时调用——那时引擎什么都没收到，提交一个字母不会重复。
     */
    static void commitLetter(char letter) {
        InputConnection ic = connection();
        if (ic == null) {
            Log.w("补发失败且没有输入连接，字母 '" + letter + "' 丢失");
            return;
        }
        try {
            ic.commitText(String.valueOf(letter), 1);
            Log.w("补发失败，直接提交字母 '" + letter + "'");
        } catch (Throwable t) {
            Log.e("提交字母 '" + letter + "' 失败", t);
        }
    }

    static void haptic(View view) {
        if (view == null) {
            return;
        }
        try {
            view.performHapticFeedback(
                    HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        } catch (Throwable t) {
            // 部分机型不支持触感反馈，忽略。
            if (Log.DEBUG) Log.i("触感反馈不可用：" + t);
        }
    }

    // ---------------------------------------------------------------- 资源 id

    private static int resId(String name) {
        Integer cached;
        synchronized (RES_IDS) {
            cached = RES_IDS.get(name);
        }
        if (cached != null) {
            return cached;
        }
        int resolved = 0;
        try {
            resolved = Resources.getSystem().getIdentifier(name, "id", "android");
        } catch (Throwable t) {
            Log.e("解析资源 id 失败：" + name, t);
        }
        if (resolved <= 0) {
            resolved = fallbackId(name);
            Log.w("运行时解析不到 " + name + "，使用编译期常量 " + resolved);
        }
        synchronized (RES_IDS) {
            RES_IDS.put(name, resolved);
        }
        return resolved;
    }

    private static int fallbackId(String name) {
        if ("selectAll".equals(name)) {
            return android.R.id.selectAll;
        }
        if ("cut".equals(name)) {
            return android.R.id.cut;
        }
        if ("copy".equals(name)) {
            return android.R.id.copy;
        }
        if ("paste".equals(name)) {
            return android.R.id.paste;
        }
        if ("undo".equals(name)) {
            return android.R.id.undo;
        }
        if ("redo".equals(name)) {
            return android.R.id.redo;
        }
        return -1;
    }
}
