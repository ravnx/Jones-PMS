package model.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import model.IModelToViewAdaptor;
import util.FileIO;
import util.Package;
import util.Pair;
import util.Person;
import util.PropertyHandler;

/**
 * Packages that were picked up but never scanned out keep getting reminder emails, so
 * the mail room needs to check out many old packages at once.
 */
class BulkCheckOutTest {

	private static final String NOTE = "Checked out in bulk by admin";

	@TempDir
	Path tempDir;

	private Database db;

	@BeforeEach
	void setUp() {
		PropertyHandler.getInstance().init(tempDir.toString());
		FileIO.init(tempDir.toString());
		db = new Database(new SilentViewAdaptor());
		db.start();
		db.addPerson(new Person("Pathak", "Navin", "np8@rice.edu", "np8"));
		db.addPerson(new Person("Henderson", "Chris", "cwh1@rice.edu", "cwh1"));
	}

	private static Date daysAgo(int days) {
		return new Date(System.currentTimeMillis() - days * 86_400_000L);
	}

	private long checkIn(String personID, long pkgID, String comment, Date when) {
		assertTrue(db.checkInPackage(personID, new Package(pkgID, comment, when)));
		return pkgID;
	}

	@Test
	void checksOutEveryListedPackageWithTheNote() {
		long first = checkIn("np8", 1, "", daysAgo(200));
		long second = checkIn("cwh1", 2, "", daysAgo(150));

		assertEquals(2, db.checkOutPackages(List.of(first, second), NOTE));

		for (long pkgID : new long[] { first, second }) {
			assertNotNull(db.getPackage(pkgID).getCheckOutDate());
			assertEquals(NOTE, db.getPackage(pkgID).getComment());
		}
	}

	@Test
	void keepsAnExistingCommentBeforeTheNote() {
		long pkgID = checkIn("np8", 1, "Large box", daysAgo(200));

		db.checkOutPackages(List.of(pkgID), NOTE);

		assertEquals("Large box; " + NOTE, db.getPackage(pkgID).getComment());
	}

	@Test
	void aPackageWithoutACommentGetsJustTheNote() {
		long pkgID = checkIn("np8", 1, null, daysAgo(200));

		db.checkOutPackages(List.of(pkgID), NOTE);

		assertEquals(NOTE, db.getPackage(pkgID).getComment());
	}

	@Test
	void leavesAlreadyCheckedOutPackagesAlone() {
		long pkgID = checkIn("np8", 1, "", daysAgo(200));
		db.checkOutPackage(pkgID);
		Date scannedOut = db.getPackage(pkgID).getCheckOutDate();

		assertEquals(0, db.checkOutPackages(List.of(pkgID), NOTE));

		assertEquals(scannedOut, db.getPackage(pkgID).getCheckOutDate());
		assertEquals("", db.getPackage(pkgID).getComment());
	}

	@Test
	void ignoresPackagesThatNoLongerExist() {
		long pkgID = checkIn("np8", 1, "", daysAgo(200));

		assertEquals(1, db.checkOutPackages(List.of(999L, pkgID), NOTE));
	}

	@Test
	void checkedOutPackagesGetNoMoreReminders() {
		long old = checkIn("np8", 1, "", daysAgo(200));
		long recent = checkIn("cwh1", 2, "", daysAgo(1));

		db.checkOutPackages(List.of(old), NOTE);

		// The query the reminder emails are sent from
		List<Long> stillWaiting = new ArrayList<Long>();
		for (Pair<Person, Package> entry : db.getEntries("checked_in=TRUE", "person_ID=ASCENDING")) {
			stillWaiting.add(entry.second.getPackageID());
		}
		assertEquals(List.of(recent), stillWaiting);
	}

	@Test
	void theCheckOutIsSaved() {
		long pkgID = checkIn("np8", 1, "", daysAgo(200));

		db.checkOutPackages(List.of(pkgID), NOTE);

		Database restarted = new Database(new SilentViewAdaptor());
		restarted.start();
		assertNotNull(restarted.getPackage(pkgID).getCheckOutDate());
		assertEquals(NOTE, restarted.getPackage(pkgID).getComment());
	}

	@Test
	void findsPackagesStillCheckedInFromBeforeTheCutoff() {
		Date cutoff = daysAgo(100);
		long old = checkIn("np8", 1, "", daysAgo(200));
		long justBefore = checkIn("cwh1", 2, "", new Date(cutoff.getTime() - 1000));
		checkIn("np8", 3, "", cutoff);
		checkIn("cwh1", 4, "", daysAgo(10));
		long oldButScannedOut = checkIn("np8", 5, "", daysAgo(300));
		db.checkOutPackage(oldButScannedOut);

		Set<Long> found = new HashSet<Long>(db.getPackagesCheckedInBefore(cutoff));

		assertEquals(Set.of(old, justBefore), found);
	}

	@Test
	void findsNothingWhenEverythingIsRecent() {
		checkIn("np8", 1, "", daysAgo(1));

		assertTrue(db.getPackagesCheckedInBefore(daysAgo(30)).isEmpty());
		assertNull(db.getPackage(1).getCheckOutDate());
	}

	private static class SilentViewAdaptor implements IModelToViewAdaptor {
		public void displayMessage(String message, String title) { }
		public void displayError(String error, String title) { }
		public void displayWarning(String warning, String title) { }
		public String getChoiceFromList(String m, String t, String[] c) { return null; }
		public String getPrinterNames(String[] printerNames) { return null; }
		public util.Pair<String[],util.SmtpSettings> changeEmail(String a, String p, String n, util.SmtpSettings s) { return null; }
		public boolean getBooleanInput(String m, String t, String[] o) { return false; }
	}
}
