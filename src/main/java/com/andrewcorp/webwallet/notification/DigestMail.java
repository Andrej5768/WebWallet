package com.andrewcorp.webwallet.notification;

import static com.andrewcorp.webwallet.telegram.BotTexts.escape;

import com.andrewcorp.webwallet.telegram.BotTexts;
import java.util.ArrayList;
import java.util.List;

/**
 * The content of a digest email: a few sections of lines, built once into the HTML and the plain text versions. The
 * HTML is kept simple, with inline styles, as mail clients want it; the chart is an attached image.
 */
final class DigestMail {

    private static final String TEXT = "#18181B";
    private static final String MUTED = "#52525B";
    private static final String FAINT = "#71717A";
    private static final String BORDER = "#E4E4E7";
    private static final String ACCENT = "#0D52A8";
    static final String MORE = "#C62828";
    static final String LESS = "#15803D";

    /** A line of a section: a label with a value on the right, either may be empty. */
    record Line(String label, String value, String color) {

        static Line of(String label, String value) {
            return new Line(label, value, null);
        }

        static Line text(String text) {
            return new Line(text, null, null);
        }
    }

    private record Section(String title, List<Line> lines) {}

    final String subject;
    private final String title;
    private final List<String> intro = new ArrayList<>();
    private final List<Section> sections = new ArrayList<>();
    private String chartAlt;
    private String buttonLabel;
    private String buttonUrl;

    DigestMail(String subject, String title) {
        this.subject = subject;
        this.title = title;
    }

    DigestMail intro(String line) {
        intro.add(line);
        return this;
    }

    DigestMail chart(String alt) {
        this.chartAlt = alt;
        return this;
    }

    DigestMail section(String sectionTitle, List<Line> lines) {
        if (!lines.isEmpty()) {
            sections.add(new Section(sectionTitle, lines));
        }
        return this;
    }

    DigestMail button(String label, String url) {
        this.buttonLabel = label;
        this.buttonUrl = url;
        return this;
    }

    boolean hasChart() {
        return chartAlt != null;
    }

    /** @param chartCid the content id of the attached chart */
    String html(
            String footer,
            String unsubscribeLabel,
            String unsubscribeUrl,
            String settingsLabel,
            String settingsUrl,
            String chartCid) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\""
                + " content=\"width=device-width\"></head>");
        html.append(
                        "<body style=\"margin:0;padding:24px 12px;background:#F4F4F5;font-family:Arial,Helvetica,sans-serif;"
                                + "color:")
                .append(TEXT)
                .append(";font-size:15px;line-height:1.5\">");
        html.append("<div style=\"max-width:600px;margin:0 auto;background:#FFFFFF;border:1px solid ")
                .append(BORDER)
                .append(";border-radius:10px;overflow:hidden\">");
        html.append("<div style=\"padding:18px 28px;border-bottom:1px solid ")
                .append(BORDER)
                .append(";font-weight:700;font-size:16px\"><span style=\"display:inline-block;width:12px;height:12px;"
                        + "border-radius:3px;background:")
                .append(ACCENT)
                .append(";margin-right:8px\"></span>WebWallet</div>");
        html.append("<div style=\"padding:24px 28px 8px\">");
        html.append("<h1 style=\"margin:0 0 6px;font-size:22px\">")
                .append(escape(title))
                .append("</h1>");
        for (String line : intro) {
            html.append("<p style=\"margin:0 0 4px;color:")
                    .append(MUTED)
                    .append("\">")
                    .append(line)
                    .append("</p>");
        }
        if (chartAlt != null && chartCid != null) {
            html.append("<img src=\"cid:")
                    .append(chartCid)
                    .append("\" alt=\"")
                    .append(escape(chartAlt))
                    .append("\" width=\"544\" style=\"display:block;width:100%;max-width:544px;height:auto;"
                            + "margin:18px 0 6px;border:1px solid ")
                    .append(BORDER)
                    .append(";border-radius:8px\">");
        }
        for (Section section : sections) {
            html.append("<div style=\"border-top:1px solid ")
                    .append(BORDER)
                    .append(";margin-top:16px;padding-top:14px\"><h2 style=\"margin:0 0 8px;font-size:15px\">")
                    .append(escape(section.title()))
                    .append("</h2>");
            html.append("<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\">");
            for (Line line : section.lines()) {
                html.append("<tr><td style=\"padding:3px 0;color:")
                        .append(line.value() == null ? FAINT : TEXT)
                        .append(";font-size:")
                        .append(line.value() == null ? "13px" : "15px")
                        .append("\">")
                        .append(escape(line.label()))
                        .append("</td>");
                if (line.value() != null) {
                    html.append("<td align=\"right\" style=\"padding:3px 0;white-space:nowrap;font-weight:")
                            .append(line.color() != null ? "700" : "400")
                            .append(";color:")
                            .append(line.color() != null ? line.color() : TEXT)
                            .append("\">")
                            .append(escape(line.value()))
                            .append("</td>");
                }
                html.append("</tr>");
            }
            html.append("</table></div>");
        }
        if (buttonUrl != null) {
            html.append("<p style=\"margin:22px 0 6px\"><a href=\"")
                    .append(escape(buttonUrl))
                    .append("\" style=\"display:inline-block;background:")
                    .append(ACCENT)
                    .append(
                            ";color:#FFFFFF;text-decoration:none;font-weight:700;padding:11px 20px;border-radius:7px\">")
                    .append(escape(buttonLabel))
                    .append("</a></p>");
        }
        html.append("</div>");
        html.append("<div style=\"padding:18px 28px 24px;margin-top:18px;border-top:1px solid ")
                .append(BORDER)
                .append(";font-size:12px;color:")
                .append(FAINT)
                .append("\">")
                .append(escape(footer))
                .append("<br>")
                .append("<a href=\"")
                .append(escape(unsubscribeUrl))
                .append("\" style=\"color:")
                .append(ACCENT)
                .append("\">")
                .append(escape(unsubscribeLabel))
                .append("</a> · <a href=\"")
                .append(escape(settingsUrl))
                .append("\" style=\"color:")
                .append(ACCENT)
                .append("\">")
                .append(escape(settingsLabel))
                .append("</a></div>");
        html.append("</div></body></html>");
        return html.toString();
    }

    String text(String footer, String unsubscribeLabel, String unsubscribeUrl) {
        StringBuilder text = new StringBuilder(title).append("\n");
        intro.forEach(line -> text.append(plain(line)).append("\n"));
        for (Section section : sections) {
            text.append("\n").append(section.title()).append("\n");
            for (Line line : section.lines()) {
                text.append(line.label());
                if (line.value() != null) {
                    text.append(" — ").append(line.value());
                }
                text.append("\n");
            }
        }
        if (buttonUrl != null) {
            text.append("\n").append(buttonLabel).append(": ").append(buttonUrl).append("\n");
        }
        text.append("\n--\n")
                .append(footer)
                .append("\n")
                .append(unsubscribeLabel)
                .append(": ")
                .append(unsubscribeUrl);
        return text.toString();
    }

    /** The intro lines may have bold parts. */
    private static String plain(String html) {
        return html.replaceAll("<[^>]+>", "")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    /** E.g. {@code +1 900 ₴ · +48 %}; null share leaves the percent out. */
    static String change(
            java.math.BigDecimal change, java.math.BigDecimal share, String currency, java.util.Locale locale) {
        String text = (change.signum() > 0 ? "+" : "") + BotTexts.wholeMoney(change, currency, locale);
        if (share != null) {
            text += " · " + (share.signum() > 0 ? "+" : "−") + SpendingReports.percent(share) + " %";
        }
        return text;
    }
}
