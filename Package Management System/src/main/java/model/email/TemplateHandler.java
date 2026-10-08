package model.email;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import util.FileIO;
import model.IModelToViewAdaptor;

public class TemplateHandler {

	private static final Logger logger = Logger.getLogger(TemplateHandler.class.getName());

	static IModelToViewAdaptor viewAdaptor;
	static String headers =
			"NOTIFICATION-SUBJECT|NOTIFICATION-BODY|REMINDER-SUBJECT|REMINDER-BODY|SENDER-ALIAS|AUTO-LINEBREAK";

	// A variable name must start with a letter, so a price such as "$5" or a lone "$" is
	// plain text rather than an empty variable name.
	static Pattern varResolutionPat = Pattern.compile("(?<!\\\\)\\$([A-Z][A-Z\\-]*)");

	/** The headers every email needs; a template without one of them cannot be sent. */
	public static final String[] REQUIRED_HEADERS =
		{"NOTIFICATION-SUBJECT", "NOTIFICATION-BODY", "REMINDER-SUBJECT", "REMINDER-BODY"};

	// The unknown variables the user was last warned about. The warning is only shown
	// again when this changes, so a reminder run does not raise one dialog per student.
	private static Set<String> lastWarnedUnresolved = new TreeSet<String>();

	public static void setViewAdaptor (IModelToViewAdaptor _viewAdaptor) {
		viewAdaptor = _viewAdaptor;
		lastWarnedUnresolved = new TreeSet<String>();
	}

	/**
	 * Returns the required headers that are missing from the resolved templates, or an
	 * empty list when the templates can be used to send email.
	 */
	public static List<String> missingHeaders(Map<String,String> templates) {
		List<String> missing = new ArrayList<String>();
		for (String header : REQUIRED_HEADERS) {
			if (templates.get(header) == null) {
				missing.add(header);
			}
		}
		return missing;
	}
	
	public static HashMap<String,String> getTemplates(boolean convert, boolean comments) {
		HashMap<String,String> result = new HashMap<String,String>();

		// Get raw file string
		String fileStr = getRawFile();

		// getRawFile returns null when the template file cannot be read. It has already
		// told the user, so return empty templates rather than throwing from here.
		if (fileStr == null) {
			return result;
		}

		// Remove all C-style block comments
		if (!comments) {
			fileStr = fileStr.replaceAll("(?s)/\\*.*?\\*/", "");
		}
		
		// Create matcher for the text surrounded by headers
		Matcher m = Pattern.compile("("+headers+"):"+ "(.*?)" + "("+headers+"|\\z)", Pattern.DOTALL).matcher(fileStr);
		
		int start = 0;
		while (m.find(start)) {
			result.put(m.group(1), m.group(2).trim());	
			
			// Remember to start at the beginning of the last-captured group, in order to capture that section
			start = m.start(3);
		}
		
		// TODO - Ensure that templates where found for all headers.  Print Error otherwise.
		
		// Transform newlines into HTML <br> tags.
		//
		// Match CR, LF or CRLF rather than System.lineSeparator(): the template file may
		// have been written on a different machine to the one reading it, and matching
		// the running platform's separator silently converts nothing when they differ -
		// which arrives as one run-on paragraph in the student's inbox.
		//
		// AUTO-LINEBREAK defaults to on when the header is missing from the file.
		if (convert && !"FALSE".equalsIgnoreCase(result.getOrDefault("AUTO-LINEBREAK", "TRUE"))) {
			for (String key : result.keySet()) {
				result.put(key, result.get(key).replaceAll("\\r\\n|\\r|\\n", "<br>"));
			}
		}
		
//			for (String key : result.keySet()) {
//				System.out.format("%s:\n%s\n", key, result.get(key));
//			}
			
		return result;
	}

	public static String getRawFile() {
		String RootDir = FileIO.getRootDir();
		String filePath = RootDir + "/email-template.txt";

		// A new installation has no template yet. Write out the default that ships inside
		// the jar, rather than failing every email until someone copies a file in by hand.
		File templateFile = new File(filePath);
		if (!templateFile.exists()) {
			installDefaultTemplate(templateFile);
		}

		String result = null;

		// Read entire file into string
		try {
			result = FileIO.loadFileAsString(filePath);
		} catch (IOException e) {
			logger.severe("Could not read email template: " + filePath + " - " + e.getMessage());
			if (viewAdaptor != null) {
				viewAdaptor.displayError("Could not read the email template file.\n\n"
						+ "Expected it at:\n " + filePath
						+ "\n\nEmails cannot be sent until this file can be read.",
						"Could not read templates");
			}
		}

		return result;
	}

	/*
	 * Copies the default template bundled in the jar to the program directory. Failure is
	 * logged rather than thrown - getRawFile reports the unreadable template to the user.
	 */
	private static void installDefaultTemplate(File templateFile) {
		try (InputStream defaults =
				TemplateHandler.class.getResourceAsStream("/email-template.txt")) {

			if (defaults == null) {
				logger.severe("The default email template is missing from the application jar.");
				return;
			}

			File parent = templateFile.getParentFile();
			if (parent != null) {
				parent.mkdirs();
			}
			Files.copy(defaults, templateFile.toPath());
			logger.info("Created a default email template at " + templateFile.getPath());

		} catch (IOException e) {
			logger.warning("Could not create the default email template: " + e.getMessage());
		}
	}
	
	public static void writeRawFile(String newTemplate) {
		String RootDir = FileIO.getRootDir();
		String filePath = RootDir + "/email-template.txt";
		
		OutputStream os;
		try {
			os = new FileOutputStream(filePath, false);
			os.write(newTemplate.getBytes());
			os.close();
		} catch (FileNotFoundException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public static void writeNewTemplates(Map<String, String> newTemplate) {
		String RootDir = FileIO.getRootDir();
		String filePath = RootDir + "/email-template.txt";

		try {
			BufferedWriter wr = new BufferedWriter(new FileWriter(filePath, false));
			
			for (String header : newTemplate.keySet()) {
				wr.write(header+":");
				wr.newLine();
				wr.write(newTemplate.get(header));
				wr.newLine();
				wr.newLine();
			}
			
			wr.close();
		} catch (FileNotFoundException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public static Map<String, String> getResolvedTemplates(Map<String, String> variables) {
		Map<String,String> rawTemplates = getTemplates(true, false);
		Map<String,String> resolvedTemplates = new HashMap<String,String>();
		variables.put("ALIAS", rawTemplates.getOrDefault("SENDER-ALIAS", ""));

		// Variable names in the template that we have no value for. They are left in the
		// text as written and reported once, rather than substituted with null - a single
		// typo such as $FIRSTNAME used to throw and stop the email being sent at all.
		Set<String> unresolved = new TreeSet<String>();

		for (String header : rawTemplates.keySet()) {
			String resolved = rawTemplates.get(header);

			if (header.matches("(NOTIFICATION|REMINDER)-(SUBJECT|BODY)")) {
				// A fresh matcher each time: a cached one stays bound to the text it was
				// created from, which goes stale as soon as the template file is edited.
				Matcher matcher = varResolutionPat.matcher(resolved);

				while(matcher.find()) {
					String name = matcher.group(1);
					String value = variables.get(name);

					if (value == null) {
						unresolved.add(name);
						continue;
					}
					resolved = resolved.replace("$" + name, value);
				}
			}

			resolvedTemplates.put(header, resolved);
		}

		if (!unresolved.isEmpty()) {
			logger.warning("Unknown variables in email template: $" + String.join(", $", unresolved));
		}
		if (!unresolved.isEmpty() && viewAdaptor != null && !unresolved.equals(lastWarnedUnresolved)) {
			lastWarnedUnresolved = unresolved;
			String names = "$" + String.join(", $", unresolved);
			viewAdaptor.displayWarning("The email template uses variables that do not exist:\n  "
					+ names + "\n\nThey were left as-is in the message. Check the spelling in\n"
					+ "Admin -> Email and Printer -> Change Email Template.",
					"Unknown Template Variables");
		}

		return resolvedTemplates;
	}
}
