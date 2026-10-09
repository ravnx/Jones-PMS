package model.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Properties;

import org.junit.jupiter.api.Test;

import util.SmtpSettings;

/**
 * The mail session has to match the configured server, and switching servers must not
 * leave settings from the previous one behind.
 */
class EmailerSessionPropertiesTest {

	@Test
	void gmailUsesStartTls() {
		Properties p = Emailer.sessionProperties(SmtpSettings.GMAIL);
		assertEquals("smtp.gmail.com", p.get("mail.smtp.host"));
		assertEquals("587", p.get("mail.smtp.port"));
		assertEquals("true", p.get("mail.smtp.starttls.enable"));
		assertNull(p.get("mail.smtp.ssl.enable"));
	}

	@Test
	void sslServerUsesImplicitTls() {
		Properties p = Emailer.sessionProperties(
				new SmtpSettings("smtp.rice.edu", 465, SmtpSettings.Security.SSL));
		assertEquals("smtp.rice.edu", p.get("mail.smtp.host"));
		assertEquals("465", p.get("mail.smtp.port"));
		assertEquals("true", p.get("mail.smtp.ssl.enable"));
		assertNull(p.get("mail.smtp.starttls.enable"));
	}

	@Test
	void switchingServersStartsClean() {
		Emailer.sessionProperties(new SmtpSettings("a.example.edu", 465, SmtpSettings.Security.SSL));
		Properties p = Emailer.sessionProperties(
				new SmtpSettings("b.example.edu", 25, SmtpSettings.Security.NONE));
		assertNull(p.get("mail.smtp.ssl.enable"));
		assertNull(p.get("mail.smtp.starttls.enable"));
		assertNull(p.get("mail.smtp.ssl.trust"));
	}
}
