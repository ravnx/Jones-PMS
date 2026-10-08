package model;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;

import model.database.Database;
import model.email.Emailer;
import model.email.TemplateHandler;
import model.print.LabelPrinter;
import util.Package;
import util.Pair;
import util.Person;

/**
 * Package Manager class functions as a large model to string together the
 * database, emailer, and printer
 * @author Navin
 *
 */
public class PackageManager {

	private IModelToViewAdaptor viewAdaptor;
	
	private Database db;
	private Emailer mailer;
	private LabelPrinter printer;
	
	public PackageManager(IModelToViewAdaptor adpt) {
		
		viewAdaptor = adpt;
		
		// initialize the database
		db = new Database(viewAdaptor);
		mailer = new Emailer(viewAdaptor);
		printer = new LabelPrinter(viewAdaptor);
	}
	
	public void start() {
		// Start the database, mailer, and printer
		db.start();
		mailer.start(db.getEntries("checked_in=TRUE", "person_ID=ASCENDING"));
		printer.start();
	}
	
	//TODO Functions that run on a schedule - mainly send reminders
	
	/*
	 * Emailer functions
	 */
	
	public boolean sendPackageNotification(String personID, long pkgID) {
		Person person = db.getPerson(personID);
		Package pkg = db.getPackage(pkgID);
		if(mailer.sendPackageNotification(person, pkg)) {
			pkg.setNotificationSent(true);
			db.editPackage(pkg);
			return true;
		}
		return false;
	}
	
	public boolean sendPackageReminders() {
		ArrayList<Pair<Person,Package>> entriesSortedByPerson = 
				db.getEntries("checked_in=true", "person_ID=ASCENDING");
		return mailer.sendAllReminders(entriesSortedByPerson);
	}
	
	public boolean checkAdminPassword(String password) {
		// TODO Auto-generated method stub
		return false;
	}

	public void changeEmail(String newEmail, String newPassword, String newAlias) {
		mailer.setEmailProperties(newEmail, newPassword, newAlias);
	}
	
	public String getEmailAddress() {
		return mailer.getSenderAddress();
	}

	public String getEmailAlias() {
		return mailer.getSenderAlias();
	}
	
	public String getRawEmailTemplate() {
		return TemplateHandler.getRawFile();
	}
	
	public Map<String,String> getEmailTemplates(boolean convert, boolean comments) {
		return TemplateHandler.getTemplates(convert, comments);
	}
	
	public void changeEmailTemplate(Map<String,String> newTemplate) {
		TemplateHandler.writeNewTemplates(newTemplate);
	}
	
	/*
	 * Printer functions
	 */
	public boolean printLabel(long pkgID) {
		String personName = db.getOwner(pkgID).getLastFirstName();
		return printer.printLabel(Long.valueOf(pkgID).toString(), personName);
	}
	
	public String[] getPrinterNames() {
		return printer.getPrinterNames();
	}
	
	public void setPrinter(String printerName) {
		printer.setPrinter(printerName);
	}
	
	/*
	 * Database functions
	 */
	
	/** Returned by {@link #checkInPackage} when the package could not be stored. */
	public static final long CHECK_IN_FAILED = -1L;

	/*
	 * How many consecutive IDs to try before giving up. The ID is a timestamp to the
	 * second, so this is the number of packages that can be checked in within the same
	 * second - far beyond what one person at a counter can do.
	 */
	private static final int MAX_ID_ATTEMPTS = 100;

	/**
	 * Checks a package in and returns its ID.
	 *
	 * The ID is the check-in time to the second, so two packages checked in during the
	 * same second would collide. When that happens the next free ID is used instead.
	 * Previously the collision was ignored and the caller was handed an ID that had not
	 * been stored, so the label printed and the email sent both pointed at the earlier
	 * package - and therefore at the wrong student.
	 *
	 * @param personID			ID of the student the package is for
	 * @param comment			Optional comment to show in the notification
	 * @return					The new package's ID, or {@link #CHECK_IN_FAILED}
	 */
	public long checkInPackage(String personID, String comment) {
		// create a packageID from the current time
		Date now = new Date();
		SimpleDateFormat ft = new SimpleDateFormat("yyyyMMddHHmmss");
		long baseID = Long.parseLong(ft.format(now));

		for (int attempt = 0; attempt < MAX_ID_ATTEMPTS; attempt++) {
			Package pkg = new Package(baseID + attempt, comment, now);
			if (db.checkInPackage(personID, pkg)) {
				return pkg.getPackageID();
			}
		}

		return CHECK_IN_FAILED;
	}
	
	public boolean checkOutPackage(long pkgID) {
		return db.checkOutPackage(pkgID);		
	}
	
	public Package getPackage(long pkgID) {
		return db.getPackage(pkgID);
	}
	
	public Person getOwner(long pkgID) {
		return db.getOwner(pkgID);
	}

	public ArrayList<Person> getPersonList(String searchString) {
		return db.getPersonList(searchString);
	}
	

	public ArrayList<Pair<Person, Package>> getPackages(String filter, String sort) {
		return db.getEntries(filter,sort);
	}

	public void importPersonCSV(String fileName) {
		db.importPersonsFromCSV(fileName);		
	}

	public boolean addPerson(Person person) {
		return db.addPerson(person);
	}

	public boolean editPerson(Person person) {
		return db.editPerson(person);		
	}

	public boolean deletePerson(String personID) {
		return db.deletePerson(personID);
	}
	
}
