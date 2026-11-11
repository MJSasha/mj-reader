package com.mjreader;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Shared 24-unit outline icons. Labels carry accessibility semantics. */
final class ReaderIcon extends Drawable {
    private final String kind;
    private final int size;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    ReaderIcon(Context context, String kind, int color) {
        this.kind = kind;
        size = Math.round(22 * context.getResources().getDisplayMetrics().density);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.7f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    static ReaderIcon forLabel(Context context, String label, int color) {
        String kind = switch (label) {
            case "Настройки", "Settings", "Настройки RSVP", "RSVP settings", "Профили и настройки", "Profiles & settings" -> "settings";
            case "Все книги", "All books", "Библиотека", "Library" -> "library";
            case "Вид и порядок", "View & sort", "Сортировка", "Sort order", "Отображение", "Display" -> "sort";
            case "Читаю", "Чтение", "Reading", "Текст", "Text", "Статус чтения", "Reading status" -> "book";
            case "Хочу прочитать", "Want to read", "В планах", "To read" -> "clock";
            case "Прочитано", "Finished", "Сохранить", "Save", "Сохранить заметку", "Save note" -> "check";
            case "Избранное", "Favorites", "Favorite" -> "star";
            case "Коллекции", "Collections", "Новая коллекция", "+ Новая коллекция", "+ New collection", "Новая", "New" -> "folder";
            case "Удалённые", "Deleted", "Удалить", "Delete" -> "trash";
            case "Название и автор", "Title & author" -> "edit";
            case "Повторный импорт", "Reimport" -> "history";
            case "Сбросить прогресс", "Reset progress", "С начала", "Read again", "Вернуть", "Restore", "Отменить удаление", "Undo delete", "Вернуться к месту", "Return to previous place" -> "history";
            case "Оформление", "Appearance", "Цвет и атмосфера", "Color & atmosphere" -> "palette";
            case "Типографика", "Typography", "Отображение слова", "Word display" -> "type";
            case "Страница", "Page layout" -> "page";
            case "Экран" -> "sun";
            case "Темп и паузы", "Pace & pauses", "RSVP", "Профили скорости", "Speed profiles" -> "speed";
            case "Оглавление", "Contents" -> "list";
            case "Поиск в книге", "Search book" -> "search";
            case "Закладки и цитаты", "Bookmarks & quotes", "Сохранить цитату", "Save quote", "Добавить закладку", "Add bookmark", "Закладки и заметки", "Bookmarks & notes" -> "bookmark";
            case "Навигация", "Navigation", "Перейти к проценту", "Go to percentage" -> "location";
            case "Полный экран", "Full screen" -> "expand";
            case "Добавить книгу", "Add book" -> "plus";
            case "Найти книги", "Discover" -> "globe";
            case "Скачать", "Download" -> "download";
            case "Название книги", "Book title" -> "book";
            case "Автор", "Author" -> "edit";
            case "Автоматически", "Automatic", "flibusta.site", "flibusta.is" -> "globe";
            case "Свой HTTPS-адрес", "Custom HTTPS address" -> "edit";
            case "Старт", "Start" -> "play";
            case "Пауза", "Pause" -> "pause";
            case "Отмена", "Cancel", "Закрыть", "Close" -> "close";
            default -> null;
        };
        return kind == null ? null : new ReaderIcon(context, kind, color);
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        canvas.save();
        canvas.translate(bounds.left, bounds.top);
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f);
        switch (kind) {
            case "download" -> { path(canvas, 12, 3, 12, 16, 7, 11); line(canvas, 12, 16, 17, 11); path(canvas, 3, 16, 3, 21, 21, 21, 21, 16); }
            case "back" -> { path(canvas, 13, 5, 6, 12, 13, 19); line(canvas, 6, 12, 21, 12); }
            case "globe" -> { canvas.drawCircle(12, 12, 9, paint); canvas.drawOval(8, 3, 16, 21, paint); line(canvas, 3, 12, 21, 12); }
            case "settings" -> {
                canvas.drawCircle(12, 12, 7, paint); canvas.drawCircle(12, 12, 2.5f, paint);
                for (int i = 0; i < 8; i++) { canvas.save(); canvas.rotate(i * 45, 12, 12); line(canvas, 12, 2, 12, 5); canvas.restore(); }
            }
            case "library" -> { box(canvas, 3, 4, 7, 20); box(canvas, 9, 4, 13, 20); path(canvas, 16, 5, 19, 4, 22, 19, 19, 20, 16, 5); }
            case "filter" -> { path(canvas, 3, 5, 21, 5, 14, 13, 14, 20, 10, 18, 10, 13, 3, 5); }
            case "sort" -> { line(canvas, 4, 6, 20, 6); line(canvas, 4, 12, 15, 12); line(canvas, 4, 18, 10, 18); }
            case "book" -> { path(canvas, 12, 6, 9, 4, 3, 4, 3, 19, 9, 19, 12, 21, 15, 19, 21, 19, 21, 4, 15, 4, 12, 6, 12, 21); }
            case "clock" -> { canvas.drawCircle(12, 12, 9, paint); path(canvas, 12, 6, 12, 12, 16, 14); }
            case "check" -> path(canvas, 4, 12, 9, 17, 20, 6);
            case "star" -> path(canvas, 12, 3, 15, 9, 22, 10, 17, 15, 18, 22, 12, 18, 6, 22, 7, 15, 2, 10, 9, 9, 12, 3);
            case "folder" -> { path(canvas, 3, 20, 3, 5, 9, 5, 12, 8, 21, 8, 21, 20, 3, 20); }
            case "trash" -> { path(canvas, 6, 7, 7, 21, 17, 21, 18, 7); line(canvas, 3, 7, 21, 7); path(canvas, 8, 7, 8, 3, 16, 3, 16, 7); line(canvas, 10, 11, 10, 17); line(canvas, 14, 11, 14, 17); }
            case "edit" -> { path(canvas, 4, 15, 16, 3, 21, 8, 9, 20, 3, 21, 4, 15, 9, 20); line(canvas, 13, 6, 18, 11); }
            case "history" -> { canvas.drawArc(4, 4, 21, 21, 210, 300, false, paint); path(canvas, 3, 4, 3, 10, 9, 10); path(canvas, 12, 7, 12, 13, 16, 15); }
            case "palette" -> { canvas.drawCircle(12, 12, 9, paint); canvas.drawCircle(8, 8, 1, paint); canvas.drawCircle(15, 7, 1, paint); canvas.drawCircle(17, 13, 1, paint); canvas.drawCircle(10, 16, 2, paint); }
            case "type" -> { path(canvas, 3, 19, 9, 4, 15, 19); line(canvas, 5, 14, 13, 14); line(canvas, 16, 10, 22, 10); line(canvas, 19, 10, 19, 19); }
            case "page" -> { box(canvas, 4, 2, 20, 22); line(canvas, 8, 7, 16, 7); line(canvas, 8, 12, 16, 12); line(canvas, 8, 17, 13, 17); }
            case "sun" -> { canvas.drawCircle(12, 12, 4, paint); for (int i = 0; i < 8; i++) { canvas.save(); canvas.rotate(i * 45, 12, 12); line(canvas, 12, 2, 12, 4); canvas.restore(); } }
            case "speed" -> { canvas.drawArc(3, 4, 21, 22, 150, 240, false, paint); line(canvas, 12, 13, 17, 7); canvas.drawCircle(12, 13, 1.5f, paint); }
            case "list" -> { for (int y : new int[]{6, 12, 18}) { line(canvas, 8, y, 21, y); line(canvas, 3, y, 4, y); } }
            case "search" -> { canvas.drawCircle(10, 10, 7, paint); line(canvas, 15, 15, 21, 21); }
            case "bookmark" -> path(canvas, 5, 3, 19, 3, 19, 22, 12, 17, 5, 22, 5, 3);
            case "location" -> { canvas.drawCircle(12, 12, 7, paint); canvas.drawCircle(12, 12, 2, paint); line(canvas, 12, 1, 12, 5); line(canvas, 12, 19, 12, 23); line(canvas, 1, 12, 5, 12); line(canvas, 19, 12, 23, 12); }
            case "expand" -> { path(canvas, 3, 9, 3, 3, 9, 3); path(canvas, 15, 3, 21, 3, 21, 9); path(canvas, 3, 15, 3, 21, 9, 21); path(canvas, 15, 21, 21, 21, 21, 15); }
            case "plus" -> { line(canvas, 4, 12, 20, 12); line(canvas, 12, 4, 12, 20); }
            case "play" -> path(canvas, 7, 3, 21, 12, 7, 21, 7, 3);
            case "pause" -> { line(canvas, 8, 4, 8, 20); line(canvas, 16, 4, 16, 20); }
            case "close" -> { line(canvas, 5, 5, 19, 19); line(canvas, 5, 19, 19, 5); }
        }
        canvas.restore();
    }
    private void line(Canvas canvas, float x1, float y1, float x2, float y2) { canvas.drawLine(x1, y1, x2, y2, paint); }
    private void box(Canvas canvas, float left, float top, float right, float bottom) { canvas.drawRoundRect(left, top, right, bottom, 1.5f, 1.5f, paint); }
    private void path(Canvas canvas, float... points) {
        Path path = new Path(); path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
        canvas.drawPath(path, paint);
    }
    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
