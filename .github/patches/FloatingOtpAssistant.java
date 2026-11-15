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
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.tempmail.C4052R;
import com.tempmail.data.Resource;
import com.tempmail.data.api.models.answers.ExtendedMail;
import com.tempmail.data.p026db.EmailTable;
import com.tempmail.data.p026db.MailboxTable;
import com.tempmail.data.repository.InboxRepository;
import com.tempmail.data.repository.MailRepository;
import com.tempmail.data.repository.MailboxRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kotlin.ResultKt;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;

/** Adds the OTP controls to the app's existing autofill overlay. */
public final class FloatingOtpAssistant {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Pattern OTP = Pattern.compile(
            "(?i)(?:otp|one[- ]time(?: password)?|verification|security|access|login)\\s*(?:code|pin)?\\D{0,48}([0-9]{4,8})(?![0-9])");

    private FloatingOtpAssistant() { }

    private interface SuspendCall {
        Object invoke(Continuation<Object> continuation) throws Throwable;
    }

    public static void attach(final AccessibilityService service, final RelativeLayout root,
                              final MailboxTable mailbox, final AccessibilityNodeInfo target) {
        LinearLayout panel = new LinearLayout(service);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(service, 8), dp(service, 5), dp(service, 8), dp(service, 8));
        panel.setBackgroundColor(Color.WHITE);
        RelativeLayout.LayoutParams panelParams = new RelativeLayout.LayoutParams(-1, -2);
        panelParams.addRule(RelativeLayout.BELOW, C4052R.id.text);
        root.addView(panel, panelParams);

        TextView status = new TextView(service);
        status.setText("Inbox assistant ready");
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setTextColor(Color.DKGRAY);
        panel.addView(status, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout firstRow = row(service);
        panel.addView(firstRow);
        Button change = button(service, "Change email");
        Button pasteEmail = button(service, "Paste email");
        firstRow.addView(change, weightParams());
        firstRow.addView(pasteEmail, weightParams());

        LinearLayout secondRow = row(service);
        panel.addView(secondRow);
        Button refresh = button(service, "Refresh inbox");
        Button pasteOtp = button(service, "Paste OTP");
        pasteOtp.setEnabled(false);
        secondRow.addView(refresh, weightParams());
        secondRow.addView(pasteOtp, weightParams());

        final String email = mailbox.getFullEmailAddress();
        pasteEmail.setOnClickListener(v -> pasteValue(service, target, email, status, "Email"));
        pasteOtp.setOnClickListener(v -> {
            Object value = pasteOtp.getTag();
            if (value instanceof String) pasteValue(service, target, (String) value, status, "OTP");
        });
        change.setOnClickListener(v -> {
            change.setEnabled(false);
            status.setText("Creating a new mailbox…");
            MailboxRepository repository = new MailboxRepository(service);
            startSuspend(c -> repository.m48413u(c), result -> {
                change.setEnabled(true);
                try {
                    Object payload = payload(result);
                    if (payload instanceof MailboxTable) {
                        String nextEmail = ((MailboxTable) payload).getFullEmailAddress();
                        TextView address = root.findViewById(C4052R.id.text);
                        address.setText(nextEmail);
                        status.setText("Mailbox changed. Tap Refresh inbox for messages.");
                        pasteEmail.setOnClickListener(x -> pasteValue(service, target, nextEmail, status, "Email"));
                        pasteOtp.setTag(null);
                        pasteOtp.setEnabled(false);
                    } else {
                        status.setText("Mailbox change failed. Try again.");
                    }
                } catch (Throwable error) {
                    status.setText("Mailbox change failed: " + safeMessage(error));
                }
            }, status);
        });
        refresh.setOnClickListener(v -> {
            refresh.setEnabled(false);
            pasteOtp.setEnabled(false);
            pasteOtp.setTag(null);
            status.setText("Refreshing the Temp Mail inbox…");
            InboxRepository repository = new InboxRepository(service);
            startSuspend(c -> repository.m48347N(c), result -> {
                refresh.setEnabled(true);
                try {
                    Resource<?> resource = (Resource<?>) result;
                    if (!resource.m48207d()) {
                        status.setText("Inbox refresh failed. Try again.");
                        return;
                    }
                    loadLatestMessage(service, status, pasteOtp);
                } catch (Throwable error) {
                    status.setText("Inbox refresh failed: " + safeMessage(error));
                }
            }, status);
        });
    }

    private static void loadLatestMessage(AccessibilityService service, TextView status, Button pasteOtp) {
        IO.execute(() -> {
            try {
                InboxRepository inbox = new InboxRepository(service);
                Object listed = inbox.m48342I(new Continuation<List<EmailTable>>() {
                    @Override public CoroutineContext getContext() { return EmptyCoroutineContext.f64617B; }
                    @Override public void resumeWith(Object ignored) { }
                });
                if (!(listed instanceof List)) throw new IllegalStateException("Inbox list is unavailable");
                List<?> values = new ArrayList<>((List<?>) listed);
                EmailTable latest = null;
                for (Object item : values) {
                    if (item instanceof EmailTable && (latest == null || ((EmailTable) item).getTimestampMs() > latest.getTimestampMs())) {
                        latest = (EmailTable) item;
                    }
                }
                if (latest == null) {
                    setStatus(status, "No messages in this mailbox yet.");
                    return;
                }
                MailRepository mailRepository = new MailRepository(service);
                String id = latest.getEid();
                startSuspend(c -> mailRepository.m48374C(id, c), result -> {
                    try {
                        Resource<?> resource = (Resource<?>) result;
                        if (!resource.m48207d() || !(resource.m48205b() instanceof ExtendedMail)) {
                            status.setText("Inbox refreshed; latest message details are unavailable.");
                            return;
                        }
                        ExtendedMail mail = (ExtendedMail) resource.m48205b();
                        String text = plain(mail.getMailSubject()) + "\n" + plain(mail.getPreview()) + "\n" + plain(mail.getMailHtml());
                        Matcher matcher = OTP.matcher(text);
                        if (matcher.find()) {
                            String code = matcher.group(1);
                            pasteOtp.setTag(code);
                            pasteOtp.setEnabled(true);
                            status.setText("OTP found in: " + safeMessage(mail.getMailSubject()));
                        } else {
                            pasteOtp.setTag(null);
                            pasteOtp.setEnabled(false);
                            status.setText("Inbox refreshed. No verification code found in the latest message.");
                        }
                    } catch (Throwable error) {
                        status.setText("Message read failed: " + safeMessage(error));
                    }
                }, status);
            } catch (Throwable error) {
                setStatus(status, "Inbox read failed: " + safeMessage(error));
            }
        });
    }

    private static void startSuspend(SuspendCall call, Consumer<Object> completion, TextView status) {
        IO.execute(() -> {
            final boolean[] resumed = {false};
            Continuation<Object> continuation = new Continuation<Object>() {
                @Override public CoroutineContext getContext() { return EmptyCoroutineContext.f64617B; }
                @Override public void resumeWith(Object result) {
                    resumed[0] = true;
                    MAIN.post(() -> complete(result, completion, status));
                }
            };
            try {
                Object immediate = call.invoke(continuation);
                if (!resumed[0] && immediate != IntrinsicsKt.m54523e()) {
                    MAIN.post(() -> complete(immediate, completion, status));
                }
            } catch (Throwable error) {
                MAIN.post(() -> status.setText("Request failed: " + safeMessage(error)));
            }
        });
    }

    private static void complete(Object result, Consumer<Object> completion, TextView status) {
        try {
            ResultKt.m53922b(result);
            completion.accept(result);
        } catch (Throwable error) {
            status.setText("Request failed: " + safeMessage(error));
        }
    }

    private static Object payload(Object value) {
        if (!(value instanceof Resource)) return null;
        Resource<?> resource = (Resource<?>) value;
        return resource.m48207d() ? resource.m48205b() : null;
    }

    private static void pasteValue(AccessibilityService service, AccessibilityNodeInfo target,
                                   String value, TextView status, String label) {
        if (value == null || value.isEmpty()) {
            status.setText(label + " is not available.");
            return;
        }
        boolean pasted = false;
        try {
            if (target != null && target.refresh() && target.isEditable()) {
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
                pasted = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            }
        } catch (Throwable ignored) { }
        if (pasted) {
            status.setText(label + " pasted into the focused field.");
        } else {
            ClipboardManager clipboard = (ClipboardManager) service.getSystemService(AccessibilityService.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
            status.setText(label + " copied. Focus the target field and paste.");
        }
    }

    private static String plain(String value) {
        if (value == null) return "";
        return Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString().replace('\u00a0', ' ');
    }

    private static LinearLayout row(AccessibilityService service) {
        LinearLayout row = new LinearLayout(service);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private static Button button(AccessibilityService service, String label) {
        Button button = new Button(service);
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        button.setMinHeight(dp(service, 40));
        button.setPadding(dp(service, 4), 0, dp(service, 4), 0);
        return button;
    }

    private static LinearLayout.LayoutParams weightParams() {
        return new LinearLayout.LayoutParams(0, -2, 1f);
    }

    private static int dp(AccessibilityService service, int value) {
        return (int) (value * service.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static void setStatus(TextView view, String text) { MAIN.post(() -> view.setText(text)); }

    private static String safeMessage(Object value) {
        String text = String.valueOf(value);
        return text.length() > 120 ? text.substring(0, 120) : text;
    }
}

