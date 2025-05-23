package model.email;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Date;
import java.util.logging.Logger;

import jakarta.mail.Authenticator;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.NoSuchProviderException;
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
	
	public Emailer(IModelToViewAdaptor viewAdaptor) {
		// get PropertyHandler and logger instance
		this.propHandler = PropertyHandler.getInstance();
		this.logger = Logger.getLogger(Emailer.class.getName());
		this.logger.setLevel(java.util.logging.Level.ALL);
		
		java.util.logging.ConsoleHandler handler = new java.util.logging.ConsoleHandler();
		//handler.setLevel(java.util.logging.Level.ALL);
		this.logger.addHandler(handler);

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
		while(this.senderAddress == null || this.senderPassword == null || this.senderAlias == null) {
			logger.warning("Failed to load email properties.");
			viewAdaptor.displayMessage("Email information was not loaded from file.\n"
					+ "Please change email information in the next window.", 
					"Email Not Loaded");			
			changeEmail();
		}
		
		// attempt to connect to the mail server and alert user if it fails

		attemptConnection();
		
		if(checkReminder()) {
			sendAllReminders(activeEntriesSortedByPerson);
		}
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
					System.err.println("Auth failed: " + e);

					System.err.format(e.toString());
					e.printStackTrace();
					
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
	 * Function that sends all reminder emails
	 * @param allEntriesSortedByPerson	All active entries - MUST be sorted by person
	 * @return							Success of sending all reminders
	 */
	public boolean sendAllReminders(ArrayList<Pair<Person,Package>> allEntriesSortedByPerson) {
		
		//collect ArrayList of pairs of person,ArrayList<Package>
		ArrayList<Pair<Person,ArrayList<Package>>> remindList = collectPairs(allEntriesSortedByPerson);		
		try {
			connect();
			// iterate through ArrayList, sending emails if the person has packages
			for (Pair<Person,ArrayList<Package>> ppPair : remindList) {
				sendPackageReminder(ppPair.first,ppPair.second);
			}
			closeConnection();
			
			logger.info("Successfully sent reminder emails.");
			
			// add property with the current time as the last sent date
			propHandler.setProperty("email.last_reminder", Long.valueOf(new Date().getTime()).toString());
			return true;
		} catch(NoSuchProviderException e) {
			logger.warning(e.getMessage());
			return false;
		} catch(MessagingException e) {
			logger.warning(e.getMessage());
			return false;
		} catch (UnsupportedEncodingException e) {
			logger.severe(e.getMessage());
			return false;
		}
	}

	/*
	 * Sends a notification email to the recipient informing them that they 
	 * have a new package 
	 */
	public boolean sendPackageNotification(Person recipient, Package pkg) {
	
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

		// Load email templates from template file
		Map<String,String> templates = TemplateHandler.getResolvedTemplates(variables);

		String body = templates.get("NOTIFICATION-BODY");
		String subject = templates.get("NOTIFICATION-SUBJECT");
		try {
			connect();
			sendEmail(recipient.getEmailAddress(), recipient.getFullName(), subject, body);
			closeConnection();
		} catch (UnsupportedEncodingException e) {
			logger.severe("UnsupportedEncodingException for Person (ID: " + recipient.getPersonID() +
					") and Package (ID: " + pkg.getPackageID() + ")");
			return false;
		} catch (MessagingException e) {
			logger.warning(e.getMessage());
			return false;
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
	 * Sends a reminder email to the recipient reminding them of each package that
	 * is returned by recipient.getPackageList()
	 */
	private void sendPackageReminder(Person recipient, ArrayList<Package> packages) 
			throws UnsupportedEncodingException, MessagingException {
		
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
		sendEmail(recipient.getEmailAddress(), recipient.getFullName(), subject, body);
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
