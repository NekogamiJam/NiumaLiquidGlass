package local.niuma.uitest;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.util.AtomicFile;
import android.view.WindowManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** A zero-permission, disposable host. Only metadata about this synthetic field is stored. */
public final class MainActivity extends Activity {
    private static final Set<String> EXPECTED = new HashSet<>(Arrays.asList(
        "", "工", "工。", "工，", "工.", "工,", "恭恭敬敬", "花花草草", "㠭", "艱難險阻", "式子", "葉子",
        "z", "⊥", "∝", "＋", "－", "。", ".", "，", ",", "`", "·", "~", "~`", "(", "{", "[{", "【", "√", "_|", "±", "±=", "=", "1", "123", "!", "£",
        "A", "AA", "AAa", "AA。", "工A", "ABC", "ABc", "ABcD", "ABcDd", "ABcDd工", "ab", "Ab", "AB", "Ⓐ", "「」",
        "＋", "＋工", "“", "⊙", "☯", "恭敬", "中国", "花花", "τ", "＆", "＆工"));
    private final Handler handler = new Handler();
    private EditText field;
    private AtomicFile state;
    private String expected;
    private String previous;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        state = new AtomicFile(new File(getFilesDir(), "ui_test_state.json"));
        String fixture = getIntent().getStringExtra("expect");
        expected = EXPECTED.contains(fixture) ? fixture : null;
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);
        TextView heading = new TextView(this);
        heading.setText("牛码手势回归 · 只输入固定测试内容");
        heading.setTextSize(20);
        layout.addView(heading);
        field = new EditText(this);
        field.setSaveEnabled(false);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN
            | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        field.setHint("独立测试输入框");
        layout.addView(field);
        setContentView(layout);
        field.requestFocus();
        field.post(() -> getSystemService(InputMethodManager.class).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT));
    }

    private final Runnable sampler = new Runnable() {
        @Override public void run() {
            publish();
            handler.postDelayed(this, 140);
        }
    };

    @Override protected void onResume() {
        super.onResume();
        handler.post(sampler);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(sampler);
        super.onPause();
    }

    private void publish() {
        FileOutputStream out = null;
        try {
            android.text.Editable text = field.getText();
            int start = BaseInputConnection.getComposingSpanStart(text);
            int end = BaseInputConnection.getComposingSpanEnd(text);
            boolean composing = start >= 0 && end > start;
            JSONObject record = new JSONObject();
            record.put("length", text.length());
            record.put("committed_length", text.length() - (composing ? end - start : 0));
            record.put("composing", composing);
            record.put("fixture_valid", expected != null);
            record.put("expected_match", expected != null && expected.contentEquals(text));
            String json = record.toString();
            if (!json.equals(previous)) {
                out = state.startWrite();
                out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
                state.finishWrite(out);
                previous = json;
            }
        } catch (Exception failure) {
            if (out != null) state.failWrite(out);
            // Never log the input text.
        }
    }
}
