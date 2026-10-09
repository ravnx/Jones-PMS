package util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Installs from before the mail server could be changed have no smtp keys at all, and
 * must keep sending through Gmail exactly as before.
 */
class SmtpSettingsTest {

	@Test
	void missingKeysMeanGmail() {
		assertEquals(SmtpSettings.GMAIL, SmtpSettings.parse(null, null, null));
	}

	@Test
	void readsStoredValues() {
		SmtpSettings s = SmtpSettings.parse(" smtp.rice.edu ", "465", "ssl");
		assertEquals("smtp.rice.edu", s.getHost());
		assertEquals(465, s.getPort());
		assertEquals(SmtpSettings.Security.SSL, s.getSecurity());
	}

	@Test
	void badValuesFallBackOneByOne() {
		SmtpSettings s = SmtpSettings.parse("mail.example.edu", "seventy", "QUANTUM");
		assertEquals("mail.example.edu", s.getHost());
		assertEquals(587, s.getPort());
		assertEquals(SmtpSettings.Security.STARTTLS, s.getSecurity());
	}

	@Test
	void portMustBeInRange() {
		assertNull(SmtpSettings.parsePort("0"));
		assertNull(SmtpSettings.parsePort("65536"));
		assertNull(SmtpSettings.parsePort(""));
		assertEquals(Integer.valueOf(25), SmtpSettings.parsePort(" 25 "));
	}
}
