package model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import util.FileIO;
import util.Person;
import util.PropertyHandler;

/**
 * Package IDs are the check-in time to the second, so two packages checked in during the
 * same second used to collide. The database refused the duplicate and returned false, but
 * the caller discarded that and handed back the ID anyway - so a label was printed and an
 * email sent for a package that was never stored, and the barcode on the box resolved to
 * the earlier package belonging to a different student.
 */
class PackageIdCollisionTest {

	@TempDir
	Path tempDir;

	private PackageManager manager;

	@BeforeEach
	void setUp() {
		// The model reads its data directory from the property handler singleton
		PropertyHandler.getInstance().init(tempDir.toString());

		// Create the directories Database.start() would. start() is not called here
		// because PackageManager.start() would also try to reach Gmail and pick a printer.
		FileIO.init(tempDir.toString());
		FileIO.makeDirs(new String[] {
				tempDir.resolve("packages").toString(),
				tempDir.resolve("packages/current").toString(),
				tempDir.resolve("packages/archive").toString()});

		manager = new PackageManager(new SilentViewAdaptor());
		manager.addPerson(new Person("Pathak", "Navin", "np8@rice.edu", "np8"));
		manager.addPerson(new Person("Henderson", "Chris", "cwh1@rice.edu", "cwh1"));
	}

	@Test
	void rapidCheckInsGetDistinctIds() {
		HashSet<Long> ids = new HashSet<Long>();

		// Well inside one second, so every one of these would previously collide
		for (int i = 0; i < 20; i++) {
			long pkgID = manager.checkInPackage("np8", "package " + i);

			assertNotEquals(PackageManager.CHECK_IN_FAILED, pkgID,
					"check in " + i + " reported failure");
			assertTrue(ids.add(pkgID), "package ID " + pkgID + " was handed out twice");
		}

		assertEquals(20, ids.size());
	}

	@Test
	void everyIdHandedOutIsActuallyStored() {
		ArrayList<Long> ids = new ArrayList<Long>();
		for (int i = 0; i < 10; i++) {
			ids.add(manager.checkInPackage("np8", ""));
		}

		// The bug: an ID was returned for a package the database had rejected
		for (long pkgID : ids) {
			assertNotNull(manager.getPackage(pkgID),
					"package " + pkgID + " was never stored");
		}
	}

	@Test
	void eachStoredPackageBelongsToTheStudentItWasCheckedInFor() {
		long first = manager.checkInPackage("np8", "");
		long second = manager.checkInPackage("cwh1", "");

		assertNotEquals(first, second);
		assertEquals("np8", manager.getOwner(first).getPersonID());
		assertEquals("cwh1", manager.getOwner(second).getPersonID(),
				"a collision would have attached this package to the first student");
	}

	@Test
	void collidingCheckInsForDifferentStudentsStaySeparate() {
		// Alternating students within the same second is the case that used to print a
		// label naming the wrong person
		for (int i = 0; i < 10; i++) {
			String personID = (i % 2 == 0) ? "np8" : "cwh1";
			long pkgID = manager.checkInPackage(personID, "");

			assertNotEquals(PackageManager.CHECK_IN_FAILED, pkgID);
			assertEquals(personID, manager.getOwner(pkgID).getPersonID(),
					"package " + pkgID + " was attached to the wrong student");
		}
	}

	/**
	 * A view adaptor that answers nothing, so the model can run without a window.
	 */
	private static class SilentViewAdaptor implements IModelToViewAdaptor {
		public void displayMessage(String message, String title) { }
		public void displayError(String error, String title) { }
		public void displayWarning(String warning, String title) { }
		public String getChoiceFromList(String m, String t, String[] c) { return null; }
		public String getPrinterNames(String[] printerNames) { return null; }
		public String[] changeEmail(String a, String p, String n) { return null; }
		public boolean getBooleanInput(String m, String t, String[] o) { return false; }
	}
}
