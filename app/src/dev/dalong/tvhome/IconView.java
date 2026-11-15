package dev.dalong.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

final class IconView extends View {
    enum Glyph {
        PERSON, INPUT, APPS, SETTINGS, WIFI, BLUETOOTH, ETHERNET, OFFLINE,
        HDMI, TV, AV, DTMB, USB, SMART_HOME, WEATHER,
        RECENTS, MANAGE_APPS, DESKTOP_SETTINGS
    }

    private final Glyph glyph;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    IconView(Context context, Glyph glyph, int tint) {
        super(context);
        this.glyph = glyph;
        paint.setColor(tint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.7f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setTint(int tint) {
        paint.setColor(tint);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.scale(getWidth() / 24f, getHeight() / 24f);
        path.reset();
        switch (glyph) {
            case PERSON:
                canvas.drawCircle(12, 8, 3.4f, paint);
                path.moveTo(4.5f, 20);
                path.cubicTo(4.5f, 13, 19.5f, 13, 19.5f, 20);
                canvas.drawPath(path, paint);
                break;
            case INPUT:
                canvas.drawRoundRect(3, 4, 21, 17, 2, 2, paint);
                canvas.drawLine(9, 21, 15, 21, paint);
                path.moveTo(7.5f, 11);
                path.lineTo(15.5f, 11);
                path.moveTo(13, 8.5f);
                path.lineTo(15.5f, 11);
                path.lineTo(13, 13.5f);
                canvas.drawPath(path, paint);
                break;
            case APPS:
                for (int y = 4; y <= 13; y += 9) {
                    for (int x = 4; x <= 13; x += 9) {
                        canvas.drawRoundRect(x, y, x + 6, y + 6, 1, 1, paint);
                    }
                }
                break;
            case SETTINGS:
                canvas.drawLine(4, 6, 20, 6, paint);
                canvas.drawLine(4, 12, 20, 12, paint);
                canvas.drawLine(4, 18, 20, 18, paint);
                canvas.drawLine(9, 3.6f, 9, 8.4f, paint);
                canvas.drawLine(15, 9.6f, 15, 14.4f, paint);
                canvas.drawLine(11, 15.6f, 11, 20.4f, paint);
                break;
            case WIFI:
                path.moveTo(3, 8.5f);
                path.quadTo(12, 1.8f, 21, 8.5f);
                path.moveTo(6, 12.2f);
                path.quadTo(12, 7.5f, 18, 12.2f);
                path.moveTo(9, 16);
                path.quadTo(12, 13.2f, 15, 16);
                canvas.drawPath(path, paint);
                canvas.drawCircle(12, 19.5f, 0.65f, paint);
                break;
            case BLUETOOTH:
                path.moveTo(7, 7);
                path.lineTo(17, 17);
                path.lineTo(12, 21);
                path.lineTo(12, 3);
                path.lineTo(17, 7);
                path.lineTo(7, 17);
                canvas.drawPath(path, paint);
                break;
            case ETHERNET:
                path.moveTo(4, 4);
                path.lineTo(20, 4);
                path.lineTo(20, 14);
                path.lineTo(16, 14);
                path.lineTo(16, 19);
                path.lineTo(8, 19);
                path.lineTo(8, 14);
                path.lineTo(4, 14);
                path.close();
                canvas.drawPath(path, paint);
                for (int x = 8; x <= 16; x += 4) {
                    canvas.drawLine(x, 4.5f, x, 8.5f, paint);
                }
                break;
            case OFFLINE:
                canvas.drawCircle(12, 12, 8, paint);
                canvas.drawLine(6.5f, 17.5f, 17.5f, 6.5f, paint);
                break;
            case HDMI:
                path.moveTo(3, 7);
                path.lineTo(21, 7);
                path.lineTo(21, 12);
                path.lineTo(17, 17);
                path.lineTo(7, 17);
                path.lineTo(3, 12);
                path.close();
                canvas.drawPath(path, paint);
                for (int x = 7; x <= 17; x += 2) {
                    canvas.drawLine(x, 10.5f, x, 12.5f, paint);
                }
                break;
            case TV:
                canvas.drawRoundRect(3, 8, 21, 19, 2, 2, paint);
                canvas.drawLine(8, 3, 12, 8, paint);
                canvas.drawLine(16, 3, 12, 8, paint);
                canvas.drawLine(7, 19, 6, 21, paint);
                canvas.drawLine(17, 19, 18, 21, paint);
                break;
            case AV:
                for (int x = 5; x <= 19; x += 7) {
                    canvas.drawCircle(x, 12, 3.1f, paint);
                    canvas.drawPoint(x, 12, paint);
                }
                break;
            case DTMB:
                canvas.drawCircle(12, 9, 1.4f, paint);
                canvas.drawLine(12, 10.5f, 12, 20, paint);
                canvas.drawLine(7.5f, 20, 16.5f, 20, paint);
                canvas.drawArc(7, 4, 17, 14, 135, 90, false, paint);
                canvas.drawArc(7, 4, 17, 14, -45, 90, false, paint);
                canvas.drawArc(3, 0, 21, 18, 140, 80, false, paint);
                canvas.drawArc(3, 0, 21, 18, -40, 80, false, paint);
                break;
            case USB:
                canvas.drawLine(12, 19, 12, 4, paint);
                path.moveTo(9.5f, 6.5f);
                path.lineTo(12, 4);
                path.lineTo(14.5f, 6.5f);
                path.moveTo(12, 16);
                path.lineTo(6.5f, 12);
                path.moveTo(12, 14);
                path.lineTo(17.5f, 11);
                canvas.drawPath(path, paint);
                canvas.drawCircle(6.5f, 10.5f, 1.4f, paint);
                canvas.drawRect(16, 8, 19, 11, paint);
                canvas.drawCircle(12, 20, 1.4f, paint);
                break;
            case RECENTS:
                canvas.drawRoundRect(3, 8, 16.5f, 20, 2, 2, paint);
                path.moveTo(8, 4);
                path.lineTo(19, 4);
                path.quadTo(21, 4, 21, 6);
                path.lineTo(21, 15);
                canvas.drawPath(path, paint);
                canvas.drawLine(6.5f, 12, 13, 12, paint);
                canvas.drawLine(6.5f, 16, 10.5f, 16, paint);
                break;
            case MANAGE_APPS:
                canvas.drawRoundRect(3, 4, 8, 9, 1, 1, paint);
                canvas.drawRoundRect(3, 15, 8, 20, 1, 1, paint);
                canvas.drawLine(11.5f, 6.5f, 21, 6.5f, paint);
                canvas.drawLine(11.5f, 17.5f, 21, 17.5f, paint);
                canvas.drawLine(15, 4.5f, 15, 8.5f, paint);
                canvas.drawLine(17.5f, 15.5f, 17.5f, 19.5f, paint);
                break;
            case DESKTOP_SETTINGS:
                canvas.drawRoundRect(2.5f, 4, 21.5f, 20, 2, 2, paint);
                canvas.drawLine(9, 4, 9, 20, paint);
                canvas.drawLine(5.4f, 8, 6.2f, 8, paint);
                canvas.drawLine(5.4f, 12, 6.2f, 12, paint);
                canvas.drawLine(5.4f, 16, 6.2f, 16, paint);
                canvas.drawLine(12.5f, 9, 18.5f, 9, paint);
                canvas.drawLine(12.5f, 15, 18.5f, 15, paint);
                canvas.drawLine(14.5f, 7.5f, 14.5f, 10.5f, paint);
                canvas.drawLine(16.5f, 13.5f, 16.5f, 16.5f, paint);
                break;
            case SMART_HOME:
                path.moveTo(3, 11);
                path.lineTo(12, 4);
                path.lineTo(21, 11);
                path.moveTo(5, 10);
                path.lineTo(5, 20);
                path.lineTo(19, 20);
                path.lineTo(19, 10);
                path.moveTo(9, 13);
                path.quadTo(12, 10.5f, 15, 13);
                canvas.drawPath(path, paint);
                canvas.drawPoint(12, 16, paint);
                break;
            case WEATHER:
                canvas.drawArc(3, 3, 15, 15, 98, 218, false, paint);
                path.moveTo(8.5f, 20);
                path.cubicTo(4, 20, 4, 14, 8.5f, 14);
                path.cubicTo(9, 8, 17, 8, 17.5f, 14);
                path.cubicTo(22, 14, 22, 20, 18, 20);
                path.close();
                canvas.drawPath(path, paint);
                break;
        }
        canvas.restore();
    }
}
