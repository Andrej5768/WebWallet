package com.andrewcorp.webwallet.notification;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * PNG charts of the spending reports. They are always light, so that they read the same in every theme of a
 * messenger: the current period in the accent color, the previous one in grey.
 */
public final class SpendingCharts {

    private static final int WIDTH = 1000;
    private static final int PADDING = 48;
    private static final Color BACKGROUND = Color.WHITE;
    private static final Color TEXT = new Color(0x18181B);
    private static final Color MUTED = new Color(0x71717A);
    private static final Color GRID = new Color(0xE4E4E7);
    private static final Color CURRENT = new Color(0x0D52A8);
    private static final Color PREVIOUS = new Color(0xA1A1AA);
    private static final Color MORE = new Color(0xC62828);
    private static final Color LESS = new Color(0x15803D);
    private static final String FONT = "DejaVu Sans";

    private SpendingCharts() {}

    /** A point of two cumulative lines; a value is null where its period has no such day. */
    public record Day(int day, BigDecimal current, BigDecimal previous) {}

    /** A category with the spending of both periods. */
    public record Bar(String name, BigDecimal current, BigDecimal previous) {}

    /** The spending from the 1st, day by day: the current month against the same days of the previous one. */
    public static byte[] cumulative(
            String title, String currentLabel, String previousLabel, List<Day> days, Locale locale) {
        int height = 560;
        BufferedImage image = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = start(image);
        int top = header(g, title, currentLabel, previousLabel) + 24;
        // A gutter at the left for the amounts of the grid
        int left = PADDING + 90;
        int right = WIDTH - PADDING;
        int bottom = height - PADDING - 20;
        double max = days.stream()
                .flatMap(day -> java.util.stream.Stream.of(day.current(), day.previous()))
                .filter(value -> value != null)
                .mapToDouble(BigDecimal::doubleValue)
                .max()
                .orElse(0);
        // Three or four grid lines a round amount apart, the highest just above the largest value
        double step = max <= 0 ? 1 : round(max / 3);
        int lines = Math.max(1, (int) Math.ceil(max * 1.02 / step));
        double scale = step * lines;
        g.setColor(GRID);
        g.setStroke(new BasicStroke(1));
        g.setFont(new Font(FONT, Font.PLAIN, 18));
        NumberFormat numbers = NumberFormat.getIntegerInstance(locale);
        for (int i = 0; i <= lines; i++) {
            int y = bottom - (int) ((bottom - top) * i / (double) lines);
            g.setColor(GRID);
            g.drawLine(left, y, right, y);
            String amount = numbers.format(Math.round(step * i));
            g.setColor(MUTED);
            g.drawString(amount, left - 16 - g.getFontMetrics().stringWidth(amount), y + 6);
        }
        int count = Math.max(days.size(), 2);
        Path2D previous = line(days, false, left, right, top, bottom, count, scale);
        Path2D current = line(days, true, left, right, top, bottom, count, scale);
        g.setColor(PREVIOUS);
        g.setStroke(new BasicStroke(4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[] {12, 9}, 0));
        g.draw(previous);
        g.setColor(CURRENT);
        g.setStroke(new BasicStroke(5, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(current);

        g.setFont(new Font(FONT, Font.PLAIN, 20));
        g.setColor(MUTED);
        FontMetrics metrics = g.getFontMetrics();
        int labelStep = Math.max(1, days.size() / 5);
        for (int i = 0; i < days.size(); i += labelStep) {
            String label = String.valueOf(days.get(i).day());
            int x = x(i, left, right, count) - metrics.stringWidth(label) / 2;
            g.drawString(label, x, bottom + 32);
        }
        return png(image, g);
    }

    /** Spending by category: a pair of bars for each, with the change on the right. */
    public static byte[] categories(
            String title, String currentLabel, String previousLabel, List<Bar> bars, Locale locale) {
        int rowHeight = 64;
        int height = 150 + bars.size() * rowHeight + PADDING;
        BufferedImage image = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = start(image);
        int y = header(g, title, currentLabel, previousLabel) + 10;
        double max = bars.stream()
                .flatMap(bar -> java.util.stream.Stream.of(bar.current(), bar.previous()))
                .mapToDouble(BigDecimal::doubleValue)
                .max()
                .orElse(0);
        int nameWidth = 210;
        int changeWidth = 150;
        int barLeft = PADDING + nameWidth;
        int barWidth = WIDTH - PADDING - changeWidth - barLeft;
        NumberFormat numbers = NumberFormat.getIntegerInstance(locale);
        Font name = new Font(FONT, Font.PLAIN, 22);
        Font change = new Font(FONT, Font.BOLD, 22);
        for (Bar bar : bars) {
            g.setFont(name);
            g.setColor(TEXT);
            g.drawString(fit(g, bar.name(), nameWidth - 16), PADDING, y + 30);
            g.setColor(CURRENT);
            g.fillRoundRect(barLeft, y + 8, width(bar.current(), max, barWidth), 16, 6, 6);
            g.setColor(PREVIOUS);
            g.fillRoundRect(barLeft, y + 30, width(bar.previous(), max, barWidth), 16, 6, 6);
            BigDecimal difference = bar.current().subtract(bar.previous());
            String text = difference.signum() == 0
                    ? "0"
                    : (difference.signum() > 0 ? "+" : "−")
                            + numbers.format(difference.abs().longValue());
            g.setFont(change);
            g.setColor(difference.signum() > 0 ? MORE : difference.signum() < 0 ? LESS : MUTED);
            FontMetrics metrics = g.getFontMetrics();
            g.drawString(text, WIDTH - PADDING - metrics.stringWidth(text), y + 34);
            y += rowHeight;
        }
        return png(image, g);
    }

    private static Graphics2D start(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        return g;
    }

    /** The title and the legend; returns where the chart starts. */
    private static int header(Graphics2D g, String title, String currentLabel, String previousLabel) {
        g.setColor(TEXT);
        g.setFont(new Font(FONT, Font.BOLD, 30));
        g.drawString(title, PADDING, PADDING + 24);
        g.setFont(new Font(FONT, Font.PLAIN, 20));
        int x = PADDING;
        int y = PADDING + 66;
        for (int i = 0; i < 2; i++) {
            g.setColor(i == 0 ? CURRENT : PREVIOUS);
            g.fillRoundRect(x, y - 9, 28, 6, 3, 3);
            g.setColor(MUTED);
            String label = i == 0 ? currentLabel : previousLabel;
            g.drawString(label, x + 38, y);
            x += 38 + g.getFontMetrics().stringWidth(label) + 32;
        }
        return y + 30;
    }

    private static Path2D line(
            List<Day> days, boolean current, int left, int right, int top, int bottom, int count, double scale) {
        Path2D path = new Path2D.Double();
        boolean started = false;
        for (int i = 0; i < days.size(); i++) {
            BigDecimal value = current ? days.get(i).current() : days.get(i).previous();
            if (value == null) {
                continue;
            }
            double y = bottom - (bottom - top) * value.doubleValue() / scale;
            if (started) {
                path.lineTo(x(i, left, right, count), y);
            } else {
                path.moveTo(x(i, left, right, count), y);
                started = true;
            }
        }
        return path;
    }

    /** The next round number: 1, 2, 2.5 or 5 times a power of ten, so that the grid has round amounts. */
    private static double round(double value) {
        double magnitude = Math.pow(10, Math.floor(Math.log10(value)));
        for (double factor : new double[] {1, 2, 2.5, 5}) {
            if (value <= factor * magnitude) {
                return factor * magnitude;
            }
        }
        return 10 * magnitude;
    }

    private static int x(int index, int left, int right, int count) {
        return left + (right - left) * index / (count - 1);
    }

    private static int width(BigDecimal value, double max, int barWidth) {
        return max <= 0 ? 0 : Math.max(2, (int) Math.round(barWidth * value.doubleValue() / max));
    }

    private static String fit(Graphics2D g, String text, int width) {
        FontMetrics metrics = g.getFontMetrics();
        if (metrics.stringWidth(text) <= width) {
            return text;
        }
        String shortened = text;
        while (!shortened.isEmpty() && metrics.stringWidth(shortened + "…") > width) {
            shortened = shortened.substring(0, shortened.length() - 1);
        }
        return shortened + "…";
    }

    private static byte[] png(BufferedImage image, Graphics2D g) {
        g.dispose();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
