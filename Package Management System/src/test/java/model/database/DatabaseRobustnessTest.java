package model.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import model.IModelToViewAdaptor;
import util.FileIO;
import util.Package;
import util.Person;
import util.PropertyHandler;

/**
 * The database has to keep the mail room running when one record is bad: a damaged
 * person file (which must also be kept, since it holds package history), a check-in for a student who has just been archived, or a roster file
 * with hundreds of unusable rows.
 */
class DatabaseRobustnessTest {

	@TempDir
	Path tempDir;

	private RecordingViewAdaptor view;
	private Database db;

	@BeforeEach
	void setUp() {
		PropertyHandler.getInstance().init(tempDir.toString());
		FileIO.init(tempDir.toString());
		view = new RecordingViewAdaptor();
		db = new Database(view);
	}

	private Path currentDir() {
		return tempDir.resolve("packages/current");
	}

	@Test
	void aDamagedPersonFileDoesNotStopStartup() throws Exception {
		db.start();
		db.addPerson(new Person("Pathak", "Navin", "np8@rice.edu", "np8"));

		// readUTF can still read this, but it is not valid JSON
		try (DataOutputStream out = new DataOutputStream(
				new FileOutputStream(currentDir().resolve("broken").toFile()))) {
			out.writeUTF("{\"first\": {\"lastName\": \"Hend");
		}

		Database restarted = new Database(view);
		restarted.start();

		assertNotNull(restarted.getPerson("np8"), "the good record should still load");
		assertFalse(Files.exists(currentDir().resolve("broken")), "the damaged file should be moved");
		assertEquals(1, damagedFiles().length, "the damaged file should be kept");
		assertEquals(1, view.warnings.size(), view.warnings.toString());
	}

	@Test
	void aDamagedArchiveFileSurvivesTheStudentBeingArchivedAgain() throws Exception {
		db.start();

		// np8's archived record is damaged when they come back
		try (DataOutputStream out = new DataOutputStream(new FileOutputStream(
				tempDir.resolve("packages/archive/np8").toFile()))) {
			out.writeUTF("{\"first\": {\"lastName\": \"Path");
		}
		assertTrue(db.addPerson(new Person("Pathak", "Navin", "np8@rice.edu", "np8")));

		// archiving them again used to overwrite the damaged file with an empty record
		assertTrue(db.deletePerson("np8"));

		File[] damaged = damagedFiles();
		assertEquals(1, damaged.length, "the damaged record should have been set aside");
		try (DataInputStream in = new DataInputStream(new FileInputStream(damaged[0]))) {
			assertTrue(in.readUTF().contains("Path"), "the damaged contents should be untouched");
		}
		assertEquals(1, view.warnings.size(), view.warnings.toString());
	}

	private File[] damagedFiles() {
		File[] files = tempDir.resolve("packages/damaged").toFile().listFiles();
		return files == null ? new File[0] : files;
	}

	@Test
	void checkingInForAStudentNotOnTheRosterFailsCleanly() {
		db.start();

		assertFalse(db.checkInPackage("gone1", new Package(20261007120000L, "", new Date())));
	}

	@Test
	void theImportConfirmationOnlyListsTheFirstFewBadRows() throws Exception {
		db.start();

		StringBuilder csv = new StringBuilder("Last Name,First Name,NetID\nPathak,Navin,np8\n");
		for (int i = 0; i < 300; i++) {
			csv.append("Nobody").append(i).append(",Someone,\n"); // no ID
		}
		Path file = tempDir.resolve("roster.csv");
		Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);

		db.importPersonsFromCSV(file.toString());

		assertEquals(1, view.questions.size());
		String confirm = view.questions.get(0);
		assertTrue(confirm.contains("300 row(s)"), confirm);
		assertTrue(confirm.contains("...and 290 more"), confirm);
		assertTrue(confirm.split("\n").length < 30, "dialog should fit on screen:\n" + confirm);
	}

	private static class RecordingViewAdaptor implements IModelToViewAdaptor {
		final List<String> questions = new ArrayList<String>();
		final List<String> warnings = new ArrayList<String>();

		public void displayMessage(String message, String title) { }
		public void displayError(String error, String title) { }
		public void displayWarning(String warning, String title) { warnings.add(warning); }
		public String getChoiceFromList(String m, String t, String[] c) { return null; }
		public String getPrinterNames(String[] printerNames) { return null; }
		public String[] changeEmail(String a, String p, String n) { return null; }
		public boolean getBooleanInput(String m, String t, String[] o) { questions.add(m); return false; }
	}
}
