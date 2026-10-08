package model.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import model.IModelToViewAdaptor;
import util.FileIO;

/**
 * Tests for the email template reader.
 *
 * Every one of these used to end in an exception that stopped the email being sent: a
 * missing template file on a new installation, a template header the file did not
 * define, or a single mistyped variable name.
 */
class TemplateHandlerTest {

	@TempDir
	Path tempDir;

	private RecordingViewAdaptor view;

	@BeforeEach
	void setUp() {
		FileIO.init(tempDir.toString());
		view = new RecordingViewAdaptor();
		TemplateHandler.setViewAdaptor(view);
	}

	private void writeTemplate(String contents) throws IOException {
		Files.writeString(tempDir.resolve("email-template.txt"), contents, StandardCharsets.UTF_8);
	}

	private Map<String,String> variables() {
		Map<String,String> variables = new HashMap<String,String>();
		variables.put("FNAME", "Navin");
		variables.put("LNAME", "Pathak");
		variables.put("NETID", "np8");
		variables.put("PKGID", "20260816120000");
		variables.put("PKGTIME", "12:00");
		variables.put("COMMENT", "");
		variables.put("NUMPKGS", "1");
		return variables;
	}

	// ---- a brand new installation -------------------------------------------

	@Test
	void writesTheBundledTemplateWhenNoneExists() {
		Path template = tempDir.resolve("email-template.txt");
		assertFalse(Files.exists(template), "precondition: no template yet");

		String raw = TemplateHandler.getRawFile();

		assertTrue(Files.exists(template), "a default template should have been created");
		assertNotNull(raw, "the freshly created template should be readable");
		assertTrue(raw.contains("NOTIFICATION-BODY"), raw);
		assertTrue(view.errors.isEmpty(), "a new installation is not an error: " + view.errors);
	}

	@Test
	void aNewInstallationCanResolveTemplatesImmediately() {
		Map<String,String> resolved = TemplateHandler.getResolvedTemplates(variables());

		assertNotNull(resolved.get("NOTIFICATION-BODY"));
		assertNotNull(resolved.get("NOTIFICATION-SUBJECT"));
		assertTrue(resolved.get("NOTIFICATION-BODY").contains("Navin"),
				resolved.get("NOTIFICATION-BODY"));
	}

	// ---- variables ----------------------------------------------------------

	@Test
	void substitutesKnownVariables() throws Exception {
		writeTemplate("AUTO-LINEBREAK:\nTRUE\n\nSENDER-ALIAS:\nJones Mail Room\n\n"
				+ "NOTIFICATION-SUBJECT:\nPackage for $FNAME\n\n"
				+ "NOTIFICATION-BODY:\nHello $FNAME $LNAME ($NETID)\n\n"
				+ "REMINDER-SUBJECT:\nReminder\n\nREMINDER-BODY:\nYou have $NUMPKGS\n");

		Map<String,String> resolved = TemplateHandler.getResolvedTemplates(variables());

		assertEquals("Package for Navin", resolved.get("NOTIFICATION-SUBJECT"));
		assertTrue(resolved.get("NOTIFICATION-BODY").contains("Hello Navin Pathak (np8)"),
				resolved.get("NOTIFICATION-BODY"));
	}

	@Test
	void anUnknownVariableIsLeftAloneAndReportedInsteadOfThrowing() throws Exception {
		// $FIRSTNAME is a plausible typo for $FNAME. This used to throw a
		// NullPointerException and stop the email being sent at all.
		writeTemplate("AUTO-LINEBREAK:\nFALSE\n\nSENDER-ALIAS:\nJones Mail Room\n\n"
				+ "NOTIFICATION-SUBJECT:\nPackage\n\n"
				+ "NOTIFICATION-BODY:\nHello $FIRSTNAME, you are $FNAME\n\n"
				+ "REMINDER-SUBJECT:\nReminder\n\nREMINDER-BODY:\nHi\n");

		Map<String,String> resolved = TemplateHandler.getResolvedTemplates(variables());

		String body = resolved.get("NOTIFICATION-BODY");
		assertTrue(body.contains("$FIRSTNAME"), "unknown variable should be left as written: " + body);
		assertTrue(body.contains("you are Navin"), "known variables should still resolve: " + body);

		assertEquals(1, view.warnings.size(), "the user should be told once: " + view.warnings);
		assertTrue(view.warnings.get(0).contains("$FIRSTNAME"), view.warnings.get(0));
	}

	// ---- line breaks --------------------------------------------------------

	@Test
	void convertsLineBreaksWrittenOnAnyPlatform() throws Exception {
		// A template saved on Windows, read here. Matching System.lineSeparator() would
		// convert nothing and the email would arrive as one run-on paragraph.
		writeTemplate("AUTO-LINEBREAK:\r\nTRUE\r\n\r\nSENDER-ALIAS:\r\nJones\r\n\r\n"
				+ "NOTIFICATION-SUBJECT:\r\nPackage\r\n\r\n"
				+ "NOTIFICATION-BODY:\r\nLine one\r\nLine two\r\n\r\n"
				+ "REMINDER-SUBJECT:\r\nReminder\r\n\r\nREMINDER-BODY:\r\nHi\r\n");

		Map<String,String> resolved = TemplateHandler.getResolvedTemplates(variables());

		String body = resolved.get("NOTIFICATION-BODY");
		assertTrue(body.contains("Line one<br>Line two"), body);
		assertFalse(body.contains("\r"), "no stray carriage returns should survive: " + body);
	}

	@Test
	void honoursAutoLinebreakFalse() throws Exception {
		writeTemplate("AUTO-LINEBREAK:\nFALSE\n\nSENDER-ALIAS:\nJones\n\n"
				+ "NOTIFICATION-SUBJECT:\nPackage\n\n"
				+ "NOTIFICATION-BODY:\nLine one\nLine two\n\n"
				+ "REMINDER-SUBJECT:\nReminder\n\nREMINDER-BODY:\nHi\n");

		Map<String,String> resolved = TemplateHandler.getResolvedTemplates(variables());

		assertFalse(resolved.get("NOTIFICATION-BODY").contains("<br>"),
				resolved.get("NOTIFICATION-BODY"));
	}

	@Test
	void defaultsToConvertingWhenTheAutoLinebreakHeaderIsMissing() throws Exception {
		// The header is optional in practice - its absence used to be a NullPointerException
		writeTemplate("SENDER-ALIAS:\nJones\n\n"
				+ "NOTIFICATION-SUBJECT:\nPackage\n\n"
				+ "NOTIFICATION-BODY:\nLine one\nLine two\n\n"
				+ "REMINDER-SUBJECT:\nReminder\n\nREMINDER-BODY:\nHi\n");

		Map<String,String> resolved = TemplateHandler.getResolvedTemplates(variables());

		assertTrue(resolved.get("NOTIFICATION-BODY").contains("Line one<br>Line two"),
				resolved.get("NOTIFICATION-BODY"));
	}

	/**
	 * Captures what the model tried to tell the user.
	 */
	private static class RecordingViewAdaptor implements IModelToViewAdaptor {
		final List<String> errors = new ArrayList<String>();
		final List<String> warnings = new ArrayList<String>();

		public void displayMessage(String message, String title) { }
		public void displayError(String error, String title) { errors.add(error); }
		public void displayWarning(String warning, String title) { warnings.add(warning); }
		public String getChoiceFromList(String m, String t, String[] c) { return null; }
		public String getPrinterNames(String[] printerNames) { return null; }
		public String[] changeEmail(String a, String p, String n) { return null; }
		public boolean getBooleanInput(String m, String t, String[] o) { return false; }
	}
}
