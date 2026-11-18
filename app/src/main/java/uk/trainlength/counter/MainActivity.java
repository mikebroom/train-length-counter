package uk.trainlength.counter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * Train Length Counter.
 * Uses GPS speed to count down the set train length (feet) once START is pressed,
 * then plays a 1-second beep when the distance has been covered.
 */
public class MainActivity extends Activity implements LocationListener {

    private static final int REQ_LOCATION = 1;
    private static final double MS_TO_MPH = 2.2369362920544;
    private static final double M_TO_FT = 3.28083989501;
    private static final long TICK_MS = 50;          // display/integration refresh
    private static final long STALE_FIX_MS = 3000;   // treat speed as 0 if no fix for this long

    private SharedPreferences prefs;
    private LocationManager locationManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView speedView, remainingView, lengthView, statusView;
    private Button startStopButton;

    private int trainLengthFt;
    private boolean running = false;
    private double coveredFt = 0;
    private double currentSpeedMs = 0;
    private long lastFixElapsed = 0;
    private long lastTickElapsed = 0;
    private Location previousFix = null;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            double speed = (now - lastFixElapsed > STALE_FIX_MS) ? 0 : currentSpeedMs;
            if (running) {
                double dt = (now - lastTickElapsed) / 1000.0;
                coveredFt += speed * dt * M_TO_FT;
                if (coveredFt >= trainLengthFt) {
                    finishCount();
                }
            }
            lastTickElapsed = now;
            updateUi(speed);
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        trainLengthFt = prefs.getInt("train_length_ft", 600);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        buildUi();
    }

    // ---------- UI (built in code, no XML layouts needed) ----------

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView label(String text, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.parseColor("#101418"));
        root.setPadding(dp(20), dp(24), dp(20), dp(24));

        // Train length setting (tap to change)
        lengthView = label("", 18, Color.parseColor("#9FB3C8"), false);
        lengthView.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable lenBg = new GradientDrawable();
        lenBg.setColor(Color.parseColor("#1C232B"));
        lenBg.setCornerRadius(dp(10));
        lengthView.setBackground(lenBg);
        lengthView.setOnClickListener(v -> showLengthDialog());
        root.addView(lengthView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Speed
        root.addView(spacer(1));
        root.addView(label("SPEED", 16, Color.parseColor("#9FB3C8"), true));
        speedView = label("--", 96, Color.WHITE, true);
        speedView.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        root.addView(speedView);
        root.addView(label("mph", 20, Color.parseColor("#9FB3C8"), false));

        // Remaining distance
        root.addView(spacer(1));
        root.addView(label("REMAINING", 16, Color.parseColor("#9FB3C8"), true));
        remainingView = label("", 72, Color.parseColor("#FFD54F"), true);
        remainingView.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        root.addView(remainingView);
        root.addView(label("feet", 20, Color.parseColor("#9FB3C8"), false));

        statusView = label("", 16, Color.parseColor("#9FB3C8"), false);
        statusView.setPadding(0, dp(8), 0, 0);
        root.addView(statusView);

        root.addView(spacer(1));

        // Start / stop button
        startStopButton = new Button(this);
        startStopButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        startStopButton.setTypeface(Typeface.DEFAULT_BOLD);
        startStopButton.setTextColor(Color.WHITE);
        startStopButton.setAllCaps(true);
        startStopButton.setOnClickListener(v -> { if (running) stopCount(); else startCount(); });
        root.addView(startStopButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(120)));

        setContentView(root);
        refreshStaticUi();
    }

    private View spacer(float weight) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, 0, weight));
        return v;
    }

    private void refreshStaticUi() {
        lengthView.setText(String.format(Locale.UK, "Train length: %,d ft   ✎", trainLengthFt));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(Color.parseColor(running ? "#C62828" : "#2E7D32"));
        startStopButton.setBackground(bg);
        startStopButton.setText(running ? "STOP" : "START");
        lengthView.setEnabled(!running);
        lengthView.setAlpha(running ? 0.5f : 1f);
    }

    private void updateUi(double speedMs) {
        boolean haveFix = lastFixElapsed != 0 && SystemClock.elapsedRealtime() - lastFixElapsed <= STALE_FIX_MS;
        speedView.setText(haveFix ? String.format(Locale.UK, "%.0f", speedMs * MS_TO_MPH) : "--");
        double remaining = Math.max(0, trainLengthFt - coveredFt);
        remainingView.setText(String.format(Locale.UK, "%,d", (int) Math.ceil(remaining)));
        if (!haveFix) statusView.setText("Waiting for GPS…");
        else if (running) statusView.setText("Counting…");
        else statusView.setText("GPS ready");
    }

    private void showLengthDialog() {
        if (running) return;
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(trainLengthFt));
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Train length (feet)")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    try {
                        int v = Integer.parseInt(input.getText().toString().trim());
                        if (v <= 0) throw new NumberFormatException();
                        trainLengthFt = v;
                        prefs.edit().putInt("train_length_ft", v).apply();
                        coveredFt = 0;
                        refreshStaticUi();
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Enter a whole number of feet", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------- Counting ----------

    private void startCount() {
        coveredFt = 0;
        lastTickElapsed = SystemClock.elapsedRealtime();
        running = true;
        refreshStaticUi();
    }

    private void stopCount() {
        running = false;
        refreshStaticUi();
    }

    private void finishCount() {
        coveredFt = trainLengthFt;
        running = false;
        refreshStaticUi();
        playBeep();
    }

    /** 1-second 1 kHz beep generated on the fly. */
    private void playBeep() {
        new Thread(() -> {
            int sampleRate = 44100;
            int n = sampleRate; // 1 second
            short[] buf = new short[n];
            int fade = sampleRate / 100; // 10 ms fade in/out to avoid clicks
            for (int i = 0; i < n; i++) {
                double env = 1.0;
                if (i < fade) env = i / (double) fade;
                else if (i > n - fade) env = (n - i) / (double) fade;
                buf[i] = (short) (Math.sin(2 * Math.PI * 1000 * i / sampleRate) * 32000 * env);
            }
            AudioTrack track;
            if (Build.VERSION.SDK_INT >= 21) {
                track = new AudioTrack(
                        new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build(),
                        new AudioFormat.Builder()
                                .setSampleRate(sampleRate)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build(),
                        n * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE);
            } else {
                track = new AudioTrack(AudioManager.STREAM_ALARM, sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, n * 2, AudioTrack.MODE_STATIC);
            }
            track.write(buf, 0, n);
            track.play();
            try { Thread.sleep(1100); } catch (InterruptedException ignored) { }
            track.release();
        }).start();
    }

    // ---------- GPS ----------

    @Override
    protected void onResume() {
        super.onResume();
        startGps();
        lastTickElapsed = SystemClock.elapsedRealtime();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
        if (!running) stopGps();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopGps();
    }

    private void startGps() {
        if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, this, Looper.getMainLooper());
        } catch (SecurityException | IllegalArgumentException e) {
            statusView.setText("GPS unavailable");
        }
    }

    private void stopGps() {
        try { locationManager.removeUpdates(this); } catch (Exception ignored) { }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_LOCATION) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startGps();
            else Toast.makeText(this, "Precise location is needed to measure speed", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onLocationChanged(Location loc) {
        double speed;
        if (loc.hasSpeed()) {
            speed = loc.getSpeed();
        } else if (previousFix != null) {
            double dt = (loc.getElapsedRealtimeNanos() - previousFix.getElapsedRealtimeNanos()) / 1e9;
            speed = dt > 0 ? loc.distanceTo(previousFix) / dt : 0;
        } else {
            speed = 0;
        }
        if (speed < 0.3) speed = 0; // ignore GPS jitter when stationary (~0.7 mph)
        currentSpeedMs = speed;
        lastFixElapsed = SystemClock.elapsedRealtime();
        previousFix = loc;
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { statusView.setText("Turn on location/GPS"); }
}
