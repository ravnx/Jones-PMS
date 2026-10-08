package model.email;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;

/**
 * Gmail's daily limit has to be recognised so a reminder run stops at the first refusal,
 * instead of trying - and failing - every remaining student.
 */
class EmailerSendingLimitTest {

	private static final String GMAIL_LIMIT = "550-5.4.5 Daily user sending limit exceeded. "
			+ "For more information on Gmail\n550-5.4.5 sending limits go to\n"
			+ "550 5.4.5  https://support.google.com/a/answer/166852 - gsmtp";

	@Test
	void recognisesGmailsDailyLimit() {
		assertTrue(Emailer.isSendingLimit(new SendFailedException(GMAIL_LIMIT)));
	}

	@Test
	void recognisesTheLimitWhenItIsTheNestedCause() {
		MessagingException outer = new MessagingException("Could not send",
				new SendFailedException(GMAIL_LIMIT));
		assertTrue(Emailer.isSendingLimit(outer));
	}

	@Test
	void aBadAddressIsNotTheLimit() {
		assertFalse(Emailer.isSendingLimit(new SendFailedException(
				"550 5.1.1 The email account that you tried to reach does not exist")));
	}
}
