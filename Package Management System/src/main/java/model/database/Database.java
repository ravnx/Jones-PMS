package model.database;

import util.FileIO;
import util.Package;
import util.Person;
import util.Pair;
import util.PropertyHandler;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.logging.Logger;

import com.google.gson.JsonParseException;

import model.IModelToViewAdaptor;

/*
 * Class that handles operations to and from the data files and DBMaps
 */

public class Database {
	
	IModelToViewAdaptor viewAdaptor;
	
	private DBMaps dbMaps;
	private DBFileIO dbIO;
	
	private String packageDirPath;
	private String currentDirPath;
	private String archiveDirPath;
	private String damagedDirPath;
	
	private Logger logger;

	public Database(IModelToViewAdaptor viewAdaptor) {
		
		this.viewAdaptor = viewAdaptor;
		
		String progDirPath = PropertyHandler.getInstance().getProperty("program_directory");
		this.packageDirPath = progDirPath + "/packages";
		this.currentDirPath = packageDirPath + "/current";
		this.archiveDirPath = packageDirPath + "/archive";
		this.damagedDirPath = packageDirPath + "/damaged";

		this.logger = Logger.getLogger(Database.class.getName());
		
		this.dbMaps = new DBMaps();
		this.dbIO = new DBFileIO();
	}
	
	/**
	 * Function whose start is controlled by the controller
	 * 
	 * Creates new package directories if they do not exist
	 * Reads the current database, initializing the DBMaps
	 */
	public void start() {
		// check if rootFolder and subfolders exist, create if they do not.
		FileIO.makeDirs(new String[] {packageDirPath, currentDirPath, archiveDirPath});
		
		// read the active package database
		readCurrentDatabase();
	}
	
	/**
	 * Checks in a package into the system, adding it to the dbMaps
	 * @param personID			ID of the owner
	 * @param pkg				package object to be added
	 * @return					Success of checking in package
	 */
	public boolean checkInPackage(String personID, Package pkg) {
		// the student may have been archived since the check-in list was loaded
		if(dbMaps.getPerson(personID) == null) {
			logger.warning("Package not checked in: person (ID: " + personID + ") is not on the roster.");
			return false;
		}

		// check if package already exists
		long pkgID = pkg.getPackageID();
		if(dbMaps.getPackage(pkgID) != null) {
			logger.warning("Package ID: " + pkgID + " was already checked in.");
			return false;
		}
		// modify current DBMaps
		dbMaps.addPackage(personID, pkg);
		// write new person file
		writePersonFile(personID,currentDirPath);
		
		return true;
	}
	
	/**
	 * Sets a check out date for the package
	 * @param pkgID				Package to check out
	 * @return					Success of checking out package
	 */
	public boolean checkOutPackage(long pkgID) {
		// modify package checkOut date
		Package pkg = dbMaps.getPackage(pkgID);
		if(pkg.getCheckOutDate() != null) {
			logger.info("Package ID: " + pkgID + " was already checked out.");		
			return false;
		} 		

		// note that this automatically edits the package in DBMaps
		pkg.setCheckOutDate(new Date());
		
		// write to file
		writePersonFile(dbMaps.getOwnerID(pkgID),currentDirPath);
		
		return true;
	}
	
	/**
	 * Edits a package in the database, editing the DBMaps and
	 * writing the changes to the owner's file
	 * @param newPerson			Package object containing new attributes for the package
	 * @return					Success of editing the package
	 */
	public boolean editPackage(Package pkg) {
		long pkgID = pkg.getPackageID();
		if(dbMaps.getPackage(pkgID) == null) {
			logger.warning("Package (ID: " + pkgID + ") to be edited by database not found.");
			return false;
		}
		
		// edit person in database maps and write to file
		dbMaps.editPackage(pkg);
		writePersonFile(dbMaps.getOwnerID(pkgID), currentDirPath);
		return true;
	}

	/**
	 * Returns a list of filtered and sorted packages, 
	 * according to the filter and sort string
	 * 
	 * filter should be written with all filters in the format
	 * 		field1=value1:field2=value2:field3=value3
	 * etc., where fields and values are defined as follows
	 * 	Fields with accompanying values:
	 * 		checked_in			
	 * 			<boolean>		Include only packages that have not been checked out
	 * 		person_name
	 * 			<String>		User input string to search Person firstName and LastName		
	 * 		on_date
	 * 			<String>		YYYYMMDD Date to get entries checked-in on
	 *		before_date			
	 * 			<String>		YYYYMMDD Date to get entries checked-in before
	 * 		after_date
	 * 			<String>		YYYYMMDD Date get entries checked-in after
	 * 
	 * sort should be written with highest priority sorts first
	 * in the format
	 * 		field1=value1:field2=value2:field3=value3
	 * 
	 * Where fields and values are defined as follows
	 * 	Fields:
	 * 		last_name			Person last name
	 * 		first_name			Person first name
	 * 		person_ID			Person personID
	 * 		package_ID			Package packageID
	 * 		check_in_date		Package check in date
	 * 		check_out_date		Package check out date
	 * 	Values:
	 * 		ASCENDING
	 * 		DESCENDING
	 * 
	 * @param filter			String containing filtering options
	 * @param sort				String containing sort options
	 */
	
	public ArrayList<Pair<Person,Package>> getEntries(String filter, String sort) {
		
		// get all of the entries from the database
		ArrayList<Pair<Person,Package>> result = dbMaps.getAllEntries();
		DBFormat.filter(result, filter);
		DBFormat.sort(result, sort);
		
		return result;
	}
	
	/**
	 * Returns a list of all people whose name contains searchString
	 * Uses the java String contains function
	 * @param searchString		String containing user input search
	 * @return					ArrayList of people with searchString in their
	 * 							lastName, firstName representation
	 */
	public ArrayList<Person> getPersonList(String searchString) {
		ArrayList<Person> result = new ArrayList<Person>();
		ArrayList<Person> allPersons = getAllCurrentPersons();
		
		//convert searchString to lower case for searching
		//internet says this is locale specific, beware
		searchString = searchString.toLowerCase();
		
		for (Person person: allPersons) {
			if(person.getLastFirstName().toLowerCase().contains(searchString)) {
				result.add(person);
			}
		}
		
		return result;
	}
	
	/**
	 * Returns a package from the package ID
	 * @param pkgID				ID of package to retrieve
	 * @return					Package object with given ID
	 */
	public Package getPackage(long pkgID) {
		return dbMaps.getPackage(pkgID);
	}
	
	/**
	 * Returns a person from the person ID
	 * @param personID			ID of the person to retrieve
	 * @return					Person object with given ID
	 */
	public Person getPerson(String personID) {
		return dbMaps.getPerson(personID);
	}
	
	/**
	 * Returns a person from a pkgID
	 * @param pkgID				ID of the package to retrieve the person
	 * @return					Package object
	 */
	public Person getOwner(long pkgID) {
		return dbMaps.getPerson(dbMaps.getOwnerID(pkgID));
	}
	
	/**
	 * Adds a person to the database maps and writes a file for the person
	 * 
	 * If the person is already in the archive, their file will be moved and their
	 * data will be read from the archive file.
	 * 
	 * If the person is not in the archive, a new person will be added to the database maps
	 * and a new file will be written for them.
	 * 
	 * @param person			Person object containing new person information
	 * @return					Success of adding the person
	 */
	public boolean addPerson(Person person) {
		//Check if person is already in the system
		String personID = person.getPersonID();
		if(dbMaps.getPerson(personID) != null) {
			logger.warning("Person (ID: " + personID + ") to be added by database already exists.");
			return false;
		} 
		
		//Check if person is in the archive
		HashSet<String> archiveFileNames = new HashSet<String>(FileIO.getFileNamesFromDirectory(archiveDirPath));
		HashSet<String> archivePersonIDs = archiveFileNames;
		
		//If person file is in archive, add file to DBMaps and delete archive file
		boolean restoredFromArchive = false;
		if(archivePersonIDs.contains(personID)) {
			String archiveFile = archiveDirPath + '/' + personID;
			restoredFromArchive = addPersonPackagesFromFile(archiveFile);
			if (!restoredFromArchive) {
				reportDamagedFiles("The archived record for " + person.getFullName() + " (" + personID
						+ ") could not be read, so they were added without their package history.");
			}
			if (restoredFromArchive) {
				FileIO.deleteFile(archiveFile);
				dbMaps.editPerson(person); //edit the person instead of adding
			}
		}

		if (!restoredFromArchive) {
			//Not in the archive, or the archived file could not be read: add them fresh.
			//An unreadable file has been moved to packages/damaged, where it can be
			//recovered by hand without being overwritten when they are next archived.
			dbMaps.addPerson(person);
		}
		
		//Write the new file
		writePersonFile(personID, currentDirPath);
		
		return true;
		 
	}
	
	/**
	 * Edits a person in the database, editing the DBMaps and
	 * writing the changes to their file
	 * @param newPerson			Person object containing new attributes for the person
	 * @return					Success of editing the person
	 */
	public boolean editPerson(Person newPerson) {
		String personID = newPerson.getPersonID();
		if(dbMaps.getPerson(personID) == null) {
			logger.warning("Person (ID: " + personID + ") to be edited by database not found.");
			return false;
		}
		
		// edit person in database maps and write to file
		dbMaps.editPerson(newPerson);
		writePersonFile(personID, currentDirPath);
		return true;
	}
	
	/**
	 * Moves a person from the current directory to the archive directory and deletes
	 * their information from the DBMaps
	 * @param personID			ID of the person to be deleted
	 * @return					Success of deleting the person
	 */
	public boolean deletePerson(String personID) {
		if(dbMaps.getPerson(personID) == null) {
			logger.warning("Person (ID: " + personID + ") to be deleted by database not found.");
			return false;
		}
		
		// move person file to the archive 
		writePersonFile(personID, archiveDirPath);
		FileIO.deleteFile(currentDirPath + '/' + personID);
		// remove person from DBMaps - must be after reading and writing file
		dbMaps.deletePerson(personID);
		
		return true;
		
	}
	
	/* Return a list of all persons in the current directory */
	private ArrayList<Person> getAllCurrentPersons() {
		return dbMaps.getAllPersons();
	}
	
	/* returns a list of all packages in the current directory */
	private ArrayList<Package> getAllCurrentPackages() {
		return dbMaps.getAllPackages();
	}
	
	/*
	 * Writes a json file containing a person object and all of its associated package objects
	 * to the directory indicated by baseDirectory
	 */
	private void writePersonFile(String personID, String baseDirectory) {
		// create the person and package pair from the DBMaps object
		Person person = dbMaps.getPerson(personID);
		ArrayList<Long> packageIDs = dbMaps.getOwnedPackageIDs(personID);
		ArrayList<Package> pkgList = new ArrayList<Package>();
		for (long pkgID: packageIDs) {
			pkgList.add(dbMaps.getPackage(pkgID));
		}
		
		Pair<Person,ArrayList<Package>> dbPair = 
				new Pair<Person,ArrayList<Package>>(person,pkgList);
		
		//write Pair object to file
		String fileName = baseDirectory + '/' + personID;
		try {
			dbIO.writeDatabaseJSONFile(dbPair, fileName);
		} catch (FileNotFoundException e) {
			logger.warning("Failed to find file: " + fileName);	
		}catch(IOException e) {
			logger.warning("Failed to write " + fileName);
			e.printStackTrace();
		}
	}
	
	/*
	 * Reads a json file containing a person object and all of its associated package objects
	 * from fileName into a Pair object
	 */
	private Pair<Person,ArrayList<Package>> readPersonFile(String fileName) {
		Pair<Person,ArrayList<Package>> dbPair = null;
		try {
			 dbPair = dbIO.readDatabaseJSONFile(fileName);
		} catch (FileNotFoundException e) {
			logger.warning("Failed to find file: " + fileName);	
		} catch (IOException e) {
			logger.warning("Failed to read " + fileName);
			e.printStackTrace();
		} catch (JsonParseException e) {
			// damaged or hand-edited JSON - Gson throws this unchecked, so it would
			// otherwise escape and stop the program at startup
			logger.warning("Damaged person file " + fileName + ": " + e.getMessage());
		}
		
		return dbPair;
		
	}
	
	/*
	 * Initializes the current database by reading all of the files in the current directory
	 * and placing all of their attributes into the database maps
	 */
	private void readCurrentDatabase() {
		ArrayList<String> currentFileNames = FileIO.getFileNamesFromDirectory(currentDirPath);
		int damaged = 0;
		for (String fileName: currentFileNames) {
			if (!addPersonPackagesFromFile(currentDirPath+'/'+fileName)) {
				damaged++;
			}
		}
		if (damaged > 0) {
			reportDamagedFiles(damaged + " student record(s) could not be read and were not loaded.");
		}
	}

	/*
	 * Tells the user that damaged files were set aside, and where to find them.
	 */
	private void reportDamagedFiles(String what) {
		if (viewAdaptor != null) {
			viewAdaptor.displayWarning(what + "\n\nThe damaged file(s) were moved to:\n " + damagedDirPath
					+ "\n\nKeep them - they hold the package history and may be recoverable.",
					"Damaged Records");
		}
	}

	/*
	 * Moves an unreadable person file to packages/damaged. Left where it was, it would
	 * be overwritten - and its package history lost - the next time that student's file
	 * is written to the same folder. Any earlier damaged copy is kept alongside.
	 */
	private void setAsideDamagedFile(String fileName) {
		File source = new File(fileName);
		if (!source.exists()) {
			return;
		}
		FileIO.makeDirs(damagedDirPath);
		File target = new File(damagedDirPath, source.getName() + "-"
				+ new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()));
		if (source.renameTo(target)) {
			logger.warning("Moved damaged person file " + fileName + " to " + target.getPath());
		} else {
			logger.severe("Could not move damaged person file " + fileName + " to " + target.getPath());
		}
	}
	
	/*
	 * Reads and adds all of the person and package information from the fileName to database maps
	 * Note: *Does not rewrite the file, make sure that calling function will write file*
	 */
	private boolean addPersonPackagesFromFile(String fileName) {
		Pair<Person,ArrayList<Package>> dbPair = readPersonFile(fileName);

		// readPersonFile returns null when the file is missing or unreadable. Skip it
		// rather than dereferencing - one damaged record must not stop the program.
		if (dbPair == null || dbPair.first == null || dbPair.first.getPersonID() == null) {
			logger.warning("Skipping unreadable person file: " + fileName);
			setAsideDamagedFile(fileName);
			return false;
		}

		Person person = dbPair.first;
		ArrayList<Package> packages = dbPair.second;

		dbMaps.addPerson(person);
		if (packages != null) {
			for (Package pkg: packages) {
				dbMaps.addPackage(person.getPersonID(), pkg);
			}
		}
		return true;
	}
		 
	/**
	 * Replaces the roster with the people in a CSV file.
	 *
	 * The file is read and validated in full before anything in the database changes, so
	 * a file that cannot be parsed leaves the roster exactly as it was. The user is then
	 * shown what the import will do and asked to confirm before it is applied.
	 *
	 * People already on the roster keep their packages and have their name and email
	 * updated from the file. People absent from the file are archived, and can be brought
	 * back - with their package history - by adding them again.
	 *
	 * @param filePath			Path to the CSV file to import
	 * @return					True if the roster was changed
	 */
	public boolean importPersonsFromCSV(String filePath) {

		/*
		 * Step 1: read the file. Nothing is deleted until this has succeeded - an
		 * unreadable or misformatted file must never cost the mail room its roster.
		 */
		ArrayList<Person> csvPersons;
		ArrayList<Pair<String,String>> failedToRead = new ArrayList<Pair<String,String>>();
		try {
			csvPersons = dbIO.readDatabaseCSVFile(filePath, failedToRead);
		} catch (FileNotFoundException e) {
			logger.severe("Failed to find file: " + filePath);
			viewAdaptor.displayError("Could not find the file:\n " + filePath
					+ "\n\nNo students were changed.", "Cannot Find File");
			return false;
		} catch (IOException e) {
			logger.severe("Failed to read file: " + filePath);
			viewAdaptor.displayError("Could not read the file:\n " + filePath
					+ "\n\nNo students were changed.", "Cannot Read File");
			return false;
		} catch (FileFormatException e) {
			logger.warning("Invalid csv file format for file: " + filePath + " - " + e.getMessage());
			viewAdaptor.displayError(e.getMessage()
					+ "\n\nNo students were changed.", "Invalid File Format");
			return false;
		}

		String failureReport = describeFailedRows(failedToRead);

		if (csvPersons.isEmpty()) {
			logger.warning("No usable rows in file: " + filePath);
			viewAdaptor.displayError("No students could be read from the file, so no students "
					+ "were changed." + failureReport, "Nothing to Import");
			return false;
		}

		/*
		 * Step 2: work out what the import would do, so the user can see it before
		 * committing to it.
		 */
		HashSet<String> csvPersonIDs = new HashSet<String>();
		for (Person person : csvPersons) {
			csvPersonIDs.add(person.getPersonID());
		}

		ArrayList<String> toArchive = new ArrayList<String>();
		int toUpdate = 0;
		for (String personID : dbMaps.getAllPersonIDs()) {
			if (csvPersonIDs.contains(personID)) {
				toUpdate++;
			} else {
				toArchive.add(personID);
			}
		}
		int toAdd = csvPersons.size() - toUpdate;

		StringBuilder summary = new StringBuilder();
		summary.append("Import ").append(csvPersons.size()).append(" students from:\n ")
				.append(new File(filePath).getName()).append("\n\n");
		summary.append(" - ").append(toAdd).append(" new students will be added\n");
		summary.append(" - ").append(toUpdate).append(" existing students will be updated\n");
		summary.append(" - ").append(toArchive.size())
				.append(" students are not in this file and will be archived\n");
		if (!toArchive.isEmpty()) {
			summary.append("   (archived students keep their packages and can be\n"
					+ "    restored with the Add button)\n");
		}
		summary.append(failureReport);
		summary.append("\nContinue?");

		/*
		 * Step 3: confirm, then apply. The roster is only touched past this point.
		 */
		if (!viewAdaptor.getBooleanInput(summary.toString(), "Confirm Import",
				new String[] {"Import", "Cancel"})) {
			logger.info("Import of " + filePath + " was cancelled by the user.");
			return false;
		}

		for (String personID : toArchive) {
			deletePerson(personID);
		}

		int added = 0;
		int updated = 0;
		for (Person person : csvPersons) {
			if (dbMaps.getPerson(person.getPersonID()) != null) {
				// already on the roster - refresh their details, keep their packages
				if (editPerson(person)) { updated++; }
			} else if (addPerson(person)) {
				added++;
			}
		}

		logger.info("Imported " + filePath + ": " + added + " added, " + updated
				+ " updated, " + toArchive.size() + " archived, "
				+ failedToRead.size() + " rows skipped.");

		viewAdaptor.displayMessage("Import complete.\n\n"
				+ " - " + added + " students added\n"
				+ " - " + updated + " students updated\n"
				+ " - " + toArchive.size() + " students archived"
				+ failureReport, "Import Complete");

		return true;
	}

	/*
	 * Builds the "rows that could not be read" section shared by the confirmation and the
	 * completion message. Returns "" when every row was usable.
	 */
	private static final int MAX_FAILED_ROWS_SHOWN = 10;

	private String describeFailedRows(ArrayList<Pair<String,String>> failedToRead) {
		if (failedToRead.isEmpty()) {
			return "";
		}

		StringBuilder report = new StringBuilder();
		report.append("\n").append(failedToRead.size())
				.append(" row(s) in the file could not be read:\n");
		for (Pair<String,String> failure : failedToRead) {
			report.append("   ").append(failure.first).append(" - ").append(failure.second).append('\n');
		}
		logger.warning(report.toString());

		// The full list is in the log. A dialog taller than the screen pushes its buttons
		// out of reach, so only show the first few.
		if (failedToRead.size() <= MAX_FAILED_ROWS_SHOWN) {
			return report.toString();
		}
		StringBuilder shortReport = new StringBuilder();
		shortReport.append("\n").append(failedToRead.size())
				.append(" row(s) in the file could not be read, including:\n");
		for (Pair<String,String> failure : failedToRead.subList(0, MAX_FAILED_ROWS_SHOWN)) {
			shortReport.append("   ").append(failure.first).append(" - ").append(failure.second).append('\n');
		}
		shortReport.append("   ...and ").append(failedToRead.size() - MAX_FAILED_ROWS_SHOWN)
				.append(" more (the full list is in the log file)\n");
		return shortReport.toString();
	}
	
}
