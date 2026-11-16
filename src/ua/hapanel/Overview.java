package ua.hapanel;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Space;
import android.widget.TextView;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.source.rtsp.RtspMediaSource;
import java.io.IOException;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

final class Overview {
    static final int CARD = 0xff1c1e26;
    static final int INSET = 0xff242630;
    static final int TEXT = 0xfff5f2ed;
    static final int MUTED = 0xffb6b5c0;
    static final int AMBER = 0xffffd18a;
    private static final int CARD_GAP_DP = 14;
    static final TimeZone KYIV = TimeZone.getTimeZone(PanelConfig.TIME_ZONE);
    static final Locale UK = new Locale("uk", "UA");
    private static final String TEMPERATURE = PanelConfig.TEMPERATURE;
    private static final String HUMIDITY = PanelConfig.HUMIDITY;
    private static final String CALENDAR = PanelConfig.CALENDAR;
    static final String SCHEDULE_UPDATED = PanelConfig.SCHEDULE_UPDATED;
    private static final String STREET_RTSP = PanelConfig.CAMERA_RTSP;
    private static final String LIVE_VIDEO = "live_video";
    private static final String CAMERA_HIDDEN = "camera_hidden";
    private static final int F2400_OUTPUT_READING = 2;
    private static final int F3600_OUTPUT_READING = 3;
    private static final int F2400_GRID_READING = 4;
    private static final int F3600_INPUT_READING = 5;
    private static final String[] STATION_NAMES = PanelConfig.STATION_NAMES;
    private static final String[] ENTITIES = {PanelConfig.WEATHER, TEMPERATURE, HUMIDITY};
    static final String[] STATION_ENTITIES = PanelConfig.STATION_ENTITIES;

    private final MainActivity host;
    private final SharedPreferences preferences;
    private final Handler handler = new Handler();
    private final ExecutorService readings = Executors.newSingleThreadExecutor();
    private final ExecutorService scheduleReads = Executors.newSingleThreadExecutor();
    private final ExecutorService snapshots = Executors.newSingleThreadExecutor();
    private final Runnable update = this::refreshReadings;
    private final Runnable updateCamera = this::refreshCamera;
    private final Runnable updateClock = this::refreshClock;
    private final SimpleDateFormat clockFormat = format("HH:mm");
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("EEEE, dd.MM.yyyy", UK);
    private HomeAssistant client;
    private boolean active;
    private boolean reading;
    private boolean scheduleReading;
    private boolean scheduleRefreshPending;
    private boolean fetching;
    private int generation;
    private int selectedDay;
    private String manualSelectionDate;
    private boolean historyChecked;
    private JSONObject oldTemperature;
    private JSONObject oldHumidity;
    private Bitmap frame;
    private Bitmap demoFrame;
    private ImageView image;
    private TextView cameraTitle;
    private TextView cameraStatus;
    private Button cameraRefresh;
    private Button videoMode;
    private Button cameraPrivacy;
    private TextureView video;
    private ExoPlayer player;
    private boolean liveVideo;
    private boolean cameraHidden;
    private boolean videoRendered;
    private final Runnable videoTimeout = this::videoFailed;
    private final Runnable retryVideo = this::startVideo;
    private Button today;
    private Button tomorrow;
    private TextView scheduleStatus;
    private OutageTimeline timeline;
    private TextView clockTime;
    private TextView clockDate;
    private TextView weatherValue;
    private TextView weatherCondition;
    private ImageView weatherIcon;
    private TextView climateStatus;
    private TextView bathTemperature;
    private TextView bathHumidity;
    private final TextView[] stationTitles = new TextView[STATION_NAMES.length];
    private final TextView[] stationCharges = new TextView[STATION_NAMES.length];
    private final String[] stationStates = new String[STATION_ENTITIES.length];
    private TextView station2400Output;
    private final ProgressBar[] stationBatteries = new ProgressBar[STATION_NAMES.length];
    private OutageTimeline.Day[] schedule;

    Overview(MainActivity host, SharedPreferences preferences) {
        this.host = host;
        this.preferences = preferences;
        liveVideo = preferences.getBoolean(LIVE_VIDEO, false);
        cameraHidden = preferences.getBoolean(CAMERA_HIDDEN, false);
        dateFormat.setTimeZone(KYIV);
    }

    LinearLayout addTo(LinearLayout page, boolean wide) {
        LinearLayout layout = new LinearLayout(host);
        layout.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(16);
        page.addView(layout, params);
        LinearLayout lights = panel("Керування");
        LinearLayout middle = host.column();
        LinearLayout right = host.column();
        LinearLayout[] sections = {lights, middle, right};
        int[] widths = {402, 494, 288};
        for (int i = 0; i < sections.length; i++) {
            LinearLayout.LayoutParams section = wide
                ? new LinearLayout.LayoutParams(0, -1, widths[i])
                : new LinearLayout.LayoutParams(-1, -2);
            if (i > 0) {
                int gap = dp(CARD_GAP_DP);
                section.setMargins(wide ? gap : 0, wide ? 0 : gap, 0, 0);
            }
            layout.addView(sections[i], section);
        }
        buildCamera(middle);
        buildSchedule(middle, wide);
        buildClock(right);
        buildWeather(right);
        buildClimate(right);
        buildStations(right);
        Button turnOffLights = host.button("Вимкнути все світло");
        turnOffLights.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        turnOffLights.setPadding(dp(16), 0, dp(16), 0);
        turnOffLights.setCompoundDrawables(new RoomIcon(RoomIcon.BULB_OFF, dp(22), MUTED), null, null, null);
        turnOffLights.setCompoundDrawablePadding(dp(8));
        turnOffLights.setOnClickListener(view -> host.turnOffLights());
        LinearLayout.LayoutParams turnOffParams = new LinearLayout.LayoutParams(-1, dp(48));
        turnOffParams.topMargin = dp(CARD_GAP_DP);
        right.addView(turnOffLights, turnOffParams);
        reset();
        return lights;
    }

    private void buildCamera(LinearLayout middle) {
        LinearLayout card = panel(null);
        middle.addView(card);
        LinearLayout titleRow = horizontal();
        card.addView(titleRow);
        cameraTitle = heading("Камера");
        titleRow.addView(cameraTitle, new LinearLayout.LayoutParams(0, -2, 1));
        videoMode = host.button("Відео");
        videoMode.setOnClickListener(view -> {
            preferences.edit().putBoolean(LIVE_VIDEO, !liveVideo).apply();
            if (liveVideo) stopVideo("Режим кадрів");
            else {
                liveVideo = true;
                startVideo();
            }
        });
        titleRow.addView(videoMode, new LinearLayout.LayoutParams(-2, dp(48)));
        cameraPrivacy = host.button("");
        host.setButtonIcon(cameraPrivacy, RoomIcon.EYE_OFF, "Приховати камеру");
        cameraPrivacy.setOnClickListener(view -> {
            cameraHidden = !cameraHidden;
            preferences.edit().putBoolean(CAMERA_HIDDEN, cameraHidden).apply();
            updateCameraMode();
        });
        LinearLayout.LayoutParams privacyParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        privacyParams.leftMargin = dp(8);
        titleRow.addView(cameraPrivacy, privacyParams);
        cameraRefresh = host.button("");
        host.setButtonIcon(cameraRefresh, RoomIcon.REFRESH, "Оновити кадр камери");
        cameraRefresh.setOnClickListener(view -> refreshCamera());
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        refreshParams.leftMargin = dp(8);
        titleRow.addView(cameraRefresh, refreshParams);
        image = new ImageView(host);
        image.setContentDescription("Кадр з камери");
        image.setBackgroundColor(0xff14161d);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(-1, dp(254));
        imageParams.topMargin = dp(8);
        FrameLayout preview = new FrameLayout(host);
        preview.setBackgroundColor(0xff14161d);
        card.addView(preview, imageParams);
        video = new TextureView(host);
        video.setContentDescription("Живе відео з камери");
        video.setVisibility(View.GONE);
        preview.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        preview.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = Math.min(right - left, (bottom - top) * 16 / 9);
            if (video.getLayoutParams().width != width) {
                video.setLayoutParams(new FrameLayout.LayoutParams(width, width * 9 / 16, Gravity.CENTER));
            }
        });
        preview.addView(image, new FrameLayout.LayoutParams(-1, -1));
        cameraStatus = value(card, "", 13, MUTED, 8);
    }

    private void buildSchedule(LinearLayout middle, boolean wide) {
        LinearLayout card = panel("Графік відключень");
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2, wide ? 1 : 0);
        cardParams.topMargin = dp(CARD_GAP_DP);
        middle.addView(card, cardParams);
        LinearLayout tabs = horizontal();
        below(card, tabs);
        today = host.button("Сьогодні");
        tomorrow = host.button("Завтра");
        for (Button tab : new Button[] {today, tomorrow}) {
            LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-2, dp(48));
            buttonParams.rightMargin = dp(10);
            tabs.addView(tab, buttonParams);
        }
        today.setOnClickListener(view -> selectDayManually(0));
        tomorrow.setOnClickListener(view -> selectDayManually(1));
        timeline = new OutageTimeline(host);
        LinearLayout.LayoutParams chart = new LinearLayout.LayoutParams(-1, dp(62));
        chart.topMargin = dp(15);
        card.addView(timeline, chart);
        scheduleStatus = value(card, "", 13, MUTED, 7);
        card.setMinimumHeight(dp(266));
    }

    private void buildClock(LinearLayout right) {
        LinearLayout card = panel(null);
        right.addView(card);
        clockTime = value(card, "", 48, TEXT, 0);
        clockTime.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        clockTime.setIncludeFontPadding(false);
        clockDate = value(card, "", 15, MUTED, 4);
    }

    void refreshClock() {
        handler.removeCallbacks(updateClock);
        long now = System.currentTimeMillis();
        Date current = new Date(now);
        clockTime.setText(clockFormat.format(current));
        clockDate.setText(dateFormat.format(current));
        handler.postDelayed(updateClock, 60000L - now % 60000L);
    }

    private void buildWeather(LinearLayout right) {
        LinearLayout card = panel("Погода");
        below(right, card);
        LinearLayout row = horizontal();
        card.addView(row);
        weatherValue = host.label("", 29, TEXT);
        weatherValue.setTypeface(null, Typeface.BOLD);
        row.addView(weatherValue, new LinearLayout.LayoutParams(0, -2, 1));
        weatherIcon = new ImageView(host);
        row.addView(weatherIcon, new LinearLayout.LayoutParams(dp(44), dp(36)));
        weatherCondition = value(card, "", 13, MUTED, 0);
    }

    private void buildClimate(LinearLayout right) {
        LinearLayout card = panel("Клімат · Ванна");
        below(right, card);
        TextView[] values = pair(card, "Температура", "Вологість");
        bathTemperature = values[0];
        bathHumidity = values[1];
        climateStatus = value(card, "", 12, MUTED, 4);
    }

    private void buildStations(LinearLayout right) {
        LinearLayout row = horizontal();
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2, 1);
        rowParams.topMargin = dp(CARD_GAP_DP);
        right.addView(row, rowParams);
        for (int i = 0; i < STATION_NAMES.length; i++) {
            LinearLayout card = panel(null);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(0, -1, 1);
            if (i > 0) cardParams.leftMargin = dp(CARD_GAP_DP);
            row.addView(card, cardParams);

            TextView title = heading(STATION_NAMES[i]);
            title.setCompoundDrawablePadding(dp(4));
            card.addView(title, new LinearLayout.LayoutParams(-1, -2));
            stationTitles[i] = title;

            TextView charge = value(card, "", 25, AMBER, 0);
            charge.setTypeface(null, Typeface.BOLD);
            stationCharges[i] = charge;
            if (i == 0) {
                station2400Output = value(card, "", 14, MUTED, 0);
                station2400Output.setVisibility(View.GONE);
            }

            card.addView(new Space(host), new LinearLayout.LayoutParams(0, 0, 1));
            ProgressBar battery = new ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal);
            battery.setMax(100);
            battery.getProgressDrawable().setColorFilter(AMBER, android.graphics.PorterDuff.Mode.SRC_IN);
            card.addView(battery, new LinearLayout.LayoutParams(-1, dp(8)));
            stationBatteries[i] = battery;
        }
    }

    private TextView[] pair(LinearLayout parent, String left, String right) {
        LinearLayout row = horizontal();
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(6);
        parent.addView(row, params);
        TextView[] values = new TextView[2];
        String[] captions = {left, right};
        for (int i = 0; i < captions.length; i++) {
            LinearLayout column = host.column();
            row.addView(column, new LinearLayout.LayoutParams(0, -2, 1));
            value(column, captions[i], 12, MUTED, 0);
            values[i] = value(column, "", 18, TEXT, 0);
            values[i].setTypeface(null, Typeface.BOLD);
        }
        return values;
    }

    void reset() {
        releaseVideo();
        generation++;
        handler.removeCallbacks(updateCamera);
        client = null;
        frame = null;
        updateCameraMode();
        historyChecked = false;
        oldTemperature = null;
        oldHumidity = null;
        schedule = null;
        manualSelectionDate = null;
        scheduleStatus.setText("Очікуємо графік…");
        selectDay(0);
        showReadings(new JSONObject[ENTITIES.length]);
        updateStationReadings(Collections.emptyMap(), true);
    }

    void resume(HomeAssistant connection) {
        active = true;
        client = connection;
        refreshReadings();
        updateCameraMode();
    }

    private void updateCameraMode() {
        boolean connected = active && client != null;
        cameraTitle.setVisibility(cameraHidden ? View.INVISIBLE : View.VISIBLE);
        cameraPrivacy.setActivated(cameraHidden);
        cameraPrivacy.setContentDescription(cameraHidden ? "Показати камеру" : "Приховати камеру");
        videoMode.setEnabled(connected && !cameraHidden);
        cameraRefresh.setEnabled(connected && !cameraHidden && !fetching && !videoRendered);
        if (cameraHidden) {
            if (demoFrame == null) demoFrame = makeDemoFrame();
            frame = null;
            image.setImageBitmap(demoFrame);
            image.setContentDescription("Демонстраційний кадр");
            handler.removeCallbacks(updateCamera);
            releaseVideo();
            videoMode.setText(liveVideo ? "Кадри" : "Відео");
            cameraStatus.setText("Демонстраційний кадр");
            return;
        }
        image.setImageBitmap(frame);
        image.setContentDescription("Кадр з камери");
        cameraStatus.setText(frame == null ? "Кадр ще не отримано" : "Отримано о " + frameTime);
        if (liveVideo) startVideo();
        else refreshCamera();
    }

    private static Bitmap makeDemoFrame() {
        Bitmap bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bitmap);
        GradientDrawable sky = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {0xff233b59, 0xff9d7076});
        sky.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
        sky.draw(canvas);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xffeec392);
        canvas.drawCircle(232, 67, 22, paint);
        Path ridge = new Path();
        ridge.moveTo(0, 124);
        ridge.lineTo(78, 58);
        ridge.lineTo(155, 124);
        ridge.lineTo(246, 101);
        ridge.lineTo(320, 138);
        ridge.lineTo(320, 180);
        ridge.lineTo(0, 180);
        ridge.close();
        paint.setColor(0xff465777);
        canvas.drawPath(ridge, paint);
        ridge.reset();
        ridge.moveTo(0, 146);
        ridge.cubicTo(100, 120, 149, 173, 320, 126);
        ridge.lineTo(320, 180);
        ridge.lineTo(0, 180);
        ridge.close();
        paint.setColor(0xff283c52);
        canvas.drawPath(ridge, paint);
        return bitmap;
    }

    void pause() {
        active = false;
        scheduleRefreshPending = false;
        handler.removeCallbacks(update);
        handler.removeCallbacks(updateCamera);
        handler.removeCallbacks(updateClock);
        releaseVideo();
    }

    void destroy() {
        pause();
        generation++;
        client = null;
        readings.shutdownNow();
        scheduleReads.shutdownNow();
        snapshots.shutdownNow();
    }

    void refreshReadings() {
        refreshSchedule();
        if (!active || client == null || reading) return;
        handler.removeCallbacks(update);
        reading = true;
        final HomeAssistant source = client;
        final int request = generation;
        final boolean checkHistory = !historyChecked;
        readings.execute(() -> {
            JSONObject[] values = new JSONObject[ENTITIES.length];
            for (int i = 0; i < ENTITIES.length; i++) {
                try {
                    values[i] = source.state(ENTITIES[i]);
                } catch (HomeAssistant.HttpError error) {
                    if (error.code == 401 || error.code == 403) break;
                } catch (Exception error) {
                    break;
                }
            }
            final boolean missingClimate = number(state(values[1])) == null || number(state(values[2])) == null;
            host.runOnUiThread(() -> {
                if (request != generation || client == null) return;
                showReadings(values);
            });
            final JSONObject previousTemperature = checkHistory && missingClimate ? lastReading(source, TEMPERATURE) : null;
            final JSONObject previousHumidity = checkHistory && missingClimate ? lastReading(source, HUMIDITY) : null;
            host.runOnUiThread(() -> {
                reading = false;
                if (client == null || !active) return;
                if (request != generation) {
                    refreshReadings();
                    return;
                }
                if (previousTemperature != null) oldTemperature = previousTemperature;
                if (previousHumidity != null) oldHumidity = previousHumidity;
                if (checkHistory && missingClimate) {
                    historyChecked = oldTemperature != null && oldHumidity != null;
                    showClimate(values);
                }
                handler.postDelayed(update, 60000);
            });
        });
    }

    void refreshSchedule() {
        if (!active || client == null) return;
        if (scheduleReading) {
            scheduleRefreshPending = true;
            return;
        }
        scheduleReading = true;
        final HomeAssistant source = client;
        final int request = generation;
        scheduleReads.execute(() -> {
            OutageTimeline.Day[] days = null;
            try {
                days = OutageTimeline.read(source, CALENDAR);
            } catch (Exception ignored) {
                // The panel will retry on the next update.
            }
            final OutageTimeline.Day[] freshDays = days;
            host.runOnUiThread(() -> {
                scheduleReading = false;
                if (!active || client == null) return;
                if (request == generation) {
                    schedule = freshDays;
                    if (freshDays != null && !scheduleDate().equals(manualSelectionDate)) {
                        selectDay(freshDays[0].ranges.isEmpty() ? 1 : 0);
                    } else {
                        showSchedule();
                    }
                    if (freshDays != null) Log.d("PanelSchedule", "Calendar displayed");
                }
                if (scheduleRefreshPending) {
                    scheduleRefreshPending = false;
                    refreshSchedule();
                }
            });
        });
    }

    private void startVideo() {
        if (cameraHidden || !active || !liveVideo || client == null || player != null) return;
        videoMode.setText("Кадри");
        if (frame == null) cameraStatus.setText("Підключаємо відео…");
        video.setVisibility(View.VISIBLE);
        // The vendor H.264 decoder crashes on this Ainol firmware.
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(host)
            .setMediaCodecSelector((mime, secure, tunneling) -> {
                ArrayList<MediaCodecInfo> software = new ArrayList<>();
                for (MediaCodecInfo codec :
                        MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)) {
                    if (codec.name.startsWith("OMX.google.")) software.add(codec);
                }
                return software;
            });
        final ExoPlayer attempt = new ExoPlayer.Builder(host, renderers)
            .setLoadControl(new DefaultLoadControl.Builder()
                .setBufferDurationsMs(800, 2500, 450, 650).build())
            .build();
        player = attempt;
        attempt.setVideoTextureView(video);
        attempt.addListener(new Player.Listener() {
            @Override public void onRenderedFirstFrame() {
                if (player != attempt) return;
                videoRendered = true;
                handler.removeCallbacks(updateCamera);
                cameraRefresh.setEnabled(false);
                image.setVisibility(View.INVISIBLE);
                cameraStatus.setText("Наживо · 640 × 360");
                android.util.Log.i("PanelVideo", "RTSP first frame");
            }

            @Override public void onPlaybackStateChanged(int state) {
                if (player != attempt) return;
                if (state == Player.STATE_READY) {
                    handler.removeCallbacks(videoTimeout);
                    if (videoRendered) cameraStatus.setText("Наживо · 640 × 360");
                } else if (state == Player.STATE_BUFFERING) {
                    handler.removeCallbacks(videoTimeout);
                    if (videoRendered) cameraStatus.setText("Буферизація відео…");
                    handler.postDelayed(videoTimeout, 8000);
                } else if (state == Player.STATE_ENDED) {
                    handler.removeCallbacks(videoTimeout);
                    handler.post(videoTimeout);
                }
                android.util.Log.d("PanelVideo", "playback state " + state);
            }

            @Override public void onPlayerError(PlaybackException error) {
                if (player != attempt) return;
                android.util.Log.w("PanelVideo", error.getErrorCodeName());
                handler.removeCallbacks(videoTimeout);
                handler.post(videoTimeout);
            }
        });
        attempt.setMediaSource(new RtspMediaSource.Factory().setForceUseRtpTcp(true).setTimeoutMs(5000)
            .createMediaSource(MediaItem.fromUri(STREET_RTSP)));
        attempt.prepare();
        attempt.play();
    }

    private void videoFailed() {
        if (!active || !liveVideo || player == null) return;
        android.util.Log.w("PanelVideo", "RTSP unavailable, showing snapshots");
        releaseVideo();
        videoMode.setText("Кадри");
        cameraStatus.setText("Відновлюємо відео…");
        cameraRefresh.setEnabled(!fetching);
        refreshCamera();
        handler.postDelayed(retryVideo, 5000);
    }

    private void releaseVideo() {
        handler.removeCallbacks(videoTimeout);
        handler.removeCallbacks(retryVideo);
        videoRendered = false;
        video.setVisibility(View.GONE);
        image.setVisibility(View.VISIBLE);
        if (player != null) {
            ExoPlayer previous = player;
            player = null;
            previous.release();
        }
        videoMode.setText("Відео");
    }

    private void stopVideo(String message) {
        liveVideo = false;
        releaseVideo();
        cameraStatus.setText(message);
        cameraRefresh.setEnabled(!fetching);
        refreshCamera();
    }

    private void refreshCamera() {
        if (cameraHidden || !active || client == null || fetching || videoRendered) return;
        handler.removeCallbacks(updateCamera);
        fetching = true;
        final HomeAssistant source = client;
        final int request = generation;
        cameraRefresh.setEnabled(false);
        if (frame == null) cameraStatus.setText("Завантажуємо кадр…");
        snapshots.execute(() -> {
            Bitmap picture = null;
            try {
                byte[] bytes = source.camera(PanelConfig.CAMERA);
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException();
                options.inSampleSize = 1;
                while (options.outWidth / options.inSampleSize > 960
                        || options.outHeight / options.inSampleSize > 540) options.inSampleSize *= 2;
                options.inJustDecodeBounds = false;
                picture = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            } catch (Exception ignored) {
                // Keep the previous snapshot visible if the camera cannot be reached.
            }
            final Bitmap fresh = picture;
            host.runOnUiThread(() -> {
                fetching = false;
                if (cameraHidden || !active || client == null || videoRendered) return;
                if (request != generation) {
                    refreshCamera();
                    return;
                }
                cameraRefresh.setEnabled(true);
                if (fresh == null) {
                    cameraStatus.setText(frame == null ? "Камера недоступна" : "Кадр не оновився · " + frameTime);
                } else {
                    frame = fresh;
                    frameTime = format("HH:mm:ss").format(new Date());
                    image.setImageBitmap(frame);
                    cameraStatus.setText("Отримано о " + frameTime);
                }
                if (active) handler.postDelayed(updateCamera, fresh == null ? 15000 : 5000);
            });
        });
    }

    private String frameTime = "";

    private void showReadings(JSONObject[] values) {
        JSONObject weather = values[0] == null ? null : values[0].optJSONObject("attributes");
        weatherValue.setText(measure(weather == null ? "" : weather.optString("temperature"),
            weather == null ? " °C" : " " + weather.optString("temperature_unit", "°C")));
        String condition = state(values[0]);
        weatherCondition.setText(weatherName(condition));
        int[][] glyph = "sunny".equals(condition) ? RoomIcon.SUN
            : condition.contains("rain") || condition.equals("pouring") ? RoomIcon.RAIN : RoomIcon.CLOUD;
        weatherIcon.setImageDrawable(number(weather == null ? "" : weather.optString("temperature")) == null
            ? null : new RoomIcon(glyph, dp(37), 0xffbcc4da));
        showClimate(values);
    }

    void updateStationReadings(Map<String, String> states, boolean initial) {
        boolean changed = false;
        for (int i = 0; i < STATION_ENTITIES.length; i++) {
            String value = states.get(STATION_ENTITIES[i]);
            if (value == null && !initial) continue;
            value = value == null ? "" : value;
            if (value.equals(stationStates[i])) continue;
            stationStates[i] = value;
            changed = true;
        }
        if (changed) showStations();
    }

    private void showStations() {
        String output2400State = stationStates[F2400_OUTPUT_READING];
        Double output2400 = number(output2400State);
        boolean showOutput2400 = output2400 != null && output2400 > 0;
        if (showOutput2400) station2400Output.setText(measure(output2400State, " Вт"));
        station2400Output.setVisibility(showOutput2400 ? View.VISIBLE : View.GONE);
        Double output3600 = number(stationStates[F3600_OUTPUT_READING]);
        Double input3600 = number(stationStates[F3600_INPUT_READING]);
        updateStationIcon(0, output2400, "off".equals(stationStates[F2400_GRID_READING]));
        updateStationIcon(1, output3600, input3600 != null && input3600 == 0);
        for (int i = 0; i < STATION_NAMES.length; i++) {
            String level = stationStates[i];
            Double percentage = number(level);
            TextView charge = stationCharges[i];
            charge.setText(percentage == null ? "Недоступно" : measure(level, "%"));
            charge.setTextColor(percentage == null ? MUTED : AMBER);
            charge.setTextSize(percentage == null ? 14 : 25);
            ProgressBar battery = stationBatteries[i];
            battery.setProgress(percentage == null ? 0 : (int) Math.round(percentage));
            battery.setVisibility(percentage == null ? View.GONE : View.VISIBLE);
        }
    }

    private void updateStationIcon(int station, Double output, boolean offGrid) {
        boolean supplying = offGrid && output != null && output > 0;
        TextView title = stationTitles[station];
        title.setCompoundDrawables(null, null,
            new RoomIcon(RoomIcon.POWER, dp(18), supplying ? AMBER : 0xff73788a), null);
        title.setContentDescription(STATION_NAMES[station] + (supplying ? " · живить навантаження" : ""));
    }

    private void showClimate(JSONObject[] values) {
        String temperature = state(values[1]);
        String humidity = state(values[2]);
        boolean missingTemperature = number(temperature) == null;
        boolean missingHumidity = number(humidity) == null;
        bathTemperature.setText(measure(missingTemperature ? state(oldTemperature) : temperature, " °C"));
        bathHumidity.setText(measure(missingHumidity ? state(oldHumidity) : humidity, "%"));
        String temperatureDate = missingTemperature ? readingDate(oldTemperature) : "";
        String humidityDate = missingHumidity ? readingDate(oldHumidity) : "";
        String dates = temperatureDate.isEmpty() ? humidityDate : humidityDate.isEmpty() || temperatureDate.equals(humidityDate)
            ? temperatureDate : temperatureDate + " / " + humidityDate;
        boolean cached = (missingTemperature && oldTemperature != null) || (missingHumidity && oldHumidity != null);
        climateStatus.setText(cached ? "Офлайн · останні виміри" + (dates.isEmpty() ? "" : " " + dates)
            : missingTemperature || missingHumidity ? "Дані недоступні" : "");
        bathTemperature.setTextColor(missingTemperature ? MUTED : TEXT);
        bathHumidity.setTextColor(missingHumidity ? MUTED : TEXT);
    }

    private void selectDayManually(int day) {
        manualSelectionDate = scheduleDate();
        selectDay(day);
    }

    private String scheduleDate() {
        return format("yyyy-MM-dd").format(new Date());
    }

    private void selectDay(int day) {
        selectedDay = day;
        today.setActivated(day == 0);
        tomorrow.setActivated(day == 1);
        if (schedule != null) showSchedule();
        else timeline.setDay(null);
    }

    private void showSchedule() {
        OutageTimeline.Day day = schedule == null ? null : schedule[selectedDay];
        timeline.setDay(day);
        String description = day == null ? "Графік недоступний" : day.description();
        SpannableString colored = new SpannableString(description);
        for (String heading : new String[] {"Відключення", "Можливе ввімкнення"}) {
            int start = description.indexOf(heading);
            if (start >= 0) colored.setSpan(new ForegroundColorSpan(
                "Відключення".equals(heading) ? AMBER : OutageTimeline.POSSIBLE_ON), start,
                start + heading.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        scheduleStatus.setText(colored);
    }

    private static JSONObject lastReading(HomeAssistant client, String entity) {
        try {
            SimpleDateFormat clock = format("yyyy-MM-dd'T'HH:mm:ssZ");
            long now = System.currentTimeMillis();
            String start = clock.format(new Date(now - 864000000L));
            JSONArray history = client.history(entity, start, clock.format(new Date(now))).optJSONArray(0);
            JSONObject latest = null;
            long latestAt = Long.MIN_VALUE;
            if (history != null) {
                for (int i = 0; i < history.length(); i++) {
                    JSONObject row = history.optJSONObject(i);
                    if (row != null && entity.equals(row.optString("entity_id"))
                            && number(state(row)) != null) {
                        Date at = readingTime(row);
                        if (at != null && at.getTime() > latestAt) {
                            latest = row;
                            latestAt = at.getTime();
                        }
                    }
                }
            }
            return latest;
        } catch (Exception ignored) {
            // History may be disabled or the entity may not be recorded.
        }
        return null;
    }

    private static String readingDate(JSONObject row) {
        Date at = readingTime(row);
        return at == null ? "" : format("dd.MM").format(at);
    }

    private static Date readingTime(JSONObject row) {
        if (row == null) return null;
        try {
            return OutageTimeline.timestamp(row.optString("last_changed", row.optString("last_updated")));
        } catch (Exception ignored) {
            return null;
        }
    }

    static SimpleDateFormat format(String pattern) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
        format.setTimeZone(KYIV);
        format.setLenient(false);
        return format;
    }

    private static String state(JSONObject object) {
        return object == null ? "" : object.optString("state");
    }

    private static Double number(String value) {
        try {
            double result = Double.parseDouble(value);
            return Double.isInfinite(result) || Double.isNaN(result) ? null : result;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String measure(String raw, String unit) {
        Double value = number(raw);
        if (value == null) return "—";
        NumberFormat format = NumberFormat.getNumberInstance(UK);
        format.setMaximumFractionDigits(2);
        return format.format(value) + unit;
    }

    private static String weatherName(String state) {
        switch (state) {
            case "sunny": return "Сонячно";
            case "clear-night": return "Ясна ніч";
            case "partlycloudy": return "Мінлива хмарність";
            case "cloudy": return "Хмарно";
            case "fog": return "Туман";
            case "rainy": return "Дощ";
            case "pouring": return "Злива";
            case "snowy": return "Сніг";
            case "snowy-rainy": return "Сніг із дощем";
            case "lightning": case "lightning-rainy": return "Гроза";
            case "windy": case "windy-variant": return "Вітряно";
            case "hail": return "Град";
            default: return "Дані недоступні";
        }
    }

    private LinearLayout panel(String title) {
        LinearLayout panel = host.column();
        panel.setBackground(shape(CARD, 19));
        panel.setPadding(dp(16), dp(11), dp(16), dp(11));
        if (title != null) panel.addView(heading(title));
        return panel;
    }

    private TextView heading(String title) {
        TextView view = host.label(title, 20, TEXT);
        view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(host);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private void below(LinearLayout parent, LinearLayout child) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(CARD_GAP_DP);
        parent.addView(child, params);
    }

    private LinearLayout inset(LinearLayout parent, int margin) {
        LinearLayout box = host.column();
        box.setPadding(dp(10), dp(6), dp(10), dp(6));
        box.setBackground(shape(INSET, 11));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(margin);
        parent.addView(box, params);
        return box;
    }

    private TextView value(LinearLayout parent, String label, int size, int color, int margin) {
        TextView view = host.label(label, size, color);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(margin);
        parent.addView(view, params);
        return view;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(int value) { return host.dp(value); }
}
