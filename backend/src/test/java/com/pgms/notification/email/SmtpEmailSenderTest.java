package com.pgms.notification.email;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;

class SmtpEmailSenderTest {

    @Test
    void rejectedRecipientsAndMalformedMessagesArePermanent() throws Exception {
        SendFailedException rejected = new SendFailedException("550 5.1.1 user unknown", null, new InternetAddress[0],
                new InternetAddress[0], new InternetAddress[] {new InternetAddress("ghost@x.test")});
        MailSendException send = new MailSendException(Map.<Object, Exception>of(new Object(), rejected));
        assertThat(SmtpEmailSender.classify(send).isPermanent()).isTrue();
        assertThat(SmtpEmailSender.classify(new MailSendException("wrapped", rejected)).isPermanent()).isTrue();
        assertThat(SmtpEmailSender.classify(new MailParseException("bad header")).isPermanent()).isTrue();
    }

    @Test
    void connectionAndAuthenticationProblemsAreRetried() {
        assertThat(SmtpEmailSender.classify(new MailSendException("Connection refused", new MessagingException("io")))
                .isPermanent()).isFalse();
        assertThat(SmtpEmailSender.classify(new MailAuthenticationException("535 auth failed")).isPermanent()).isFalse();
        assertThat(SmtpEmailSender.classify(new MailSendException(Map.<Object, Exception>of(
                new Object(), new MessagingException("421 try later")))).isPermanent()).isFalse();
    }
}
