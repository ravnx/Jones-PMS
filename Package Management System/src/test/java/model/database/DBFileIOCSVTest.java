package model.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import util.Pair;
import util.Person;

/**
 * Tests for the roster CSV reader.
 *
 * The reader locates columns by header name, so every column order the project has
 * documented over the years has to import cleanly. It also has to survive the rows a
 * real spreadsheet export produces - blank lines, short rows, quoted names - by
 * reporting them rather than throwing, because the caller archives the roster based
 * on what this returns.
 */
class DBFileIOCSVTest {

	@TempDir
	Path tempDir;

	private DBFileIO dbIO;
	private ArrayList<Pair<String,String>> failed;

	@BeforeEach
	void setUp() {
		dbIO = new DBFileIO();
		failed = new ArrayList<Pair<String,String>>();
	}

	private String write(String contents) throws IOException {
		Path file = tempDir.resolve("roster.csv");
		Files.writeString(file, contents, StandardCharsets.UTF_8);
		return file.toString();
	}

	private Person only(ArrayList<Person> people) {
		assertEquals(1, people.size(), "expected exactly one person");
		return people.get(0);
	}

	// ---- column orders -------------------------------------------------------

	@Test
	void readsTheOrderTheReadmeDocuments() throws Exception {
		ArrayList<Person> people = dbIO.readDatabaseCSVFile(write(
				"Last Name,First Name,NetID,Email Address\n"
				+ "Pathak,Navin,np8,np8@rice.edu\n"), failed);

		Person p = only(people);
		assertEquals("Pathak", p.getLastName());
		assertEquals("Navin", p.getFirstName());
		assertEquals("np8", p.getPersonID());
		assertEquals("np8@rice.edu", p.getEmailAddress());
		assertTrue(failed.isEmpty());
	}

	@Test
	void readsTheOrderTheOldParserRequired() throws Exception {
		ArrayList<Person> people = dbIO.readDatabaseCSVFile(write(
				"Last Name,First Name,Email Address,ID\n"
				+ "Pathak,Navin,np8@rice.edu,np8\n"), failed);

		Person p = only(people);
		assertEquals("Pathak", p.getLastName());
		assertEquals("np8", p.getPersonID());
		assertEquals("np8@rice.edu", p.getEmailAddress());
	}

	@Test
	void readsLowercaseAndReorderedHeaders() throws Exception {
		ArrayList<Person> people = dbIO.readDatabaseCSVFile(write(
				"netid,first,last,email\n"
				+ "np8,Navin,Pathak,np8@rice.edu\n"), failed);

		Person p = only(people);
		assertEquals("Pathak", p.getLastName());
		assertEquals("Navin", p.getFirstName());
		assertEquals("np8", p.getPersonID());
	}

	@Test
	void ignoresColumnsItDoesNotUse() throws Exception {
		ArrayList<Person> people = dbIO.readDatabaseCSVFile(write(
				"College,Last Name,Room,First Name,NetID,Email Address\n"
				+ "Jones,Pathak,317,Navin,np8,np8@rice.edu\n"), failed);

		Person p = only(people);
		assertEquals("Pathak", p.getLastName());
		assertEquals("np8", p.getPersonID());
		assertEquals("np8@rice.edu", p.getEmailAddress());
	}

	@Test
	void doesNotMistakeAnUnrelatedColumnForTheIdColumn() throws Exception {
		// "Resident" contains the letters "id" - it must not claim the ID role
		ArrayList<Person> people = dbIO.readDatabaseCSVFile(write(
				"Resident,Last Name,First Name,NetID\n"
				+ "yes,Pathak,Navin,np8\n"), failed);

		assertEquals("np8", only(people).getPersonID());
	}

	@Test
	void generatesAnEmailWhenTheColumnIsAbsent() throws Exception {
		ArrayList<Person> people = dbIO.readDatabaseCSVFile(write(
				"First Name,Last Name,NetID\n"
				+ "Navin,Pathak,np8\n"), failed);

		assertEquals("np8@rice.edu", only(people).getEmailAddress());
	}

	@Test
	void generatesAnEmailWhenTheCellIsBlank() throws Exception {
		ArrayList<Person> people = dbIO.readDatabaseCSVFile(write(
				"Last Name,First Name,NetID,Email Address\n"
				+ "Pathak,Navin,np8,\n"), failed);

		assertEquals("np8@rice.edu", only(people).getEmailAddress());
	}

	@Test
	void toleratesAByteOrderMark() throws Exception {
		ArrayList<Person> people = dbIO.readDatabaseCSVFile(write(
				"﻿Last Name,First Name,NetID,Email Address\n"
				+ "Pathak,Navin,np8,np8@rice.edu\n"), failed);

		assertEquals("np8", only(people).getPersonID());
	}

	// ---- rows a real export produces ----------------------------------------

	@Test
	void skipsBlankLinesInsteadOfThrowing() throws Exception {
		ArrayList<Person> people = dbIO.readDatabaseCSVFile(write(
				"Last Name,First Name,NetID,Email Address\n"
				+ "Pathak,Navin,np8,np8@rice.edu\n"
				+ "\n"
				+ "   \n"
				+ "Henderson,Chris,cwh1,cwh1@rice.edu\n"
				+ "\n"), failed);

		assertEquals(2, people.size());
		assertTrue(failed.isEmpty(), "blank lines are not failures");
	}

	@Test
	void reportsShortRowsInsteadOfThrowing() throws Exception {
		ArrayList<Person> people = dbIO.readDatabaseCSVFile(write(
				"Last Name,First Name,NetID,Email Address\n"
				+ "Pathak,Navin,np8,np8@rice.edu\n"
				+ "Henderson,Chris\n"), failed);

		assertEquals(1, people.size());
		assertEquals(1, failed.size());
		assertEquals("ID not provided", failed.get(0).second);
		assertTrue(failed.get(0).first.contains("Row 3"), failed.get(0).first);
	}

	@Test
	void keepsQuotedFieldsWithCommasIntact() throws Exception {
		ArrayList<Person> people = dbIO.readDatabaseCSVFile(write(
				"Last Name,First Name,NetID,Email Address\n"
				+ "\"Smith, Jr.\",John,js42,js42@rice.edu\n"), failed);

		Person p = only(people);
		assertEquals("Smith, Jr.", p.getLastName());
		assertEquals("John", p.getFirstName());
		assertEquals("js42", p.getPersonID());
	}

	@Test
	void unescapesDoubledQuotes() throws Exception {
		ArrayList<Person> people = dbIO.readDatabaseCSVFile(write(
				"Last Name,First Name,NetID\n"
				+ "Smith,\"Jo \"\"JJ\"\" John\",js42\n"), failed);

		assertEquals("Jo \"JJ\" John", only(people).getFirstName());
	}

	@Test
	void reportsDuplicateAndMissingIdsAndKeepsGoing() throws Exception {
		ArrayList<Person> people = dbIO.readDatabaseCSVFile(write(
				"Last Name,First Name,NetID,Email Address\n"
				+ "Pathak,Navin,np8,np8@rice.edu\n"
				+ "Pathak,Navin,np8,np8@rice.edu\n"
				+ "Bobmanuel,Ambi,,ajb6@rice.edu\n"
				+ "Henderson,Chris,cwh1,cwh1@rice.edu\n"), failed);

		assertEquals(2, people.size());
		assertEquals(2, failed.size());
		assertTrue(failed.get(0).second.startsWith("Duplicate ID"), failed.get(0).second);
		assertEquals("ID not provided", failed.get(1).second);
	}

	@Test
	void reportsRowsWithNoNameAtAll() throws Exception {
		ArrayList<Person> people = dbIO.readDatabaseCSVFile(write(
				"Last Name,First Name,NetID\n"
				+ ",,np8\n"), failed);

		assertTrue(people.isEmpty());
		assertEquals(1, failed.size());
		assertEquals("Name not provided", failed.get(0).second);
	}

	// ---- headers that must be rejected --------------------------------------

	@Test
	void rejectsAHeaderMissingARequiredColumn() throws Exception {
		String path = write("Last Name,First Name,Email Address\n"
				+ "Pathak,Navin,np8@rice.edu\n");

		FileFormatException e = assertThrows(FileFormatException.class,
				() -> dbIO.readDatabaseCSVFile(path, failed));
		assertTrue(e.getMessage().contains("ID"), e.getMessage());
	}

	@Test
	void rejectsAnEmptyFile() throws Exception {
		String path = write("");

		assertThrows(FileFormatException.class,
				() -> dbIO.readDatabaseCSVFile(path, failed));
	}

	@Test
	void reportsAMissingFileAsAnIOException() {
		String path = tempDir.resolve("does-not-exist.csv").toString();

		assertThrows(IOException.class, () -> dbIO.readDatabaseCSVFile(path, failed));
	}
}
