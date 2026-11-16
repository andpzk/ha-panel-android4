package ua.hapanel;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.InputType;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements LightEvents.Listener {
    private static final int PAGE = 0xff121318;
    private static final int TEXT = 0xfff5f2ed;
    private static final int MUTED = 0xffb6b5c0;
    private static final int BUTTON = 0xff242630;
    private static final int ACTIVE = 0xff3e3022;
    private static final int AMBER = 0xffffd18a;
    private static final int CONNECTED_GREEN = 0xff65d68a;
    private static final int DISCONNECTED_RED = 0xfff07878;
    private static final String DEFAULT_ADDRESS = "";
    private static final String BLACK_SCREEN_AT_NIGHT = "black_screen_at_night";
    private static final String CONNECTING = "Підключаємося…";
    private static final String CONNECTED = "Home Assistant підключено";
    private static final String SENDING_COMMAND = "Надсилаємо команду…";
    private static final Room[] ROOMS = PanelConfig.ROOMS;

    private final ExecutorService commands = Executors.newFixedThreadPool(3);
    private final Handler handler = new Handler();
    private final ArrayList<LightRow> lights = new ArrayList<>();
    private SharedPreferences preferences;
    private TextView status;
    private GradientDrawable connectionDot;
    private Button refresh;
    private Overview overview;
    private ScrollView dashboard;
    private View blackScreen;
    private float previousBrightness;
    private boolean destroyed;
    private boolean resumed;
    private long resumedAt;
    private boolean lightStatesSynced;
    private boolean lightTurnedOffSinceResume;
    private LightEvents lightEvents;
    private boolean confirmingNight;
    private final Runnable reconnectLights = this::connectLights;
    private final Runnable lightTimeout = () -> lightConnectionFailed(lightEvents, "Зв’язок із Home Assistant втрачено", true);
    private final Runnable nightCheck = () -> sleepIfDark(false);

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        preferences = getSharedPreferences("connection", MODE_PRIVATE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE);
        buildPanel();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        applyNightMode();
        resumePanel();
    }

    private void resumePanel() {
        resumedAt = SystemClock.elapsedRealtime();
        lightTurnedOffSinceResume = false;
        overview.refreshClock();
        connectLights();
        if (hasToken()) overview.resume(connection());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (NightMode.MORNING_WAKE.equals(intent.getAction())) showPanel();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 1) applyNightMode();
    }

    private void applyNightMode() {
        int flags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;
        if (NightMode.enabled(this)) {
            getWindow().addFlags(flags);
            NightMode.scheduleMorning(this);
        } else {
            getWindow().clearFlags(flags);
        }
    }

    @Override protected void onPause() {
        resumed = false;
        stopLightEvents();
        showPanel();
        overview.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        commands.shutdownNow();
        for (LightRow row : lights) clearCommand(row);
        overview.destroy();
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) sleepIfDark(false);
    }

    private void buildPanel() {
        dashboard = new ScrollView(this);
        dashboard.setBackgroundColor(PAGE);
        LinearLayout page = column();
        page.setPadding(dp(24), dp(12), dp(24), dp(12));
        dashboard.addView(page);
        FrameLayout screen = new FrameLayout(this);
        screen.addView(dashboard, new FrameLayout.LayoutParams(-1, -1));
        blackScreen = new View(this);
        blackScreen.setBackgroundColor(Color.BLACK);
        blackScreen.setContentDescription("Торкніться, щоб відкрити панель");
        blackScreen.setOnClickListener(view -> showPanel());
        blackScreen.setVisibility(View.GONE);
        screen.addView(blackScreen, new FrameLayout.LayoutParams(-1, -1));
        setContentView(screen);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(header);
        TextView title = label("Панель дому", 28, TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setCompoundDrawables(new RoomIcon(RoomIcon.HOME, dp(30), AMBER), null, null, null);
        title.setCompoundDrawablePadding(dp(12));
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button settings = button("Підключення");
        settings.setOnClickListener(view -> showConnection());
        header.addView(settings, new LinearLayout.LayoutParams(-2, dp(48)));

        boolean configured = hasToken();
        status = label("", 14, MUTED);
        connectionDot = new GradientDrawable();
        connectionDot.setShape(GradientDrawable.OVAL);
        connectionDot.setBounds(0, 0, dp(8), dp(8));
        status.setCompoundDrawables(connectionDot, null, null, null);
        status.setCompoundDrawablePadding(dp(8));
        setConnectionStatus(configured ? CONNECTING : "Потрібне підключення", configured ? AMBER : MUTED);
        refresh = button("Оновити стан");
        refresh.setEnabled(configured);
        refresh.setOnClickListener(view -> {
            stopLightEvents();
            setConnectionStatus(CONNECTING, AMBER);
            connectLights();
            overview.refreshReadings();
        });
        boolean wide = getResources().getConfiguration().screenWidthDp >= 980;
        LinearLayout info = new LinearLayout(this);
        info.setGravity(Gravity.CENTER_VERTICAL);
        if (wide) {
            setButtonIcon(settings, RoomIcon.SETTINGS, "Підключення");
            settings.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
            setButtonIcon(refresh, RoomIcon.REFRESH, "Оновити стан");
            header.addView(status, 1, new LinearLayout.LayoutParams(-2, -2));
            LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(dp(48), dp(48));
            refreshParams.setMargins(dp(12), 0, dp(8), 0);
            header.addView(refresh, 2, refreshParams);
        } else {
            page.addView(info);
            info.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
            info.addView(refresh, new LinearLayout.LayoutParams(-2, dp(48)));
        }
        overview = new Overview(this, preferences);
        LinearLayout grid = overview.addTo(page, wide);
        int columns = wide || getResources().getConfiguration().screenWidthDp >= 420 ? 3 : 2;
        LinearLayout line = null;
        for (Room room : ROOMS) {
            for (Light light : room.lights) {
                int column = lights.size() % columns;
                if (column == 0) {
                    line = new LinearLayout(this);
                    line.setWeightSum(columns);
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                    params.topMargin = dp(10);
                    grid.addView(line, params);
                }
                LightRow row = new LightRow(room, light, wide);
                lights.add(row);
                LinearLayout.LayoutParams tile = new LinearLayout.LayoutParams(0, dp(wide ? 90 : 106), 1);
                if (column != columns - 1) tile.rightMargin = dp(10);
                line.addView(row.tile, tile);
            }
        }
    }

    private void showConnection() {
        LinearLayout form = column();
        form.setPadding(dp(20), 0, dp(20), dp(10));
        EditText address = new EditText(this);
        address.setSingleLine(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setText(preferences.getString("address", DEFAULT_ADDRESS));
        form.addView(label("Адреса Home Assistant", 16, TEXT));
        form.addView(address);
        EditText token = new EditText(this);
        token.setSingleLine(true);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setHint(hasToken() ? "Порожньо — зберегти наявний" : "Довготривалий токен доступу");
        form.addView(token);
        form.addView(label("HTTP передає токен без шифрування. Використовуйте лише довірену локальну мережу.", 14, MUTED));
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Підключення").setView(form)
            .setNeutralButton("Нічний режим", (window, button) -> showNightMode())
            .setNegativeButton("Скасувати", null).setPositiveButton("Зберегти", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String server;
            try {
                server = HomeAssistant.normalizeAddress(address.getText().toString());
            } catch (IllegalArgumentException error) {
                address.setError("Введіть адресу http:// або https:// без шляху");
                return;
            }
            String key = token.getText().toString().trim();
            if (key.isEmpty() && server.equals(preferences.getString("address", ""))) {
                key = preferences.getString("token", "");
            }
            if (key.isEmpty() || key.matches("(?s).*\\s.*")) {
                token.setError("Введіть токен доступу");
                return;
            }
            preferences.edit().putString("address", server).putString("token", key).apply();
            dialog.dismiss();
            stopLightEvents();
            lightTurnedOffSinceResume = false;
            refresh.setEnabled(true);
            setConnectionStatus(CONNECTING, AMBER);
            for (LightRow row : lights) {
                clearCommand(row);
                row.show("");
            }
            connectLights();
            overview.reset();
            overview.resume(connection());
        }));
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
    }

    private void showNightMode() {
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
            .setTitle("Нічний режим")
            .setMessage("З 23:58 до 08:00 панель гасить екран, коли все освітлення вимкнене. "
                + "О 08:00 екран вмикається за часовим поясом панелі.")
            .setNegativeButton("Закрити", null);
        if (NightMode.enabled(this)) {
            CheckBox dim = new CheckBox(this);
            dim.setText("Чорний екран · повернення дотиком");
            dim.setChecked(useBlackScreen());
            dim.setOnCheckedChangeListener((view, checked) ->
                preferences.edit().putBoolean(BLACK_SCREEN_AT_NIGHT, checked).apply());
            LinearLayout options = column();
            options.setPadding(dp(16), 0, dp(16), 0);
            options.addView(dim);
            dialog.setView(options);
            dialog.setPositiveButton("Вимкнути", (window, button) -> {
                NightMode.disable(this);
                applyNightMode();
            });
            dialog.setNeutralButton("Перевірити", (window, button) -> {
                if (useBlackScreen()) showBlackScreen();
                else NightMode.sleepUntil(this, System.currentTimeMillis() + 20000);
            });
        } else {
            dialog.setPositiveButton("Увімкнути", (window, button) -> {
                Intent request = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                request.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, NightMode.admin(this));
                request.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Дозвіл потрібен для вимкнення екрана в нічному режимі.");
                startActivityForResult(request, 1);
            });
        }
        dialog.show();
    }

    private boolean useBlackScreen() {
        return preferences.getBoolean(BLACK_SCREEN_AT_NIGHT, false);
    }

    private void showBlackScreen() {
        if (blackScreen.isShown()) return;
        stopLightEvents();
        overview.pause();
        dashboard.setVisibility(View.INVISIBLE);
        blackScreen.setVisibility(View.VISIBLE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE);
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        previousBrightness = attributes.screenBrightness;
        attributes.screenBrightness = 1f / 255f;
        getWindow().setAttributes(attributes);
        Log.i("PanelNight", "Black screen shown");
    }

    private void showPanel() {
        if (blackScreen.getVisibility() != View.VISIBLE) return;
        blackScreen.setVisibility(View.GONE);
        dashboard.setVisibility(View.VISIBLE);
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.screenBrightness = previousBrightness;
        getWindow().setAttributes(attributes);
        Log.i("PanelNight", "Black screen dismissed");
        if (resumed) resumePanel();
    }

    private boolean hasToken() {
        return !preferences.getString("token", "").isEmpty();
    }

    private void toggle(LightRow row) {
        if (!lightStatesSynced || !row.button.isEnabled() || row.commandTimeout != null) return;
        sendSwitchCommand(Collections.singletonList(row), "on".equals(row.state) ? "off" : "on");
    }

    void turnOffLights() {
        if (!lightStatesSynced) {
            Toast.makeText(this, "Очікуємо підключення до Home Assistant", Toast.LENGTH_SHORT).show();
            return;
        }
        List<LightRow> targets = new ArrayList<>();
        for (LightRow row : lights) {
            if (!row.light.isLighting(row.room)) continue;
            if (row.commandTimeout != null) {
                Toast.makeText(this, "Дочекайтеся попередньої команди", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!"off".equals(row.state)) targets.add(row);
        }
        if (targets.isEmpty()) {
            Toast.makeText(this, "Усе світло вже вимкнено", Toast.LENGTH_SHORT).show();
            return;
        }
        sendSwitchCommand(targets, "off");
    }

    private void sendSwitchCommand(List<LightRow> targets, String expected) {
        HomeAssistant client = connection();
        List<String> entities = new ArrayList<>();
        Map<LightRow, Runnable> timeouts = new HashMap<>();
        long pressedAt = SystemClock.elapsedRealtime();
        for (LightRow row : targets) {
            Runnable timeout = () -> finishCommand(row, "Home Assistant ще не підтвердив стан.");
            entities.add(row.light.entity);
            timeouts.put(row, timeout);
            row.commandTimeout = timeout;
            row.expected = expected;
            row.pressedAt = pressedAt;
            handler.postDelayed(timeout, 9000);
        }
        status.setText(SENDING_COMMAND);
        commands.execute(() -> {
            try {
                client.callControls(entities, "on".equals(expected) ? "turn_on" : "turn_off");
            } catch (Exception failure) {
                handler.post(() -> {
                    for (Map.Entry<LightRow, Runnable> command : timeouts.entrySet()) {
                        LightRow row = command.getKey();
                        if (row.commandTimeout == command.getValue()) finishCommand(row, errorMessage(failure));
                    }
                });
            }
        });
    }

    private void finishCommand(LightRow row, String failure) {
        clearCommand(row);
        Log.d("PanelLatency", (failure == null ? "confirmed " : "failed ")
            + (SystemClock.elapsedRealtime() - row.pressedAt) + " ms");
        if (failure != null) {
            if (lightStatesSynced) status.setText(failure);
            sleepIfDark(false);
        } else if (lightStatesSynced && SENDING_COMMAND.contentEquals(status.getText())) {
            for (LightRow pending : lights) {
                if (pending.commandTimeout != null) return;
            }
            status.setText(CONNECTED);
        }
    }

    private void clearCommand(LightRow row) {
        if (row.commandTimeout != null) handler.removeCallbacks(row.commandTimeout);
        row.commandTimeout = null;
        row.expected = null;
    }

    private HomeAssistant connection() {
        return new HomeAssistant(
            preferences.getString("address", DEFAULT_ADDRESS), preferences.getString("token", ""));
    }

    private String errorMessage(Exception failure) {
        if (failure instanceof HomeAssistant.HttpError) {
            int code = ((HomeAssistant.HttpError) failure).code;
            if (code == 401 || code == 403) return "Home Assistant відхилив доступ. Перевірте токен.";
            return "Помилка Home Assistant · HTTP " + code;
        }
        return "Стан не оновлюється. Перевірте підключення.";
    }

    private void setConnectionStatus(String message, int color) {
        connectionDot.setColor(color);
        status.setText(message);
    }

    private void connectLights() {
        if (!resumed || destroyed || blackScreen.isShown() || !hasToken() || lightEvents != null) return;
        ArrayList<String> entities = new ArrayList<>();
        for (LightRow row : lights) entities.add(row.light.entity);
        Collections.addAll(entities, Overview.STATION_ENTITIES);
        entities.add(Overview.SCHEDULE_UPDATED);
        lightEvents = new LightEvents(preferences.getString("address", DEFAULT_ADDRESS),
            preferences.getString("token", ""), entities, this);
        handler.postDelayed(lightTimeout, 12000);
        lightEvents.connect();
    }

    private void stopLightEvents() {
        handler.removeCallbacks(reconnectLights);
        handler.removeCallbacks(lightTimeout);
        handler.removeCallbacks(nightCheck);
        if (lightStatesSynced) setConnectionStatus(CONNECTING, AMBER);
        lightStatesSynced = false;
        confirmingNight = false;
        if (lightEvents != null) {
            LightEvents previous = lightEvents;
            lightEvents = null;
            previous.closeConnection(1000, "");
        }
    }

    @Override public void disconnected(LightEvents source, String message, boolean retry) {
        handler.post(() -> lightConnectionFailed(source, message, retry));
    }

    private void lightConnectionFailed(LightEvents source, String message, boolean retry) {
        if (source == null || source != lightEvents) return;
        stopLightEvents();
        setConnectionStatus(message, DISCONNECTED_RED);
        Log.i("PanelLights", "Connection lost");
        if (retry) handler.postDelayed(reconnectLights, 3000);
    }

    @Override public void states(LightEvents source, Map<String, String> states, boolean initial) {
        handler.post(() -> {
            if (source != lightEvents) return;
            overview.updateStationReadings(states, initial);
            String scheduleUpdated = states.get(Overview.SCHEDULE_UPDATED);
            if (scheduleUpdated != null && !scheduleUpdated.isEmpty()
                    && !"unavailable".equals(scheduleUpdated) && !"unknown".equals(scheduleUpdated)) {
                if (!initial) Log.d("PanelSchedule", "Received schedule update");
                overview.refreshSchedule();
            }
            if (initial) {
                handler.removeCallbacks(lightTimeout);
                lightStatesSynced = true;
                setConnectionStatus(CONNECTED, CONNECTED_GREEN);
                Log.i("PanelLights", "Subscribed; initial states: " + states.size());
            }
            boolean lightUpdate = initial;
            for (LightRow row : lights) {
                String state = states.get(row.light.entity);
                if (!initial && state == null) continue;
                lightUpdate = true;
                if (!initial && row.light.isLighting(row.room) && "on".equals(row.state) && "off".equals(state)) {
                    lightTurnedOffSinceResume = true;
                }
                row.show(state == null ? "" : state);
                if (row.commandTimeout != null && row.expected.equals(state)) finishCommand(row, null);
            }
            if (lightUpdate) {
                if (!initial) Log.d("PanelLights", "State changes: " + states.size());
                sleepIfDark(false);
            }
        });
    }

    @Override public void pong(LightEvents source) {
        handler.post(() -> {
            if (source != lightEvents || !confirmingNight) return;
            confirmingNight = false;
            handler.removeCallbacks(lightTimeout);
            sleepIfDark(true);
        });
    }

    private void sleepIfDark(boolean confirmed) {
        handler.removeCallbacks(nightCheck);
        if (!lightStatesSynced || !hasWindowFocus() || !NightMode.enabled(this) || confirmingNight) return;
        for (LightRow row : lights) {
            if (row.light.isLighting(row.room) && (!"off".equals(row.state) || row.commandTimeout != null)) return;
        }
        long now = System.currentTimeMillis();
        if (!NightMode.isNight(now)) {
            handler.postDelayed(nightCheck, NightMode.nextNight(now) - now);
            return;
        }
        long grace = 60000 - (SystemClock.elapsedRealtime() - resumedAt);
        if (!lightTurnedOffSinceResume && grace > 0) {
            handler.postDelayed(nightCheck, grace);
            return;
        }
        if (!confirmed) {
            try {
                lightEvents.pingHomeAssistant();
                confirmingNight = true;
                handler.postDelayed(lightTimeout, 5000);
            } catch (IllegalStateException failure) {
                lightConnectionFailed(lightEvents, "Зв’язок із Home Assistant втрачено", true);
            }
            return;
        }
        if (useBlackScreen()) showBlackScreen();
        else NightMode.sleepUntil(this, NightMode.nextMorning(now));
    }

    static final class Light {
        final String name;
        final String entity;
        final int[][] icon;

        Light(String name, String id) {
            this(name, id, name.startsWith("Підсвітка") ? RoomIcon.LIGHT_STRIP : null);
        }

        Light(String name, String id, int[][] icon) {
            this.name = name;
            entity = id;
            this.icon = icon;
        }

        boolean isLighting(Room room) {
            return icon != RoomIcon.FAN && room.icon != RoomIcon.CHARGE && !isAlwaysOnOutlet();
        }

        boolean isAlwaysOnOutlet() {
            return PanelConfig.ALWAYS_ON_OUTLET.equals(entity);
        }
    }

    static final class Room {
        final String name;
        final int[][] icon;
        final Light[] lights;

        Room(String name, int[][] icon, Light... lights) {
            this.name = name;
            this.icon = icon;
            this.lights = lights;
        }
    }

    private final class LightRow {
        final Room room;
        final Light light;
        final Button button;
        final View tile;
        final View outletIndicator;
        final boolean compact;
        String state;
        Runnable commandTimeout;
        String expected;
        long pressedAt;

        LightRow(Room room, Light light, boolean compact) {
            this.room = room;
            this.light = light;
            this.compact = compact;
            button = button("");
            button.setGravity(Gravity.CENTER);
            int verticalPadding = dp(compact ? 3 : 6);
            button.setPadding(dp(4), verticalPadding, dp(4), verticalPadding);
            button.setCompoundDrawablePadding(dp(compact ? 3 : 4));
            button.setTextSize(compact ? 15 : 16);
            button.setOnClickListener(view -> toggle(this));
            if (light.isAlwaysOnOutlet()) {
                FrameLayout frame = new FrameLayout(MainActivity.this);
                frame.addView(button, new FrameLayout.LayoutParams(-1, -1));
                outletIndicator = new View(MainActivity.this);
                GradientDrawable dot = new GradientDrawable();
                dot.setShape(GradientDrawable.OVAL);
                dot.setColor(AMBER);
                outletIndicator.setBackground(dot);
                FrameLayout.LayoutParams corner = new FrameLayout.LayoutParams(dp(7), dp(7),
                    Gravity.TOP | Gravity.RIGHT);
                corner.topMargin = dp(8);
                corner.rightMargin = dp(8);
                frame.addView(outletIndicator, corner);
                tile = frame;
            } else {
                outletIndicator = null;
                tile = button;
            }
            show("");
        }

        void show(String value) {
            if (value.equals(state)) return;
            state = value;
            boolean on = "on".equals(state);
            boolean highlighted = on && outletIndicator == null;
            boolean known = on || "off".equals(state);
            String caption = on ? "Увімк." : known ? "Вимк." : "Недоступно";
            int titleEnd = room.name.length();
            int details = titleEnd + 1;
            SpannableString label = new SpannableString(room.name + "\n" + light.name);
            label.setSpan(new StyleSpan(Typeface.BOLD), 0, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int detailTextSize = compact ? 13 : 14;
            label.setSpan(new AbsoluteSizeSpan(Math.round(detailTextSize * getResources().getDisplayMetrics().scaledDensity)),
                details, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            label.setSpan(new ForegroundColorSpan(MUTED), details, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            button.setText(label);
            button.setContentDescription(room.name + " · " + light.name + " · " + caption);
            int[][] glyph = light.icon == null ? room.icon : light.icon;
            button.setCompoundDrawables(null, new RoomIcon(glyph, dp(compact ? 29 : 33),
                highlighted ? AMBER : known ? 0xffb9c5dd : 0xff858793), null, null);
            if (outletIndicator != null) outletIndicator.setVisibility(on ? View.VISIBLE : View.GONE);
            button.setActivated(highlighted);
            button.setEnabled(known);
        }
    }

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    TextView label(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    Button button(String value) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(value);
        button.setTextSize(16);
        button.setTextColor(TEXT);
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[] {-android.R.attr.state_enabled}, shape(0xff20212a));
        background.addState(new int[] {android.R.attr.state_focused}, shape(0xff46404a));
        background.addState(new int[] {android.R.attr.state_activated}, shape(ACTIVE));
        background.addState(new int[] {}, shape(BUTTON));
        button.setBackground(background);
        return button;
    }

    void setButtonIcon(Button button, int[][] glyph, String description) {
        int iconSize = dp(24);
        int inset = (dp(48) - iconSize) / 2;
        button.setText("");
        button.setContentDescription(description);
        button.setPadding(inset, 0, 0, 0);
        button.setCompoundDrawables(new RoomIcon(glyph, iconSize, MUTED), null, null, null);
    }

    private GradientDrawable shape(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(11));
        return drawable;
    }

    int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
