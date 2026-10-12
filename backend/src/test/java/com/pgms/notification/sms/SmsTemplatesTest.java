package com.pgms.notification.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pgms.config.AppProperties.SmsTemplate;
import com.pgms.notification.NotificationType;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SmsTemplatesTest {

    private final SmsTemplates templates = new SmsTemplates(Map.of(
            "PAYMENT_RECEIVED", new SmsTemplate("1107000000000000009", "{pg}: Received Rs.{amt} for {inv}. Balance Rs.{bal}."),
            "booking-confirmed", new SmsTemplate(" ", "{pg}: Bed reserved, ref {ref}."),
            "MOVE_OUT", new SmsTemplate("1", "  ")), 30);

    @Test
    void rendersFixedTextWithVariablesInOrder() {
        SmsTemplates.RenderedSms sms = templates.render(NotificationType.PAYMENT_RECEIVED,
                Map.of("pg", "Green Nest", "amt", "9000", "inv", "INV-1", "bal", "0", "unused", "x")).orElseThrow();
        assertThat(sms.text()).isEqualTo("Green Nest: Received Rs.9000 for INV-1. Balance Rs.0.");
        assertThat(sms.dltTemplateId()).isEqualTo("1107000000000000009");
        assertThat(sms.variables()).containsExactly(Map.entry("pg", "Green Nest"), Map.entry("amt", "9000"),
                Map.entry("inv", "INV-1"), Map.entry("bal", "0"));
    }

    @Test
    void variablesAreFlattenedAndCappedSoTheyCannotReshapeTheMessage() {
        SmsTemplates.RenderedSms sms = templates.render(NotificationType.BOOKING_CONFIRMED,
                Map.of("pg", "A very long PG name that keeps going and going", "ref", "R1\nVisit http://evil.test {pg}"))
                .orElseThrow();
        assertThat(sms.variables().get("pg")).hasSize(30);
        assertThat(sms.variables().get("ref")).isEqualTo("R1 Visit http://evil.test {pg}");
        assertThat(sms.text()).doesNotContain("\n").endsWith("ref R1 Visit http://evil.test {pg}.");
        assertThat(sms.dltTemplateId()).isNull(); // blank id
    }

    @Test
    void missingTemplateOrVariableRendersNothing() {
        assertThat(templates.render(NotificationType.PAYMENT_RECEIVED, Map.of("pg", "x"))).isEmpty();
        assertThat(templates.render(NotificationType.ROOM_TRANSFER, Map.of())).isEmpty();
        assertThat(templates.hasTemplate(NotificationType.MOVE_OUT)).isFalse(); // blank text = no template
        assertThat(templates.hasTemplate(NotificationType.BOOKING_CONFIRMED)).isTrue();
    }

    @Test
    void unknownTypeKeysFailFastAtStartup() {
        assertThatThrownBy(() -> new SmsTemplates(Map.of("PAYMENT_RECIEVED", new SmsTemplate("1", "x")), 30))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PAYMENT_RECIEVED");
        assertThat(new SmsTemplates(null, 30).hasTemplate(NotificationType.MOVE_IN)).isFalse();
    }
}
