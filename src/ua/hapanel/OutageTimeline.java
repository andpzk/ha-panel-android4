package ua.hapanel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import java.text.ParsePosition;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import org.json.JSONArray;
import org.json.JSONObject;

final class OutageTimeline extends View {
    static final int POSSIBLE_ON = 0xffa1bce3;
    private static final String[] TICKS = {"00:00", "06:00", "12:00", "18:00", "24:00"};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Day day;

    OutageTimeline(Context context) {
        super(context);
        setContentDescription("Шкала відключень від 00:00 до 24:00");
    }

    void setDay(Day value) {
        day = value;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float width = getWidth();
        float top = 10 * density;
        float bottom = 23 * density;
        paint.setColor(0xff383a45);
        canvas.drawRect(0, top, width, bottom, paint);
        if (day != null) {
            for (Interval range : day.ranges) {
                paint.setColor(range.possibleOn ? POSSIBLE_ON : Overview.AMBER);
                canvas.drawRect(Math.round(width * day.fraction(range.start)), top,
                    Math.round(width * day.fraction(range.end)), bottom, paint);
            }
        }
        paint.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
        for (int i = 0; i < TICKS.length; i++) {
            float x = width * (day == null ? i / 4f : day.hourFraction(i * 6));
            paint.setColor(0xff666873);
            canvas.drawLine(x, 27 * density, x, 33 * density, paint);
            paint.setColor(Overview.MUTED);
            paint.setTextAlign(i == 0 ? Paint.Align.LEFT : i == 4 ? Paint.Align.RIGHT : Paint.Align.CENTER);
            canvas.drawText(TICKS[i], x, 52 * density, paint);
        }
    }

    static Day[] read(HomeAssistant client, String entity) throws Exception {
        Calendar midnight = Calendar.getInstance(Overview.KYIV);
        midnight.set(Calendar.HOUR_OF_DAY, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 0);
        midnight.set(Calendar.MILLISECOND, 0);
        long start = midnight.getTimeInMillis();
        midnight.add(Calendar.DAY_OF_MONTH, 1);
        long next = midnight.getTimeInMillis();
        midnight.add(Calendar.DAY_OF_MONTH, 1);
        long end = midnight.getTimeInMillis();
        String pattern = "yyyy-MM-dd'T'HH:mm:ssZ";
        JSONArray events = client.calendar(entity, Overview.format(pattern).format(new Date(start)),
            Overview.format(pattern).format(new Date(end)));
        return parse(events, start, next, end);
    }

    static Day[] parse(JSONArray events, long start, long next, long end) throws Exception {
        Day[] days = {new Day(start, next), new Day(next, end)};
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.getJSONObject(i);
            String summary = event.optString("summary");
            boolean possibleOn = "Power POSSIBLE ON".equalsIgnoreCase(summary);
            if (!possibleOn && !"Power OFF".equalsIgnoreCase(summary)) continue;
            long from = instant(event.getJSONObject("start"));
            long until = instant(event.getJSONObject("end"));
            if (until <= from) throw new java.io.IOException("Invalid calendar interval");
            for (Day day : days) {
                long left = Math.max(day.start, from);
                long right = Math.min(day.end, until);
                if (left < right) day.ranges.add(new Interval(left, right, possibleOn));
            }
        }
        for (Day day : days) Collections.sort(day.ranges, new Comparator<Interval>() {
            @Override public int compare(Interval left, Interval right) {
                return left.start < right.start ? -1 : left.start == right.start ? 0 : 1;
            }
        });
        return days;
    }

    private static long instant(JSONObject endpoint) throws Exception {
        String value = endpoint.optString("dateTime");
        return timestamp(value.isEmpty() ? endpoint.getString("date") : value).getTime();
    }

    static Date timestamp(String value) throws Exception {
        String pattern = value.contains("T") ? "yyyy-MM-dd'T'HH:mm:ssZ" : "yyyy-MM-dd";
        value = value.replaceFirst("\\.\\d+", "").replaceFirst("Z$", "+0000")
            .replaceFirst("([+-]\\d{2}):(\\d{2})$", "$1$2");
        ParsePosition position = new ParsePosition(0);
        Date date = Overview.format(pattern).parse(value, position);
        if (date == null || position.getIndex() != value.length()) throw new java.io.IOException("Invalid calendar date");
        return date;
    }

    static final class Day {
        final long start;
        final long end;
        final ArrayList<Interval> ranges = new ArrayList<>();

        Day(long start, long end) {
            this.start = start;
            this.end = end;
        }

        float fraction(long time) {
            return (float) (time - start) / (end - start);
        }

        float hourFraction(int hour) {
            Calendar clock = Calendar.getInstance(Overview.KYIV);
            clock.setTimeInMillis(start);
            clock.set(Calendar.HOUR_OF_DAY, hour);
            return fraction(clock.getTimeInMillis());
        }

        String description() {
            if (ranges.isEmpty()) return "У календарі немає записів";
            StringBuilder result = new StringBuilder();
            for (boolean possibleOn : new boolean[] {false, true}) {
                StringBuilder times = new StringBuilder();
                for (Interval range : ranges) {
                    if (range.possibleOn != possibleOn) continue;
                    if (times.length() != 0) times.append(", ");
                    times.append(Overview.format("HH:mm").format(new Date(range.start)));
                    times.append("–");
                    times.append(range.end == end ? "24:00" : Overview.format("HH:mm").format(new Date(range.end)));
                }
                if (times.length() == 0) continue;
                if (result.length() != 0) result.append('\n');
                result.append(possibleOn ? "Можливе ввімкнення  " : "Відключення  ").append(times);
            }
            return result.toString();
        }
    }

    private static final class Interval {
        final long start;
        final long end;
        final boolean possibleOn;

        Interval(long start, long end, boolean possibleOn) {
            this.start = start;
            this.end = end;
            this.possibleOn = possibleOn;
        }
    }
}
