package com.tempmail.data.services;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Controls attached to Temp Mail's existing accessibility autofill overlay. */
public final class FloatingOtpAssistant {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WindowManager activeWindowManager;
    private static View activeFloatingView;
    private static View activeFloatingPanel;
    private static AccessibilityService accessibilityService;
    private static TextView currentAddressView;
    private static final Pattern OTP = Pattern.compile(
            "(?i)(?:otp|one[- ]time(?: password)?|verification|security|access|login)\\s*(?:code|pin)?\\D{0,48}([0-9]{4,8})(?![0-9])");

    private FloatingOtpAssistant() { }

    /** Installs an always-visible in-app toggle on Temp Mail's main screen. */
    public static void installInApp(Context context) {
        if (context instanceof AccessibilityService) {
            accessibilityService = (AccessibilityService) context;
            return;
        }
        if (!(context instanceof Activity)) return;
        Activity activity = (Activity) context;
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup) || content.findViewWithTag("tempmail-floating-toggle") != null) return;
        int addressId = resourceId("id", "text");
        currentAddressView = addressId == 0 ? null : activity.findViewById(addressId);
        Button toggle = button(activity, "Floating OTP");
        toggle.setTag("tempmail-floating-toggle");
        toggle.setContentDescription("Floating OTP assistant toggle");
        FrameLayout.LayoutParams toggleParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        toggleParams.setMargins(dp(activity, 12), dp(activity, 12), dp(activity, 16), dp(activity, 16));
        ((ViewGroup) content).addView(toggle, toggleParams);
        toggle.setOnClickListener(v -> {
            if (accessibilityService == null) {
                toggle.setText("Enable accessibility");
                activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return;
            }
            if (activeFloatingView != null) {
                removeFloating();
                toggle.setText("Floating OTP");
            } else {
                showFloating(accessibilityService);
                toggle.setText("Turn floating off");
            }
        });
    }

    private static void showFloating(AccessibilityService service) {
        RelativeLayout root = new RelativeLayout(service);
        root.setBackgroundColor(Color.TRANSPARENT);
        activeFloatingView = root;
        Button launcher = button(service, "OTP");
        launcher.setId(View.generateViewId());
        launcher.setTag("tempmail-floating-launcher");
        launcher.setContentDescription("Show or hide Temp Mail assistant");
        launcher.setMinWidth(dp(service, 52));
        launcher.setMinHeight(dp(service, 46));
        RelativeLayout.LayoutParams launcherParams = new RelativeLayout.LayoutParams(dp(service, 56), dp(service, 46));
        launcherParams.addRule(RelativeLayout.ALIGN_PARENT_END);
        root.addView(launcher, launcherParams);
        launcher.setOnClickListener(v -> {
            View panel = activeFloatingPanel;
            if (panel != null) panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        });
        attach(service, root);
        WindowManager manager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        int type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(dp(service, 184), -2, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        params.x = dp(service, 8);
        manager.addView(root, params);
        activeWindowManager = manager;
    }

    private static void removeFloating() {
        try { if (activeWindowManager != null && activeFloatingView != null) activeWindowManager.removeView(activeFloatingView); }
        catch (Throwable ignored) { }
        activeFloatingView = null;
        activeFloatingPanel = null;
        activeWindowManager = null;
    }

    public static void attach(Context context, Object overlay) {
        if (context instanceof AccessibilityService) {
            accessibilityService = (AccessibilityService) context;
            if (!(overlay instanceof View)) return;
        } else if (context instanceof Activity) {
            installInApp(context);
            return;
        }
        if (!(overlay instanceof RelativeLayout)) return;
        RelativeLayout root = (RelativeLayout) overlay;
        int textId = resourceId("id", "text");
        TextView address = textId == 0 ? null : root.findViewById(textId);
        if (address == null && context instanceof Activity && textId != 0) address = ((Activity) context).findViewById(textId);
        if (address == null) address = currentAddressView;
        final TextView mailboxAddress = address;

        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(context, 8), dp(context, 5), dp(context, 8), dp(context, 8));
        panel.setBackgroundColor(Color.WHITE);
        RelativeLayout.LayoutParams panelParams = new RelativeLayout.LayoutParams(-1, -2);
        if (overlay == activeFloatingView) {
            View launcher = root.findViewWithTag("tempmail-floating-launcher");
            if (launcher != null) {
                panelParams.addRule(RelativeLayout.BELOW, launcher.getId());
                panelParams.topMargin = dp(context, 4);
            }
            panel.setVisibility(View.GONE);
            activeFloatingPanel = panel;
        } else if (textId != 0) {
            panelParams.addRule(RelativeLayout.BELOW, textId);
        }
        root.addView(panel, panelParams);

        TextView status = new TextView(context);
        status.setText("Inbox assistant ready");
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setTextColor(Color.DKGRAY);
        panel.addView(status, new LinearLayout.LayoutParams(-1, -2));
        if (overlay == activeFloatingView) {
            Button close = button(context, "Hide options");
            panel.addView(close, new LinearLayout.LayoutParams(-1, -2));
            close.setOnClickListener(v -> panel.setVisibility(View.GONE));
        }

        Button change = button(context, "Change email");
        Button pasteEmail = button(context, "Paste email");
        Button refresh = button(context, "Refresh inbox");
        Button pasteOtp = button(context, "Paste OTP");
        pasteOtp.setEnabled(false);
        if (overlay == activeFloatingView) {
            panel.addView(change, verticalButtonParams(context));
            panel.addView(pasteEmail, verticalButtonParams(context));
            panel.addView(refresh, verticalButtonParams(context));
            panel.addView(pasteOtp, verticalButtonParams(context));
        } else {
            LinearLayout first = row(context);
            panel.addView(first);
            first.addView(change, weightParams());
            first.addView(pasteEmail, weightParams());
            LinearLayout second = row(context);
            panel.addView(second);
            second.addView(refresh, weightParams());
            second.addView(pasteOtp, weightParams());
        }

        pasteEmail.setOnClickListener(v -> {
            status.setText("Loading the current Temp Mail address…");
            IO.execute(() -> {
                String email = getCurrentEmail(context);
                MAIN.post(() -> {
                    if (email != null && email.startsWith("ERROR:")) status.setText("Mailbox lookup failed: " + email.substring(6));
                    else if (email == null || email.isEmpty()) status.setText("Current mailbox address is unavailable.");
                    else paste(context, email, status);
                });
            });
        });
        pasteOtp.setOnClickListener(v -> {
            Object code = pasteOtp.getTag();
            if (code instanceof String) paste(context, (String) code, status);
        });
        change.setOnClickListener(v -> {
            change.setEnabled(false);
            status.setText("Creating a new mailbox…");
            try {
                Object repository = construct("com.tempmail.data.repository.MailboxRepository", context);
                suspendCall(repository, "m48413u", new Object[0], result -> {
                    change.setEnabled(true);
                    Object mailbox = payload(result);
                    String next = callString(mailbox, "getFullEmailAddress");
                    if (next == null || next.isEmpty()) {
                        status.setText("Mailbox change failed. Try again.");
                        return;
                    }
                    if (mailboxAddress != null) mailboxAddress.setText(next);
                    pasteOtp.setTag(null);
                    pasteOtp.setEnabled(false);
                    status.setText("Mailbox changed. Refresh to check messages.");
                }, error -> {
                    change.setEnabled(true);
                    status.setText("Mailbox change failed: " + safe(error));
                });
            } catch (Throwable error) {
                change.setEnabled(true);
                status.setText("Mailbox change failed: " + safe(error));
            }
        });
        refresh.setOnClickListener(v -> {
            refresh.setEnabled(false);
            pasteOtp.setTag(null);
            pasteOtp.setEnabled(false);
            status.setText("Refreshing the Temp Mail inbox…");
            try {
                Object repository = construct("com.tempmail.data.repository.InboxRepository", context);
                suspendCall(repository, "m48347N", new Object[0], result -> {
                    refresh.setEnabled(true);
                    if (!isSuccess(result)) {
                        status.setText("Inbox refresh failed. Try again.");
                        return;
                    }
                    loadLatest(context, status, pasteOtp);
                }, error -> {
                    refresh.setEnabled(true);
                    status.setText("Inbox refresh failed: " + safe(error));
                });
            } catch (Throwable error) {
                refresh.setEnabled(true);
                status.setText("Inbox refresh failed: " + safe(error));
            }
        });
    }

    private static void loadLatest(Context context, TextView status, Button pasteOtp) {
        try {
            Object inbox = construct("com.tempmail.data.repository.InboxRepository", context);
            suspendCall(inbox, "m48342I", new Object[0],
                    value -> processLatest(context, status, pasteOtp, value),
                    error -> status.setText("Inbox list failed: " + safe(error)));
        } catch (Throwable error) {
            status.setText("Inbox list failed: " + safe(error));
        }
    }

    private static void processLatest(Context context, TextView status, Button pasteOtp, Object value) {
        try {
            if (!(value instanceof List)) {
                status.setText("Inbox list is unavailable.");
                return;
            }
            Object latest = null;
            long newest = Long.MIN_VALUE;
            for (Object email : new ArrayList<>((List<?>) value)) {
                Object timestamp = call(email, "getTimestampMs");
                if (timestamp instanceof Number && ((Number) timestamp).longValue() > newest) {
                    newest = ((Number) timestamp).longValue();
                    latest = email;
                }
            }
            if (latest == null) {
                status.setText("No messages in this mailbox yet.");
                return;
            }
            Object id = call(latest, "getEid");
            Object mailRepository = construct("com.tempmail.data.repository.MailRepository", context);
            suspendCall(mailRepository, "m48374C", new Object[]{String.valueOf(id)}, result -> {
                Object mail = payload(result);
                if (mail == null) {
                    status.setText("Inbox refreshed; latest message details are unavailable.");
                    return;
                }
                String content = plain(callString(mail, "getMailSubject")) + "\n"
                        + plain(callString(mail, "getPreview")) + "\n"
                        + plain(callString(mail, "getMailHtml"));
                Matcher matcher = OTP.matcher(content);
                if (matcher.find()) {
                    String code = matcher.group(1);
                    pasteOtp.setTag(code);
                    pasteOtp.setEnabled(true);
                    status.setText("OTP found in: " + safe(callString(mail, "getMailSubject")));
                } else {
                    status.setText("Inbox refreshed. No verification code found in the latest message.");
                }
            }, error -> status.setText("Message read failed: " + safe(error)));
        } catch (Throwable error) {
            status.setText("Inbox list failed: " + safe(error));
        }
    }

    private static void suspendCall(Object owner, String method, Object[] args,
                                    Consumer<Object> success, Consumer<Throwable> failure) {
        IO.execute(() -> {
            try {
                Object result = invokeWithContinuation(owner, method, args, value -> MAIN.post(() -> success.accept(value)),
                        error -> MAIN.post(() -> failure.accept(error)));
                if (!isSuspended(result)) MAIN.post(() -> success.accept(result));
            } catch (Throwable error) {
                MAIN.post(() -> failure.accept(error));
            }
        });
    }

    private static Object invokeWithContinuation(Object owner, String name, Object[] args) throws Throwable {
        return invokeWithContinuation(owner, name, args, ignored -> { }, ignored -> { });
    }

    private static Object invokeWithContinuation(Object owner, String name, Object[] args,
                                                 Consumer<Object> resumed, Consumer<Throwable> failed) throws Throwable {
        Method call = findSuspendMethod(owner.getClass(), name, args);
        if (call == null) throw new NoSuchMethodException(owner.getClass().getName() + "." + name);
        Class<?> continuationType = call.getParameterTypes()[args.length];
        if (!continuationType.isInterface()) throw new NoSuchMethodException(name + " has no Continuation parameter");
        InvocationHandler handler = (proxy, method, values) -> {
            if (method.getName().equals("getContext")) {
                Class<?> context = Class.forName("kotlin.coroutines.EmptyCoroutineContext", true, continuationType.getClassLoader());
                Field field = context.getField("f64617B");
                return field.get(null);
            }
            if (method.getName().equals("resumeWith")) {
                try {
                    Object result = values == null ? null : values[0];
                    Class<?> resultKt = Class.forName("kotlin.ResultKt", true, continuationType.getClassLoader());
                    Method check = resultKt.getMethod("m53922b", Object.class);
                    check.invoke(null, result);
                    resumed.accept(result);
                } catch (Throwable error) {
                    failed.accept(unwrap(error));
                }
                return null;
            }
            if (method.getName().equals("toString")) return "FloatingOtpAssistantContinuation";
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == (values == null ? null : values[0]);
            return null;
        };
        Object continuation = Proxy.newProxyInstance(continuationType.getClassLoader(),
                new Class<?>[]{continuationType}, handler);
        Object[] values = new Object[args.length + 1];
        System.arraycopy(args, 0, values, 0, args.length);
        values[args.length] = continuation;
        try {
            call.setAccessible(true);
            return call.invoke(owner, values);
        } catch (Throwable error) {
            throw unwrap(error);
        }
    }

    private static Method findSuspendMethod(Class<?> type, String name, Object[] args) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            Method[] methods;
            try { methods = current.getDeclaredMethods(); }
            catch (Throwable ignored) { continue; }
            for (Method candidate : methods) {
                Class<?>[] parameters = candidate.getParameterTypes();
                if (!candidate.getName().equals(name) || parameters.length < args.length + 1) continue;
                int continuationIndex = parameters.length - 1;
                if (!parameters[continuationIndex].isInterface()) continue;
                boolean matches = true;
                for (int i = 0; i < args.length; i++) {
                    if (args[i] != null && !box(parameters[i]).isAssignableFrom(args[i].getClass())) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    try { candidate.setAccessible(true); } catch (Throwable ignored) { }
                    return candidate;
                }
            }
        }
        for (Method candidate : type.getMethods()) {
            Class<?>[] parameters = candidate.getParameterTypes();
            if (!candidate.getName().equals(name) || parameters.length < args.length + 1
                    || !parameters[parameters.length - 1].isInterface()) continue;
            boolean matches = true;
            for (int i = 0; i < args.length; i++) {
                if (args[i] != null && !box(parameters[i]).isAssignableFrom(args[i].getClass())) {
                    matches = false;
                    break;
                }
            }
            if (matches) return candidate;
        }
        return null;
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private static boolean isSuspended(Object value) {
        try {
            ClassLoader loader = value == null ? FloatingOtpAssistant.class.getClassLoader() : value.getClass().getClassLoader();
            Object suspended = Class.forName("kotlin.coroutines.intrinsics.IntrinsicsKt", true, loader)
                    .getMethod("m54523e").invoke(null);
            return value == suspended;
        } catch (Throwable ignored) { return false; }
    }

    private static boolean isSuccess(Object value) {
        try { return Boolean.TRUE.equals(call(value, "m48207d")); }
        catch (Throwable ignored) { return false; }
    }

    private static Object payload(Object value) {
        if (!isSuccess(value)) return null;
        try { return call(value, "m48205b"); }
        catch (Throwable ignored) { return null; }
    }

    private static Object construct(String name, Object context) throws Exception {
        return Class.forName(name).getConstructor(android.content.Context.class).newInstance(context);
    }

    private static Object call(Object target, String method) throws Exception {
        if (target == null) return null;
        return target.getClass().getMethod(method).invoke(target);
    }

    private static String getCurrentEmail(Context context) {
        try {
            Class<?> databaseClass = Class.forName("com.tempmail.data.p026db.AppDatabase");
            Object database = databaseClass.getMethod("getInstance", Context.class).invoke(null, context);
            Object dao = databaseClass.getMethod("mailboxDao").invoke(database);
            Object rows = dao.getClass().getMethod("getDefaultMailboxSync").invoke(dao);
            if (rows instanceof List && !((List<?>) rows).isEmpty()) {
                return callString(((List<?>) rows).get(0), "getFullEmailAddress");
            }
        } catch (Throwable error) {
            return "ERROR:" + safe(unwrap(error));
        }
        return "";
    }

    private static String callString(Object target, String method) {
        try { Object value = call(target, method); return value == null ? "" : String.valueOf(value); }
        catch (Throwable ignored) { return ""; }
    }

    private static int resourceId(String type, String name) {
        try { return Class.forName("com.tempmail.C4052R$" + type).getField(name).getInt(null); }
        catch (Throwable ignored) { return 0; }
    }

    private static void paste(Context context, String value, TextView status) {
        if (value == null || value.isEmpty()) { status.setText("Value is not available."); return; }
        ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        manager.setPrimaryClip(ClipData.newPlainText("Temp Mail", value));
        boolean pasted = false;
        try {
            if (context instanceof AccessibilityService) {
                AccessibilityService service = (AccessibilityService) context;
                AccessibilityNodeInfo target = findFocused(service);
                if (target != null && target.refresh() && target.isEditable()) {
                    target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    pasted = target.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                    if (!pasted) {
                        Bundle args = new Bundle();
                        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
                        pasted = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                    }
                }
            }
        } catch (Throwable ignored) { }
        if (pasted) status.setText("Pasted into the focused field.");
        else status.setText("Copied. Tap the target field, then paste from the keyboard.");
    }

    private static AccessibilityNodeInfo findFocused(AccessibilityService service) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        AccessibilityNodeInfo found = findFocusedNode(root);
        if (found != null) return found;
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            for (int pass = 0; pass < 2; pass++) {
                for (AccessibilityWindowInfo window : windows) {
                    if (pass == 0 && window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                    if (pass == 1 && window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                    found = findFocusedNode(window.getRoot());
                    if (found != null) return found;
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static AccessibilityNodeInfo findFocusedNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if ((node.isFocused() || node.isAccessibilityFocused()) && node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findFocusedNode(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private static String plain(String value) {
        return value == null ? "" : Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString();
    }

    private static LinearLayout row(Context service) {
        LinearLayout view = new LinearLayout(service);
        view.setOrientation(LinearLayout.HORIZONTAL);
        return view;
    }

    private static Button button(Context service, String text) {
        Button view = new Button(service);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setMinHeight(dp(service, 40));
        view.setPadding(dp(service, 4), 0, dp(service, 4), 0);
        return view;
    }

    private static LinearLayout.LayoutParams weightParams() { return new LinearLayout.LayoutParams(0, -2, 1f); }
    private static LinearLayout.LayoutParams verticalButtonParams(Context context) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(context, 3);
        return params;
    }
    private static int dp(Context service, int value) {
        return (int) (value * service.getResources().getDisplayMetrics().density + 0.5f);
    }
    private static void show(TextView view, String value) { MAIN.post(() -> view.setText(value)); }
    private static String safe(Object value) {
        String text = String.valueOf(value);
        return text.length() > 120 ? text.substring(0, 120) : text;
    }
    private static Throwable unwrap(Throwable error) {
        while (error instanceof java.lang.reflect.InvocationTargetException && ((java.lang.reflect.InvocationTargetException) error).getTargetException() != null)
            error = ((java.lang.reflect.InvocationTargetException) error).getTargetException();
        return error;
    }
}

