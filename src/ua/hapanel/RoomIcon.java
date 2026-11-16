package ua.hapanel;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

final class RoomIcon extends Drawable {
    static final int[][] HOME = {{2,11,12,3,22,11}, {5,10,5,21,10,21,10,15,14,15,14,21,19,21,19,10}};
    static final int[][] SOFA = {{4,11,4,7,6,5,18,5,20,7,20,11}, {4,19,4,21}, {20,19,20,21},
        {3,12,6,12,6,15,18,15,18,12,21,12,22,13,22,18,21,19,3,19,2,18,2,13,3,12}};
    static final int[][] BED = {{3,20,3,7,6,7,6,13}, {3,17,21,17,21,20}, {3,13,21,13,21,17},
        {8,13,8,10,10,10,10,13}, {12,13,12,9,18,9,21,11,21,13}};
    static final int[][] DOOR = {{4,21,20,21}, {6,21,6,4,18,4,18,21}, {6,4,14,7,14,19,6,21}, {11,13,12,13}};
    static final int[][] KITCHEN = {{3,10,21,10}, {7,7,17,7}, {10,7,10,5,14,5,14,7},
        {5,10,5,18,7,20,17,20,19,18,19,10}, {2,13,5,13}, {19,13,22,13}};
    static final int[][] BATH = {{3,13,21,13}, {4,13,5,17,7,19,17,19,19,17,20,13},
        {7,19,6,21}, {17,19,18,21}, {5,13,5,6,7,4,10,4,12,6}, {10,8,14,8}};
    static final int[][] MIRROR = {{7,3,17,3,19,5,19,19,17,21,7,21,5,19,5,5,7,3},
        {8,11,13,6}, {11,14,16,9}};
    static final int[][] MOON = {{13,3,8,4,5,7,3,12,4,17,7,20,12,21,17,19,20,16,21,12,
        18,14,14,13,11,10,11,6,13,3}};
    static final int[][] TOILET = {{4,12,4,5,10,5,10,12}, {3,12,21,12,21,14,18,17,16,18,16,21,9,21,9,18,6,16,5,12}, {7,8,8,8}};
    static final int[][] WINDOW = {{3,4,21,4,21,20,3,20,3,4}, {12,4,12,20}, {3,12,21,12}};
    static final int[][] LIGHT_STRIP = {{4,9,20,9,21,10,21,14,20,15,4,15,3,14,3,10,4,9},
        {7,12,7,12}, {12,12,12,12}, {17,12,17,12}, {5,6,4,4}, {12,5,12,3}, {19,6,20,4}};
    static final int[][] BULB_OFF = {{9,17,9,15,6,12,5,9,6,6,9,4,12,3,15,4,18,6,19,9,18,12,15,15,15,17,9,17},
        {9,20,15,20}, {3,21,21,3}};
    static final int[][] FAN = {{12,10,14,11,14,13,12,14,10,13,10,11,12,10},
        {10,11,6,9,5,6,7,4,10,5,12,8,12,10},
        {14,11,17,8,20,9,21,12,19,14,16,14,14,13},
        {12,14,13,18,11,21,8,20,7,18,8,15,10,13}};
    static final int[][] CHARGE = {{4,5,19,5,19,19,4,19,4,5}, {21,9,21,15}, {13,7,9,12,14,12,10,17}};
    static final int[][] POWER = {{14,2,5,13,11,13,10,22,19,11,13,11,14,2}};
    static final int[][] CLOUD = {{5,18,2,15,2,11,5,9,8,9,11,5,15,5,18,9,21,10,22,13,21,16,18,18,5,18}};
    static final int[][] SUN = {{8,8,12,6,16,8,18,12,16,16,12,18,8,16,6,12,8,8},
        {12,1,12,3}, {12,21,12,23}, {1,12,3,12}, {21,12,23,12},
        {4,4,6,6}, {18,18,20,20}, {4,20,6,18}, {18,6,20,4}};
    static final int[][] RAIN = {{5,15,2,12,2,9,5,7,8,7,11,3,15,3,18,7,21,8,22,11,20,15,5,15},
        {8,18,6,21}, {13,18,11,21}, {18,18,16,21}};
    static final int[][] REFRESH = {{20,10,18,5,14,3,9,4,5,8,4,12}, {20,5,20,10,15,10},
        {4,14,6,19,10,21,15,20,19,16,20,13}, {4,19,4,14,9,14}};
    static final int[][] EYE_OFF = {{2,12,6,8,10,6,14,6,18,8,22,12,18,16,14,18,10,18,6,16,2,12},
        {9,12,10,10,12,9,14,10,15,12,14,14,12,15,10,14,9,12}, {3,21,21,3}};
    static final int[][] SETTINGS = {{10,3,14,3,15,6,18,7,21,7,22,11,20,13,20,16,18,20,
        15,19,13,22,9,21,8,18,5,17,3,17,2,13,4,11,4,8,6,5,9,6,10,3},
        {12,8,15,9,16,12,15,15,12,16,9,15,8,12,9,9,12,8}};

    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[][] lines;

    RoomIcon(int[][] lines, int size, int color) {
        this.lines = lines;
        setBounds(0, 0, size, size);
        ink.setColor(color);
        ink.setStrokeWidth(1.7f);
        ink.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        canvas.save();
        canvas.translate(bounds.left, bounds.top);
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f);
        for (int[] line : lines) {
            for (int i = 2; i < line.length; i += 2) {
                canvas.drawLine(line[i - 2], line[i - 1], line[i], line[i + 1], ink);
            }
        }
        canvas.restore();
    }

    @Override public void setAlpha(int alpha) { ink.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { ink.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
