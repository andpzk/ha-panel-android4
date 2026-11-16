package ua.hapanel;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;
import java.util.Calendar;

public final class NightMode {
    static final String MORNING_WAKE = "ua.hapanel.MORNING_WAKE";
    private static final int NIGHT_START_MINUTE = 23 * 60 + 58;
    private static final int WAKE_HOUR = 8;

    private NightMode() {}

    static boolean isNight(long now) {
        Calendar clock = Calendar.getInstance(Overview.KYIV);
        clock.setTimeInMillis(now);
        int minute = clock.get(Calendar.HOUR_OF_DAY) * 60 + clock.get(Calendar.MINUTE);
        return minute >= NIGHT_START_MINUTE || minute < WAKE_HOUR * 60;
    }

    static long nextMorning(long now) {
        return nextTime(now, WAKE_HOUR, 0);
    }

    static long nextNight(long now) {
        return nextTime(now, NIGHT_START_MINUTE / 60, NIGHT_START_MINUTE % 60);
    }

    private static long nextTime(long now, int hour, int minute) {
        Calendar morning = Calendar.getInstance(Overview.KYIV);
        morning.setTimeInMillis(now);
        morning.set(Calendar.HOUR_OF_DAY, hour);
        morning.set(Calendar.MINUTE, minute);
        morning.set(Calendar.SECOND, 0);
        morning.set(Calendar.MILLISECOND, 0);
        if (morning.getTimeInMillis() <= now) morning.add(Calendar.DAY_OF_MONTH, 1);
        return morning.getTimeInMillis();
    }

    static ComponentName admin(Context context) {
        return new ComponentName(context, Admin.class);
    }

    private static DevicePolicyManager policies(Context context) {
        return (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    static boolean enabled(Context context) {
        return policies(context).isAdminActive(admin(context));
    }

    static void disable(Context context) {
        alarms(context).cancel(wakeIntent(context));
        policies(context).removeActiveAdmin(admin(context));
    }

    static void scheduleMorning(Context context) {
        alarms(context).set(AlarmManager.RTC_WAKEUP, nextMorning(System.currentTimeMillis()),
            wakeIntent(context));
    }

    static void sleepUntil(Context context, long wakeAt) {
        if (!enabled(context)) return;
        PendingIntent wake = wakeIntent(context);
        alarms(context).set(AlarmManager.RTC_WAKEUP, wakeAt, wake);
        try {
            policies(context).lockNow();
            Log.i("PanelNight", "Screen locked; wake scheduled for " + wakeAt);
        } catch (SecurityException error) {
            scheduleMorning(context);
            Log.w("PanelNight", "Screen lock permission unavailable", error);
        }
    }

    private static AlarmManager alarms(Context context) {
        return (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    }

    private static PendingIntent wakeIntent(Context context) {
        return PendingIntent.getBroadcast(context, 0, new Intent(context, Wake.class), 0);
    }

    public static final class Admin extends DeviceAdminReceiver {}

    public static final class Wake extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (!enabled(context)) return;
            scheduleMorning(context);
            if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "ua.hapanel:morning").acquire(10000);
            context.startActivity(new Intent(context, MainActivity.class)
                .setAction(MORNING_WAKE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            Log.i("PanelNight", "Wake alarm opened panel");
        }
    }
}
