package model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import util.FileIO;
import util.Person;
import util.PropertyHandler;

/**
 * Checking out old packages in bulk, from the admin's side: by the day they were
 * checked in, and with a note saying how they were checked out.
 */
class OldPackagesTest {

	@TempDir
	Path tempDir;

	private PackageManager manager;

	@BeforeEach
	void setUp() {
		// See PackageIdCollisionTest: start() is not called, as it would reach Gmail and a printer
		PropertyHandler.getInstance().init(tempDir.toString());
		FileIO.init(tempDir.toString());
		FileIO.makeDirs(new String[] {
				tempDir.resolve("packages").toString(),
				tempDir.resolve("packages/current").toString(),
				tempDir.resolve("packages/archive").toString()});

		manager = new PackageManager(new SilentViewAdaptor());
		manager.addPerson(new Person("Pathak", "Navin", "np8@rice.edu", "np8"));
	}

	@Test
	void aPackageCheckedInTodayIsNotFromBeforeToday() {
		LocalDate today = LocalDate.now();
		long pkgID = manager.checkInPackage("np8", "");
		assumeTrue(today.equals(LocalDate.now()), "the date changed during the test");

		assertEquals(List.of(), manager.getPackagesCheckedInBefore(today));
		assertEquals(List.of(pkgID), manager.getPackagesCheckedInBefore(today.plusDays(1)));
	}

	@Test
	void bulkCheckOutNotesHowThePackageWasCheckedOut() {
		long pkgID = manager.checkInPackage("np8", "Large box");

		assertEquals(1, manager.checkOutPackages(List.of(pkgID)));

		assertEquals("Large box; " + PackageManager.BULK_CHECK_OUT_NOTE, manager.getPackage(pkgID).getComment());
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
