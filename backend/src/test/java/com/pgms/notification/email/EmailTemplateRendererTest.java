package com.pgms.notification.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.pgms.config.AppProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.Mockito;

class EmailTemplateRendererTest {

    private final EmailTemplateRenderer renderer = rendererFromClasspathLayout("https://portal.pgms.test");

    @Test
    void escapesEveryUserSuppliedValue() {
        EmailTemplateRenderer.RenderedEmail email = renderer.render("Hi <b>\"you\"</b>\r\nBcc: x@evil.test",
                "Line <script>alert(1)</script> & more\n{{content}}", Map.of("pg", "Nest & <Co>"));

        assertThat(email.subject()).isEqualTo("Hi <b>\"you\"</b> Bcc: x@evil.test").doesNotContain("\n");
        assertThat(email.html())
                .contains("Hi &lt;b&gt;&quot;you&quot;&lt;/b&gt;")
                .contains("Line &lt;script&gt;alert(1)&lt;/script&gt; &amp; more<br>{{content}}")
                .contains("Nest &amp; &lt;Co&gt;")
                .doesNotContain("<script>")
                .doesNotContain("<Co>");
        // single-pass slot filling: "{{content}}" in the body stays literal (preview + paragraph), never expanded again
        assertThat(email.html().split("\\{\\{content}}", -1)).hasSize(3);
        assertThat(email.html().split("Open the portal", -1)).hasSize(2);
        assertThat(email.text()).contains("Line <script>alert(1)</script> & more").endsWith("Nest & <Co>\n");
    }

    @Test
    void showsTheOneTimeCodeAndPortalLink() {
        EmailTemplateRenderer.RenderedEmail email = renderer.render("Your password reset code", "Use the code below.",
                Map.of("code", "042917", "validity", "10 min"));
        assertThat(email.html()).contains(">042917</div>").contains("valid for 10 min")
                .contains("href=\"https://portal.pgms.test\"").contains("PG Manager").contains("<meta name=\"viewport\"");
        assertThat(email.text()).contains("Your code: 042917").contains("Valid for 10 min.").contains("Portal: https://portal.pgms.test");
    }

    @Test
    void nonHttpPortalUrlsAreNeverLinked() {
        EmailTemplateRenderer.RenderedEmail email = rendererFromClasspathLayout("javascript:alert(1)")
                .render("Hello", "Para one.\n\nPara two.", Map.of());
        assertThat(email.html()).doesNotContain("javascript:").doesNotContain("Open the portal");
        assertThat(email.html()).contains("Para one.</p>").contains("Para two.</p>");
        assertThat(email.text()).doesNotContain("Portal:");
    }

    private static EmailTemplateRenderer rendererFromClasspathLayout(String portalUrl) {
        AppProperties properties = Mockito.mock(AppProperties.class, Answers.RETURNS_DEEP_STUBS);
        Mockito.when(properties.notifications().email().brandName()).thenReturn("PG Manager");
        Mockito.when(properties.notifications().portalUrl()).thenReturn(portalUrl);
        return new EmailTemplateRenderer(properties);
    }
}
