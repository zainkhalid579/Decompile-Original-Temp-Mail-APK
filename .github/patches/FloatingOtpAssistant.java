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

        if (mailboxAddress != null) pasteEmail.setOnClickListener(v -> paste(context, mailboxAddress.getText().toString(), status));
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

    private static void loadLatest(Context service, TextView status, Button pasteOtp) {
        IO.execute(() -> {
            try {
                Object inbox = construct("com.tempmail.data.repository.InboxRepository", service);
                Object list = invokeWithContinuation(inbox, "m48342I", new Object[0]);
                if (!(list instanceof List)) throw new IllegalStateException("Inbox list is unavailable");
                Object latest = null;
                long newest = Long.MIN_VALUE;
                for (Object email : new ArrayList<>((List<?>) list)) {
                    Object timestamp = call(email, "getTimestampMs");
                    if (timestamp instanceof Number && ((Number) timestamp).longValue() > newest) {
                        newest = ((Number) timestamp).longValue();
                        latest = email;
                    }
                }
                if (latest == null) {
                    show(status, "No messages in this mailbox yet.");
                    return;
                }
                Object id = call(latest, "getEid");
                Object mailRepository = construct("com.tempmail.data.repository.MailRepository", service);
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
                show(status, "Inbox read failed: " + safe(error));
            }
        });
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
        Class<?> continuationType = Class.forName("kotlin.coroutines.Continuation");
        InvocationHandler handler = (proxy, method, values) -> {
            if (method.getName().equals("getContext")) {
                Class<?> context = Class.forName("kotlin.coroutines.EmptyCoroutineContext");
                Field field = context.getField("f64617B");
                return field.get(null);
            }
            if (method.getName().equals("resumeWith")) {
                try {
                    Object result = values == null ? null : values[0];
                    Class<?> resultKt = Class.forName("kotlin.ResultKt");
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
        Object continuation = Proxy.newProxyInstance(continuationType.getClassLoader(), new Class<?>[]{continuationType}, handler);
        Class<?>[] types = new Class<?>[args.length + 1];
        Object[] values = new Object[args.length + 1];
        for (int i = 0; i < args.length; i++) {
            values[i] = args[i];
            types[i] = args[i] instanceof String ? String.class : args[i].getClass();
        }
        types[args.length] = continuationType;
        values[args.length] = continuation;
        try {
            Method call = findSuspendMethod(owner.getClass(), name, values, continuation);
            if (call == null) throw new NoSuchMethodException(owner.getClass().getName() + "." + name);
            return call.invoke(owner, values);
        } catch (Throwable error) {
            throw unwrap(error);
        }
    }

    private static Method findSuspendMethod(Class<?> type, String name, Object[] values, Object continuation) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method candidate : current.getDeclaredMethods()) {
                Class<?>[] parameters = candidate.getParameterTypes();
                if (!candidate.getName().equals(name) || parameters.length != values.length) continue;
                boolean matches = true;
                for (int i = 0; i < parameters.length; i++) {
                    Object value = i == parameters.length - 1 ? continuation : values[i];
                    if (value != null && !box(parameters[i]).isInstance(value)) { matches = false; break; }
                }
                if (matches) {
                    try { candidate.setAccessible(true); } catch (Throwable ignored) { }
                    return candidate;
                }
            }
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
            Object suspended = Class.forName("kotlin.coroutines.intrinsics.IntrinsicsKt")
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
        boolean pasted = false;
        try {
            if (context instanceof AccessibilityService) {
                AccessibilityService service = (AccessibilityService) context;
                AccessibilityNodeInfo root = service.getRootInActiveWindow();
                AccessibilityNodeInfo target = findFocused(root);
                if (target != null && target.refresh() && target.isEditable()) {
                    target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Bundle args = new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
                    pasted = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                }
            }
        } catch (Throwable ignored) { }
        if (pasted) status.setText("Pasted into the focused field.");
        else {
            ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            manager.setPrimaryClip(ClipData.newPlainText("Temp Mail", value));
            status.setText("Copied. Focus the target field and paste.");
        }
    }

    private static AccessibilityNodeInfo findFocused(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isFocused() && node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findFocused(node.getChild(i));
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

