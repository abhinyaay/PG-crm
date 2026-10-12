package com.pgms.notification.email;

import com.pgms.config.AppProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Renders a notification into a branded HTML email plus a plain-text alternative.
 *
 * <p>Deliberately tiny instead of a template engine: the layout ({@code templates/email/layout.html}) has a fixed set of
 * {@code {{slot}}} placeholders, every user-influenced value (names, titles, bodies, codes) is HTML-escaped before it is
 * placed, and substitution is a single pass, so text that happens to contain {@code {{...}}} is never expanded.
 */
@Component
public class EmailTemplateRenderer {

    private static final Pattern SLOT = Pattern.compile("\\{\\{([a-z]+)}}");
    private static final Pattern LINE_BREAKS = Pattern.compile("[\\r\\n]+");
    private static final String PARAGRAPH = "<p style=\"margin:0 0 14px 0;font-size:15px;line-height:22px;color:#374151;\">";

    private final String layout;
    private final String defaultBrand;
    private final String portalUrl;

    @Autowired
    public EmailTemplateRenderer(AppProperties properties) {
        this(loadLayout(), properties.notifications().email().brandName(), properties.notifications().portalUrl());
    }

    public EmailTemplateRenderer(String layout, String defaultBrand, String portalUrl) {
        this.layout = layout;
        this.defaultBrand = defaultBrand;
        this.portalUrl = safeUrl(portalUrl);
    }

    public record RenderedEmail(String subject, String text, String html) {
    }

    /**
     * {@code params} may carry {@code pg} (sender brand, usually the organization name), {@code code} (a one-time code,
     * shown prominently) and {@code validity} (how long the code is valid).
     */
    public RenderedEmail render(String title, String body, Map<String, String> params) {
        String brand = params.getOrDefault("pg", defaultBrand);
        String subject = LINE_BREAKS.matcher(title).replaceAll(" ").trim();
        String code = params.get("code");
        String validity = params.get("validity");

        StringBuilder content = new StringBuilder();
        for (String paragraph : body.split("\\R\\s*\\R")) {
            if (!paragraph.isBlank()) {
                content.append(PARAGRAPH).append(escape(paragraph.trim()).replaceAll("\\R", "<br>")).append("</p>");
            }
        }
        if (code != null) {
            content.append("<div style=\"margin:8px 0 18px 0;padding:14px 0;text-align:center;background-color:#f0fdfa;"
                            + "border:1px solid #99f6e4;border-radius:8px;font-size:30px;line-height:36px;font-weight:700;"
                            + "letter-spacing:8px;color:#0f766e;font-family:'SFMono-Regular',Menlo,Consolas,monospace;\">")
                    .append(escape(code)).append("</div>");
            content.append(PARAGRAPH).append("Never share this code with anyone, including PG staff.")
                    .append(validity == null ? "" : " It is valid for " + escape(validity) + ".").append("</p>");
        }
        if (portalUrl != null) {
            content.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:8px 0 18px 0;\">"
                            + "<tr><td style=\"border-radius:8px;background-color:#0f766e;\"><a href=\"")
                    .append(escape(portalUrl))
                    .append("\" style=\"display:inline-block;padding:11px 20px;font-size:15px;font-weight:600;color:#ffffff;"
                            + "text-decoration:none;\">Open the portal</a></td></tr></table>");
        }
        String footer = "You are receiving this email from " + escape(brand) + " about your account. "
                + "Choose which notifications you get under Settings &rarr; Notifications in the portal.";

        String html = fill(Map.of(
                "title", escape(subject),
                "preheader", escape(preview(body)),
                "brand", escape(brand),
                "content", content.toString(),
                "footer", footer));

        StringBuilder text = new StringBuilder(subject).append("\n\n").append(body.trim()).append("\n");
        if (code != null) {
            text.append("\nYour code: ").append(code).append("\n")
                    .append(validity == null ? "" : "Valid for " + validity + ". ").append("Never share this code.\n");
        }
        if (portalUrl != null) {
            text.append("\nPortal: ").append(portalUrl).append("\n");
        }
        text.append("\n-- \n").append(brand).append("\n");
        return new RenderedEmail(subject, text.toString(), html);
    }

    private String fill(Map<String, String> slots) {
        Matcher matcher = SLOT.matcher(layout);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(slots.getOrDefault(matcher.group(1), "")));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    static String escape(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value, StandardCharsets.UTF_8.name());
    }

    private static String preview(String body) {
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() > 120 ? flat.substring(0, 120) : flat;
    }

    /** Only absolute http(s) URLs end up in an href. */
    private static String safeUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String lower = url.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://") ? url.trim() : null;
    }

    private static String loadLayout() {
        try (InputStream in = new ClassPathResource("templates/email/layout.html").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Email layout template missing", e);
        }
    }
}
