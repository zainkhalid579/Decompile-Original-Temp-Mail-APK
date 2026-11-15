package com.tempmail.data.services;

import android.accessibilityservice.AccessibilityService;
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
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
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
    private static final Pattern OTP = Pattern.compile(
            "(?i)(?:otp|one[- ]time(?: password)?|verification|security|access|login)\\s*(?:code|pin)?\\D{0,48}([0-9]{4,8})(?![0-9])");

    private FloatingOtpAssistant() { }

    public static void attach(AccessibilityService service, View overlay) {
        if (!(overlay instanceof RelativeLayout)) return;
        RelativeLayout root = (RelativeLayout) overlay;
        int textId = resourceId("id", "text");
        TextView address = textId == 0 ? null : root.findViewById(textId);

        LinearLayout panel = new LinearLayout(service);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(service, 8), dp(service, 5), dp(service, 8), dp(service, 8));
        panel.setBackgroundColor(Color.WHITE);
        RelativeLayout.LayoutParams panelParams = new RelativeLayout.LayoutParams(-1, -2);
        if (textId != 0) panelParams.addRule(RelativeLayout.BELOW, textId);
        root.addView(panel, panelParams);

        TextView status = new TextView(service);
        status.setText("Inbox assistant ready");
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setTextColor(Color.DKGRAY);
        panel.addView(status, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout first = row(service);
        panel.addView(first);
        Button change = button(service, "Change email");
        Button pasteEmail = button(service, "Paste email");
        first.addView(change, weightParams());
        first.addView(pasteEmail, weightParams());

        LinearLayout second = row(service);
        panel.addView(second);
        Button refresh = button(service, "Refresh inbox");
        Button pasteOtp = button(service, "Paste OTP");
        pasteOtp.setEnabled(false);
        second.addView(refresh, weightParams());
        second.addView(pasteOtp, weightParams());

        if (address != null) pasteEmail.setOnClickListener(v -> paste(service, address.getText().toString(), status));
        pasteOtp.setOnClickListener(v -> {
            Object code = pasteOtp.getTag();
            if (code instanceof String) paste(service, (String) code, status);
        });
        change.setOnClickListener(v -> {
            change.setEnabled(false);
            status.setText("Creating a new mailbox…");
            try {
                Object repository = construct("com.tempmail.data.repository.MailboxRepository", service);
                suspendCall(repository, "m48413u", new Object[0], result -> {
                    change.setEnabled(true);
                    Object mailbox = payload(result);
                    String next = callString(mailbox, "getFullEmailAddress");
                    if (next == null || next.isEmpty()) {
                        status.setText("Mailbox change failed. Try again.");
                        return;
                    }
                    if (address != null) address.setText(next);
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
                Object repository = construct("com.tempmail.data.repository.InboxRepository", service);
                suspendCall(repository, "m48347N", new Object[0], result -> {
                    refresh.setEnabled(true);
                    if (!isSuccess(result)) {
                        status.setText("Inbox refresh failed. Try again.");
                        return;
                    }
                    loadLatest(service, status, pasteOtp);
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

    private static void loadLatest(AccessibilityService service, TextView status, Button pasteOtp) {
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
            Method call = owner.getClass().getMethod(name, types);
            return call.invoke(owner, values);
        } catch (Throwable error) {
            throw unwrap(error);
        }
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

    private static void paste(AccessibilityService service, String value, TextView status) {
        if (value == null || value.isEmpty()) { status.setText("Value is not available."); return; }
        boolean pasted = false;
        try {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            AccessibilityNodeInfo target = findFocused(root);
            if (target != null && target.refresh() && target.isEditable()) {
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
                pasted = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            }
        } catch (Throwable ignored) { }
        if (pasted) status.setText("Pasted into the focused field.");
        else {
            ClipboardManager manager = (ClipboardManager) service.getSystemService(AccessibilityService.CLIPBOARD_SERVICE);
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

    private static LinearLayout row(AccessibilityService service) {
        LinearLayout view = new LinearLayout(service);
        view.setOrientation(LinearLayout.HORIZONTAL);
        return view;
    }

    private static Button button(AccessibilityService service, String text) {
        Button view = new Button(service);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setMinHeight(dp(service, 40));
        view.setPadding(dp(service, 4), 0, dp(service, 4), 0);
        return view;
    }

    private static LinearLayout.LayoutParams weightParams() { return new LinearLayout.LayoutParams(0, -2, 1f); }
    private static int dp(AccessibilityService service, int value) {
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

