package model.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import util.Package;
import util.Pair;
import util.Person;

/**
 * Person files have to survive three things: being written by an older version (a
 * length-prefixed format whose dates are Java-version-specific display text), growing
 * past the 64 KB the old format could hold, and a save being interrupted.
 */
class PersonFileFormatTest {

	@TempDir
	Path tempDir;

	private final DBFileIO dbIO = new DBFileIO();

	/* A file exactly as versions before 2.0 wrote it, with the given check-in date text. */
	private Path legacyFile(String checkInDate) throws Exception {
		String json = "{\n  \"first\": {\n    \"lastName\": \"Pathak\",\n    \"firstName\": \"Navin\",\n"
				+ "    \"emailAddress\": \"np8@rice.edu\",\n    \"personID\": \"np8\"\n  },\n"
				+ "  \"second\": [\n    {\n      \"packageID\": 20250423182840,\n"
				+ "      \"comment\": \"José's box\",\n      \"checkInDate\": \"" + checkInDate + "\",\n"
				+ "      \"notificationSent\": true\n    }\n  ]\n}";
		Path file = tempDir.resolve("np8");
		try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file.toFile()))) {
			out.writeUTF(json);
		}
		return file;
	}

	private static Date localTime(String text) throws Exception {
		return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(text);
	}

	@Test
	void readsFilesWrittenByEveryOlderJavaVersion() throws Exception {
		String[] forms = {
			"Apr 23, 2025 6:28:40 PM",			// Java 8
			"Apr 23, 2025, 6:28:40 PM",			// Java 9-19
			"Apr 23, 2025, 6:28:40 PM",	// Java 20+, which this machine's real file has
		};
		for (String form : forms) {
			Pair<Person,ArrayList<Package>> pair = dbIO.readDatabaseJSONFile(legacyFile(form).toString());

			assertEquals("np8", pair.first.getPersonID(), form);
			Package pkg = pair.second.get(0);
			assertEquals(20250423182840L, pkg.getPackageID(), form);
			assertEquals(localTime("2025-04-23 18:28:40"), pkg.getCheckInDate(), form);
			assertEquals("José's box", pkg.getComment(), form);
		}
	}

	@Test
	void anOldFileIsRewrittenInTheNewFormatWithoutChangingIt() throws Exception {
		Path file = legacyFile("Apr 23, 2025, 6:28:40 PM");
		Pair<Person,ArrayList<Package>> before = dbIO.readDatabaseJSONFile(file.toString());

		dbIO.writeDatabaseJSONFile(before, file.toString());

		String text = Files.readString(file, StandardCharsets.UTF_8);
		assertTrue(text.startsWith("{"), "should now be plain JSON: " + text);
		assertTrue(text.contains("\"2025-04-23T"), "dates should be ISO-8601: " + text);

		Pair<Person,ArrayList<Package>> after = dbIO.readDatabaseJSONFile(file.toString());
		assertEquals(before.second.get(0).getCheckInDate(), after.second.get(0).getCheckInDate());
		assertEquals("José's box", after.second.get(0).getComment());
	}

	@Test
	void savesAStudentWithMorePackagesThanTheOldFormatCouldHold() throws Exception {
		ArrayList<Package> packages = new ArrayList<Package>();
		for (int i = 0; i < 1000; i++) {
			Package pkg = new Package(20260101000000L + i, "a comment that takes up some room", new Date());
			pkg.setCheckOutDate(new Date());
			packages.add(pkg);
		}
		Path file = tempDir.resolve("np8");

		dbIO.writeDatabaseJSONFile(new Pair<Person,ArrayList<Package>>(
				new Person("Pathak", "Navin", "np8@rice.edu", "np8"), packages), file.toString());

		assertTrue(Files.size(file) > 65535, "precondition: larger than writeUTF allows");
		assertEquals(1000, dbIO.readDatabaseJSONFile(file.toString()).second.size());
	}

	@Test
	void leavesNoTemporaryFilesBehind() throws Exception {
		Path file = tempDir.resolve("np8");
		Pair<Person,ArrayList<Package>> pair = new Pair<Person,ArrayList<Package>>(
				new Person("Pathak", "Navin", "np8@rice.edu", "np8"), new ArrayList<Package>());

		dbIO.writeDatabaseJSONFile(pair, file.toString());
		dbIO.writeDatabaseJSONFile(pair, file.toString());

		try (var files = Files.list(tempDir)) {
			assertEquals(1, files.count(), "only the person file should remain");
		}
	}

	@Test
	void aMissingCheckOutDateStaysMissing() throws Exception {
		Path file = tempDir.resolve("np8");
		ArrayList<Package> packages = new ArrayList<Package>();
		packages.add(new Package(20260101000000L, "", new Date()));

		dbIO.writeDatabaseJSONFile(new Pair<Person,ArrayList<Package>>(
				new Person("Pathak", "Navin", "np8@rice.edu", "np8"), packages), file.toString());

		assertNull(dbIO.readDatabaseJSONFile(file.toString()).second.get(0).getCheckOutDate());
	}

	@Test
	void rejectsADateItCannotUnderstand() {
		assertFalse(isParseable("next Tuesday"));
	}

	private static boolean isParseable(String text) {
		try {
			DateAdapter.parse(text);
			return true;
		} catch (RuntimeException e) {
			return false;
		}
	}
}
