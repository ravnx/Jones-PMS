package model.email;

import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Properties;
import java.util.Date;
import java.util.logging.Logger;

import jakarta.mail.Authenticator;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.NoSuchProviderException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import model.IModelToViewAdaptor;
import util.Package;
import util.Pair;
import util.Person;
import util.PropertyHandler;

/*
 * Class that handles sending notification and reminder emails to students through
 * SMTP to the Gmail mail server.
 */

//TODO Timeout
//TODO If notification sending fails, add to a list of emails to send

public class Emailer {
	
	private PropertyHandler propHandler;
	private String senderAddress;
	private String senderPassword;
	private String senderAlias;
	
	private String host;
	private Session session;
	private Transport transport;
	
	//private HashMap<String,String> templates;
	
	private Logger logger;
	private IModelToViewAdaptor viewAdaptor;

	// whether the user has been told about Gmail's sending limit this session
	private boolean sendingLimitReported = false;
	
	public Emailer(IModelToViewAdaptor viewAdaptor) {
		// get PropertyHandler and logger instance
		this.propHandler = PropertyHandler.getInstance();
		this.logger = Logger.getLogger(Emailer.class.getName());
		this.logger.setLevel(java.util.logging.Level.ALL);
		// No extra ConsoleHandler here: the root logger already has one, and adding a
		// second printed every Emailer message to the console twice.

		this.viewAdaptor = viewAdaptor;
		logger.info("Emailer initialized.");
		// Give view adaptor to the email template reader
		TemplateHandler.setViewAdaptor(viewAdaptor);
        this.host = "smtp.gmail.com";
	}
	
	public void start(ArrayList<Pair<Person, Package>> activeEntriesSortedByPerson) {
		// get properties from PropertyHandler
		this.senderAddress = propHandler.getProperty("email.email_address");
		this.senderPassword = propHandler.getProperty("email.password");
		this.senderAlias = propHandler.getProperty("email.alias");

		// warn the user if the email properties were not loaded
		if(!isConfigured()) {
			logger.warning("Failed to load email properties.");
			viewAdaptor.displayMessage("Email information was not loaded from file.\n"
					+ "Please change email information in the next window.",
					"Email Not Loaded");

			// If the user cancels, start with email switched off rather than reopening
			// the dialog forever - on a fresh install there was previously no way past it.
			if(!changeEmail() || !isConfigured()) {
				logger.warning("Email setup was cancelled. Email is disabled for this session.");
				viewAdaptor.displayMessage("Emails will not be sent until email information "
						+ "is entered.\nYou can enter it from Admin -> Email and Printer.",
						"Email Disabled");
				return;
			}
		}

		// attempt to connect to the mail server and alert user if it fails

		attemptConnection();

		if(checkReminder()) {
			int pending = pendingReminders(activeEntriesSortedByPerson).size();
			if (pending == 0) {
				// nobody has a package waiting, so today's reminders are already done
				propHandler.setProperty("email.last_reminder", Long.valueOf(new Date().getTime()).toString());
			} else if (viewAdaptor.getBooleanInput(pending + " reminder email(s) are ready to send to students "
					+ "with packages waiting.\n\nSend them now?", "Send Reminders",
					new String[] {"Send Now", "Not Now"})) {
				sendAllReminders(activeEntriesSortedByPerson);
			} else {
				// asked again the next time the program starts
				logger.info("Reminder emails postponed by the user (" + pending + " pending).");
			}
		}
	}

	/**
	 * True when an address, password and alias are all available to send with.
	 */
	public boolean isConfigured() {
		return senderAddress != null && senderPassword != null && senderAlias != null;
	}
	
	/**
	 * Function changes email, password, and alias to passed values
	 * @param newAlias			New Alias for sender
	 * @param newAddress		New Email address
	 * @param newPassword		New Password to email address
	 */
	public void setEmailProperties(String newAlias, String newAddress, String newPassword) {

		propHandler.setProperty("email.email_address",newAddress);
		propHandler.setProperty("email.password",newPassword);
		propHandler.setProperty("email.alias",newAlias);
		
		this.senderAddress = newAddress;
		this.senderPassword = newPassword;
		this.senderAlias = newAlias;
		
		attemptConnection();
		
	}
	
	/**
	 * Attempts to connect to the Gmail server with stored credentials
	 * Sends error messages to the view if an authentication error or a 
	 * general messaging error occurs.
	 */
	public void attemptConnection() {
		boolean retry = true;
		logger.info("Attempting to connect to the mail server.");
		while(retry) {
			try {
				connect();
				closeConnection();
				retry = false;
			} catch (AuthenticationFailedException e){
					// Note: never pass the message to a format method - a '%' in an SMTP
					// error would then throw from inside this handler.
					logger.warning("Authentication failed: " + e.getMessage());

					viewAdaptor.displayMessage("Incorrect username or password.\n","");
					
					
					if (!changeEmail()) {
						retry = false;
						viewAdaptor.displayMessage("Emails will not be sent until a connection is established.",
								"");
					}
					
					
			} catch (MessagingException e) {
				logger.warning("Failed to connect to the mail server. " + e.getMessage());
				System.err.println("MessagingException: " + e);
				String[] options = {"Retry", "Cancel"};
				retry = viewAdaptor.getBooleanInput("Program failed to connect to the Gmail server.\n"
						+ "Please check your internet connection and try again.", 
						"Failed Connection",options);
				if(!retry) {
					viewAdaptor.displayMessage("Emails will not be sent until a connection is established.",
							"");
				}
			}
		}
	}

	/**
	 * Function that sends all reminder emails.
	 *
	 * Every message is built before connecting, so a template problem is reported once
	 * and nothing waits on a dialog while the connection is open. Each student who is
	 * sent a reminder is recorded straight away, so a run that stops part way - most
	 * often at Gmail's daily sending limit - carries on from where it stopped next time,
	 * instead of emailing everyone again from the start.
	 *
	 * @param allEntriesSortedByPerson	All active entries - MUST be sorted by person
	 * @return							Success of sending all reminders
	 */
	public boolean sendAllReminders(ArrayList<Pair<Person,Package>> allEntriesSortedByPerson) {

		if(!isConfigured()) {
			logger.warning("Reminder emails skipped: no email account is configured.");
			return false;
		}

		ArrayList<Pair<Person,ArrayList<Package>>> remindList = pendingReminders(allEntriesSortedByPerson);
		if (remindList.isEmpty()) {
			propHandler.setProperty("email.last_reminder", Long.valueOf(new Date().getTime()).toString());
			return true;
		}
		if (!templatesUsable()) {
			return false;
		}

		// build every message first
		List<String[]> messages = new ArrayList<String[]>();
		for (Pair<Person,ArrayList<Package>> ppPair : remindList) {
			messages.add(buildReminder(ppPair.first, ppPair.second));
		}

		Set<String> reminded = remindedToday();
		int sent = 0;
		int badAddresses = 0;
		try {
			connect();
			for (int i = 0; i < remindList.size(); i++) {
				Person person = remindList.get(i).first;
				String[] message = messages.get(i);
				try {
					sendEmail(person.getEmailAddress(), person.getFullName(), message[0], message[1]);
				} catch (SendFailedException e) {
					if (isSendingLimit(e)) {
						throw e;
					}
					// a bad address only affects this student - carry on with the rest
					logger.warning("Reminder not sent to " + person.getPersonID() + ": " + e.getMessage());
					badAddresses++;
					continue;
				}
				sent++;
				reminded.add(person.getPersonID());
				saveRemindedToday(reminded);
			}
		} catch(MessagingException e) {
			logger.warning("Reminder emails stopped after " + sent + " of " + remindList.size()
					+ ": " + e.getMessage());
			viewAdaptor.displayWarning((isSendingLimit(e)
					? "Gmail's daily sending limit has been reached, so reminder emails stopped.\n\n"
					: "Reminder emails stopped because of a mail server error:\n " + e.getMessage() + "\n\n")
					+ sent + " of " + remindList.size() + " reminders were sent. The rest will be offered\n"
					+ "the next time the program starts.", "Reminders Not Finished");
			return false;
		} catch (UnsupportedEncodingException e) {
			logger.severe(e.getMessage());
			return false;
		} finally {
			closeConnectionQuietly();
		}

		logger.info("Sent " + sent + " reminder emails" + (badAddresses > 0
				? "; " + badAddresses + " could not be delivered to their address." : "."));

		// add property with the current time as the last sent date
		propHandler.setProperty("email.last_reminder", Long.valueOf(new Date().getTime()).toString());
		return true;
	}

	/*
	 * Students with packages waiting who have not already been sent a reminder today.
	 */
	private ArrayList<Pair<Person,ArrayList<Package>>> pendingReminders(
			ArrayList<Pair<Person,Package>> allEntriesSortedByPerson) {
		Set<String> reminded = remindedToday();
		ArrayList<Pair<Person,ArrayList<Package>>> pending = new ArrayList<Pair<Person,ArrayList<Package>>>();
		for (Pair<Person,ArrayList<Package>> ppPair : collectPairs(allEntriesSortedByPerson)) {
			if (!reminded.contains(ppPair.first.getPersonID())) {
				pending.add(ppPair);
			}
		}
		return pending;
	}

	/*
	 * IDs of the students sent a reminder today, stored as "yyyy-MM-dd:id1,id2". A value
	 * from an earlier day is ignored, so the list starts empty each morning.
	 */
	private Set<String> remindedToday() {
		Set<String> result = new LinkedHashSet<String>();
		String prefix = today() + ":";
		String value = propHandler.getProperty("email.reminded_today");
		if (value != null && value.startsWith(prefix)) {
			for (String id : value.substring(prefix.length()).split(",")) {
				if (!id.isEmpty()) {
					result.add(id);
				}
			}
		}
		return result;
	}

	private void saveRemindedToday(Set<String> reminded) {
		propHandler.setProperty("email.reminded_today", today() + ":" + String.join(",", reminded));
	}

	private static String today() {
		return new SimpleDateFormat("yyyy-MM-dd").format(new Date());
	}

	/*
	 * True for Gmail's "550 5.4.5 Daily user sending limit exceeded" response. Nothing
	 * more can be sent until the limit resets, so there is no point trying the rest.
	 */
	static boolean isSendingLimit(MessagingException e) {
		for (Exception cause = e; cause != null;
				cause = cause instanceof MessagingException ? ((MessagingException) cause).getNextException() : null) {
			String message = cause.getMessage();
			if (message != null && (message.contains("5.4.5") || message.toLowerCase().contains("sending limit"))) {
				return true;
			}
		}
		return false;
	}

	/*
	 * Checks that the template file has every section an email needs. getRawFile has
	 * already told the user when the file could not be read at all.
	 */
	private boolean templatesUsable() {
		Map<String,String> templates = TemplateHandler.getTemplates(true, false);
		if (templates.isEmpty()) {
			logger.warning("Emails not sent: the email template could not be read.");
			return false;
		}
		List<String> missing = TemplateHandler.missingHeaders(templates);
		if (!missing.isEmpty()) {
			logger.warning("Emails not sent: the email template is missing " + missing);
			viewAdaptor.displayError("The email template is missing these sections:\n  "
					+ String.join(", ", missing) + "\n\nNo emails were sent. Fix the template in\n"
					+ "Admin -> Email and Printer -> Change Email Template.", "Email Template Incomplete");
			return false;
		}
		return true;
	}

	/*
	 * Sends a notification email to the recipient informing them that they 
	 * have a new package 
	 */
	public boolean sendPackageNotification(Person recipient, Package pkg) {

		if(!isConfigured()) {
			logger.warning("Notification skipped: no email account is configured.");
			return false;
		}

		// Find variable values
		Map<String,String> variables = new HashMap<String,String>();
		variables.put("COMMENT", pkg.getComment());
		variables.put("PKGTIME", pkg.getCheckInDate().toString());
		variables.put("PKGID",   String.valueOf(pkg.getPackageID()));  
		variables.put("FNAME",   recipient.getFirstName());  
		variables.put("LNAME",   recipient.getLastName());  
		variables.put("NETID",   recipient.getPersonID());  
		variables.put("NUMPKGS", "--");
		//variables.put("ALIAS",   "");  

		if (!templatesUsable()) {
			return false;
		}

		// Load email templates from template file
		Map<String,String> templates = TemplateHandler.getResolvedTemplates(variables);

		String body = templates.get("NOTIFICATION-BODY");
		String subject = templates.get("NOTIFICATION-SUBJECT");
		try {
			connect();
			sendEmail(recipient.getEmailAddress(), recipient.getFullName(), subject, body);
		} catch (UnsupportedEncodingException e) {
			logger.severe("UnsupportedEncodingException for Person (ID: " + recipient.getPersonID() +
					") and Package (ID: " + pkg.getPackageID() + ")");
			return false;
		} catch (MessagingException e) {
			logger.warning(e.getMessage());
			if (isSendingLimit(e) && !sendingLimitReported) {
				// once per session - the check-in result already says this email was not sent
				sendingLimitReported = true;
				viewAdaptor.displayWarning("Gmail's daily sending limit has been reached.\n\n"
						+ "Notification emails cannot be sent until it resets, which can take up to\n"
						+ "24 hours. Packages are still checked in and labels still print.",
						"Gmail Sending Limit");
			}
			return false;
		} finally {
			closeConnectionQuietly();
		}
		return true;
	}
	
	public String getSenderAddress() {
		return senderAddress;
	}

	public String getSenderAlias() {
		return senderAlias;
	}
	
	// connect to the mail server
	private void connect() throws NoSuchProviderException, MessagingException {
		//logger.info("Connecting to SMTP server: " + host);

		// set up properties for the mail session
		Properties props = System.getProperties();
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", host);
		props.put("mail.smtp.ssl.trust", host);
        props.put("mail.smtp.port", "587");
        props.put("mail.smtp.auth", "true");
		// Adding timeouts for extra debugging
		props.put("mail.smtp.connectiontimeout", "10000"); // 10 seconds
		props.put("mail.smtp.timeout", "10000");
		props.put("mail.smtp.writetimeout", "10000");
		// Enable debug output from the mail API
		//props.put("mail.debug", "true");
		
		final String username = senderAddress;
		final String password = senderPassword;

		// Log properties for debugging (avoid logging sensitive data)
		//logger.info("SMTP Properties: host=" + host + ", port=" + props.get("mail.smtp.port"));

        //session = Session.getDefaultInstance(props);
		//transport = session.getTransport("smtp");
		//transport.connect(host, senderAddress, senderPassword);
		Session session = Session.getInstance(props,
        	new Authenticator() {
            	@Override
            	protected PasswordAuthentication getPasswordAuthentication() {
                	return new PasswordAuthentication(username, password);
            	}
        	});
		this.session = session;
		//session.setDebug(true);

		try {
			transport = session.getTransport("smtp");
			transport.connect(host, senderAddress, senderPassword);
			logger.info("Connected to SMTP server successfully. transport.isConnected() = " + transport.isConnected());
		} catch (NoSuchProviderException e) {
			logger.warning("No such provider: " + e.getMessage());
			e.printStackTrace();
			throw e;
		} catch (MessagingException e) {
			logger.warning("Messaging exception: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}
	
	// send an email through the mail server
	private void sendEmail(String recipientEmail, String recipientAlias, String subject,
			String body) throws UnsupportedEncodingException, MessagingException {
		
        MimeMessage message = new MimeMessage(session);
        
        message.addHeader("Content-Type", "text/html; charset=utf-8");
        
        message.setFrom(new InternetAddress(senderAddress, senderAlias));
        message.addRecipient(Message.RecipientType.TO, 
        		new InternetAddress(recipientEmail, recipientAlias));
        
        message.setSubject(subject);
        //message.setText(body);
        
        message.setContent(body, "text/html");
        
        message.saveChanges();
        transport.sendMessage(message, message.getAllRecipients());
	}

	// close the connection to the mail server
	private void closeConnection() throws MessagingException {
        transport.close();
	}

	// close the connection, if one is open, when there is nothing useful to do on failure
	private void closeConnectionQuietly() {
		try {
			if (transport != null && transport.isConnected()) {
				transport.close();
			}
		} catch (MessagingException e) {
			logger.info("Error while closing the mail connection: " + e.getMessage());
		}
	}
	
	/**
	 * Check if a reminder email should be sent. Reminder email will be sent if
	 * 		1) The last reminder was not sent within the same day
	 * 		2) The time is at least 07:00
	 * 		3) The day is a weekday
	 * @return					True if a reminder email should be sent
	 */
	private boolean checkReminder() {
		
		// Initialize calendar
		Calendar now = new GregorianCalendar();
		
		// Check if the hour is past 7 and the day is not a weekend
		if (now.get(Calendar.HOUR_OF_DAY) >= 7 && 
				now.get(Calendar.DAY_OF_WEEK) != Calendar.SATURDAY &&
				now.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) {
			
			if (propHandler.getProperty("email.last_reminder") == null && now.get(Calendar.HOUR_OF_DAY) >= 7) {
				return true;
			}
			
			// Initialize last reminder calendar
			Calendar lastReminder = new GregorianCalendar();
			lastReminder.setTimeInMillis(Long.valueOf(propHandler.getProperty("email.last_reminder")));
			
			if (now.get(Calendar.DAY_OF_YEAR) != lastReminder.get(Calendar.DAY_OF_YEAR)) {
				return true;
			}
		}
		
		return false;
	}
	
	/**
	 * Builds the reminder email for the recipient, listing each of their packages.
	 * @return					{subject, body}
	 */
	private String[] buildReminder(Person recipient, ArrayList<Package> packages) {
		
		// Find variable values
		Map<String,String> variables = new HashMap<String,String>();
		variables.put("COMMENT", "");
		variables.put("PKGTIME", "");
		variables.put("PKGID",   "");  
		variables.put("FNAME",   recipient.getFirstName());  
		variables.put("LNAME",   recipient.getLastName());  
		variables.put("NETID",   recipient.getPersonID());  
		variables.put("NUMPKGS", String.valueOf(packages.size()));

		// Load email templates from template file
		Map<String,String> templates = TemplateHandler.getResolvedTemplates(variables);

		String body = templates.get("REMINDER-BODY");
		String subject = templates.get("REMINDER-SUBJECT");
		
		for (int i=0; i<packages.size(); i++) {
			Package pkg = packages.get(i);
			body += "Package " + (i+1) + " (ID: " + pkg.getPackageID() + ")" + ":\n";
			body += "\tChecked in on " + pkg.getCheckInDate().toString() + "\n";
			if(!pkg.getComment().isEmpty()) {
				body += "\tComment: " + pkg.getComment() + "\n";
			}
			body += "\n";
		}
		body += "Jones Mail Room";
		return new String[] {subject, body};
	}
	
	/**
	 * Function requests the view to get user input for new email information
	 * @return								True if the user input email information
	 */
	private boolean changeEmail() {
		String[] newEmail = viewAdaptor.changeEmail(senderAddress,senderPassword,senderAlias);
		if(newEmail != null) {
			setEmailProperties(newEmail[0],newEmail[1],newEmail[2]);
			return true;
		}
		return false;
	}
	
	/**
	 * Function that collects all of the pairs for the send all reminders function
	 * @param allEntriesSortedByPerson		DB entries sorted by person
	 * @return								ArrayList of pairs of person and owned packages
	 */
	private ArrayList<Pair<Person,ArrayList<Package>>> collectPairs(
			ArrayList<Pair<Person,Package>> allEntriesSortedByPerson) {
		
		// create a container for the result
		ArrayList<Pair<Person,ArrayList<Package>>> result = new ArrayList<Pair<Person,ArrayList<Package>>>();

		
		// initialize holders for the last person and a package list
		Person lastPerson = null;
		ArrayList<Package> pkgList = new ArrayList<Package>();
		for(Pair<Person,Package> entry : allEntriesSortedByPerson) {
			if (entry.first != lastPerson) {
				// if the person is new, add old person entry with packages
				if (lastPerson != null) {
					result.add(new Pair<Person,ArrayList<Package>>(lastPerson,pkgList));
				}
				
				// set the last person and give them a new package list
				lastPerson = entry.first;			// set the lastPerson
				pkgList = new ArrayList<Package>(); // new reference for new person
			}
			
			pkgList.add(entry.second);
		}
		
		// add the last person
		if (lastPerson != null && pkgList.size() > 0) {
			result.add(new Pair<Person,ArrayList<Package>>(lastPerson,pkgList));
		}
		
		return result;
	}

}
