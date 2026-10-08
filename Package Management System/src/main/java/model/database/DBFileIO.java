package model.database;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

import util.Package;
import util.Pair;
import util.Person;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
/**
 * Class contains functions for writing database entries to file and
 * pulling person objects from a csv file.
 */
public class DBFileIO {

	private Logger logger;

	public DBFileIO() {
		this.logger = Logger.getLogger(DBFileIO.class.getName());
	}

	/*
	 * Dates are written as ISO-8601 by DateAdapter, which also reads every older form.
	 */
	private static Gson gson() {
		return new GsonBuilder()
				.setPrettyPrinting()
				.registerTypeAdapter(Date.class, new DateAdapter())
				.create();
	}

	private static final Type PAIR_TYPE = new TypeToken<Pair<Person,ArrayList<Package>>>(){}.getType();

	/**
	 * Function that will write a pair containing a person object and all associated packages
	 * to the specified JSON file.
	 *
	 * The file is plain UTF-8 JSON. It is written to a hidden temporary file in the same
	 * folder and then moved over the old one, so a crash or power cut part way through
	 * leaves the previous version intact rather than a half-written file. Older versions
	 * used DataOutputStream.writeUTF, which also cannot write more than 64 KB.
	 *
	 * @param DBPair			Pair containing a person and ArrayList of all packages associated
	 * @param filePath			Path to the file to be written
	 * @throws IOException
	 * @throws FileNotFoundException
	 */
	public void writeDatabaseJSONFile(Pair<Person,ArrayList<Package>> DBPair, String filePath)
			throws IOException,FileNotFoundException {

		byte[] json = gson().toJson(DBPair).getBytes(StandardCharsets.UTF_8);

		Path target = Paths.get(filePath);
		// a leading "." keeps the temporary file out of directory listings of person files
		Path temp = Files.createTempFile(target.toAbsolutePath().getParent(),
				"." + target.getFileName() + ".", ".tmp");
		try {
			try (FileOutputStream out = new FileOutputStream(temp.toFile())) {
				out.write(json);
				out.getFD().sync(); // on disk before it replaces the old file
			}
			try {
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	/**
	 * Function that will read a specified JSON file and return the person and packages
	 * contained within. Reads both plain JSON files and the length-prefixed files written
	 * by versions before 2.0, which are converted the next time they are saved.
	 *
	 * @param filePath			Path to the file with the person information
	 * @return					Returns a pair of person and packages in arrayList object
	 * @throws IOException
	 * @throws FileNotFoundException
	 */
	public Pair<Person,ArrayList<Package>> readDatabaseJSONFile(String filePath)
			throws IOException,FileNotFoundException {

		byte[] bytes;
		try (FileInputStream in = new FileInputStream(filePath)) {
			bytes = in.readAllBytes();
		}

		if (isLegacyFormat(bytes)) {
			try {
				String json = new DataInputStream(new ByteArrayInputStream(bytes)).readUTF();
				return gson().fromJson(json, PAIR_TYPE);
			} catch (IOException | JsonParseException e) {
				// the length prefix matched by coincidence - read it as plain JSON below
			}
		}
		return gson().fromJson(new String(bytes, StandardCharsets.UTF_8), PAIR_TYPE);
	}

	/*
	 * writeUTF files start with a two-byte length that is exactly the rest of the file.
	 */
	private static boolean isLegacyFormat(byte[] bytes) {
		return bytes.length >= 2
				&& (((bytes[0] & 0xff) << 8) | (bytes[1] & 0xff)) == bytes.length - 2;
	}

	/*
	 * Roles a CSV column can fill. Columns are located by matching the header text,
	 * so the order of columns in the file does not matter.
	 */
	private static final int LAST = 0;
	private static final int FIRST = 1;
	private static final int EMAIL = 2;
	private static final int ID = 3;
	private static final String[] ROLE_NAMES = {"last name", "first name", "email address", "ID"};

	/**
	 * Reads a CSV file and returns the list of people within the file.
	 *
	 * The file must have a header row. Columns are located by name rather than by
	 * position, so any column order works and unrecognised columns are ignored. A
	 * column is matched to a role by the first of these its header contains:
	 *
	 * 	email address	"mail"
	 * 	last name		"last", "surname", "family"
	 * 	first name		"first", "given"
	 * 	ID				"netid", "net id", "username" - or "id" when none of those
	 *
	 * Last name, first name and ID are required; the email column is optional and an
	 * address is generated from the ID when it is missing or blank.
	 *
	 * Quoted fields are supported, so a value may contain a comma ("Smith, Jr.").
	 * Blank lines are skipped. A row that cannot be used - no ID, a duplicate ID, or
	 * no name at all - is recorded in {@code failed} and skipped rather than aborting
	 * the whole import.
	 *
	 * @param filePath			Name of the CSV file to read from
	 * @param failed			ArrayList that receives a pair of the person that could
	 * 							not be read and the reason
	 * @return					List of all of the person objects in the file
	 * @throws IOException
	 * @throws FileNotFoundException
	 * @throws FileFormatException 	If the header is missing or lacks a required column
	 */
	public ArrayList<Person> readDatabaseCSVFile(String filePath, ArrayList<Pair<String,String>> failed)
			throws IOException, FileNotFoundException, FileFormatException{

		ArrayList<Person> personList = new ArrayList<Person>();
		HashSet<String> personIDSet = new HashSet<String>();

		byte[] bytes;
		try (FileInputStream in = new FileInputStream(filePath)) {
			bytes = in.readAllBytes();
		}

		try (BufferedReader br = new BufferedReader(new StringReader(decodeCsv(bytes)))) {

			// handle the header
			String headerLine = br.readLine();
			if (headerLine == null) {
				throw new FileFormatException("The file is empty. It must begin with a header row, "
						+ "for example:\n Last Name,First Name,NetID,Email Address");
			}
			int[] columns = mapColumns(splitCsvLine(stripBom(headerLine)));

			// loop through the file
			int rowNumber = 1;
			String line;
			while ((line = br.readLine()) != null) {
				rowNumber++;

				// ignore empty lines
				if (line.isBlank()) {
					continue;
				}

				List<String> fields = splitCsvLine(line);
				String lastName = fieldAt(fields, columns[LAST]);
				String firstName = fieldAt(fields, columns[FIRST]);
				String personID = fieldAt(fields, columns[ID]);
				String label = describeRow(rowNumber, firstName, lastName);

				// handle rows that cannot become a Person
				if (personID.isEmpty()) {
					failed.add(new Pair<String,String>(label, "ID not provided"));
					continue;
				}
				if (lastName.isEmpty() && firstName.isEmpty()) {
					failed.add(new Pair<String,String>(label, "Name not provided"));
					continue;
				}
				if (!personIDSet.add(personID)) {
					failed.add(new Pair<String,String>(label, "Duplicate ID (" + personID + ")"));
					continue;
				}

				String emailAddress = fieldAt(fields, columns[EMAIL]);
				if (emailAddress.isEmpty()) {
					emailAddress = Person.generateEmail(personID);
					logger.info("Email automatically generated for " + firstName + ' ' + lastName);
				}

				personList.add(new Person(lastName, firstName, emailAddress, personID));
			}
		}

		logger.info(filePath + " was successfully read: " + personList.size()
				+ " people, " + failed.size() + " rows skipped");
		return personList;
	}

	/*
	 * Decodes the file as UTF-8 when it is valid UTF-8, and otherwise as Windows-1252,
	 * which is what Excel's plain "CSV (Comma delimited)" export writes on Windows.
	 * Decoding that as UTF-8 would turn "José" into "Jos?" and the import would then
	 * overwrite the correctly spelled name already on the roster.
	 */
	static String decodeCsv(byte[] bytes) {
		try {
			return StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException e) {
			return new String(bytes, Charset.forName("windows-1252"));
		}
	}

	/*
	 * Locates each required column by its header text. Returns an array indexed by the
	 * role constants, holding the column index for that role or -1 when absent.
	 */
	private static int[] mapColumns(List<String> header) throws FileFormatException {
		int[] columns = {-1, -1, -1, -1};

		// A NetID or username column is the key whenever there is one. A plain "ID"
		// column is only used without one: registrar exports often have a numeric
		// "Student ID" before "NetID", and keying on that would archive the whole roster.
		int genericIdColumn = -1;

		for (int i = 0; i < header.size(); i++) {
			String text = header.get(i).toLowerCase(Locale.ROOT).replace('_', ' ').trim();

			int role;
			if (text.contains("mail")) {
				role = EMAIL;
			} else if (text.contains("last") || text.contains("surname") || text.contains("family")) {
				role = LAST;
			} else if (text.contains("first") || text.contains("given")) {
				role = FIRST;
			} else if (isNetIdHeader(text)) {
				role = ID;
			} else if (text.matches(".*\\bid\\b.*")) {
				if (genericIdColumn == -1) {
					genericIdColumn = i;
				}
				continue;
			} else {
				continue; // a column we do not use
			}

			// first matching column wins, so a stray later column cannot displace it
			if (columns[role] == -1) {
				columns[role] = i;
			}
		}

		if (columns[ID] == -1) {
			columns[ID] = genericIdColumn;
		}

		// email is optional - it is generated from the ID when absent
		ArrayList<String> missing = new ArrayList<String>();
		for (int role : new int[] {LAST, FIRST, ID}) {
			if (columns[role] == -1) {
				missing.add(ROLE_NAMES[role]);
			}
		}
		if (!missing.isEmpty()) {
			throw new FileFormatException("The file's header is missing a column for: "
					+ String.join(", ", missing)
					+ "\n\nThe header found was:\n " + String.join(",", header)
					+ "\n\nA header like this one works:\n Last Name,First Name,NetID,Email Address");
		}

		return columns;
	}

	/*
	 * True when a header names a NetID column. A generic "ID" header is matched
	 * separately, as a whole word so that a column such as "Resident Hall" is not
	 * mistaken for one, and only used when there is no NetID column.
	 */
	private static boolean isNetIdHeader(String text) {
		return text.contains("netid")
				|| text.contains("net id")
				|| text.contains("username");
	}

	/*
	 * Splits one CSV line, honouring double-quoted fields so that a value may contain a
	 * comma. A doubled quote inside a quoted field is a literal quote.
	 *
	 * Note: a field containing a line break is not supported - each record must be on
	 * one line.
	 */
	private static List<String> splitCsvLine(String line) {
		List<String> fields = new ArrayList<String>();
		StringBuilder field = new StringBuilder();
		boolean inQuotes = false;

		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);

			if (inQuotes) {
				if (c != '"') {
					field.append(c);
				} else if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
					field.append('"');
					i++; // consume the escaping quote
				} else {
					inQuotes = false;
				}
			} else if (c == '"') {
				inQuotes = true;
			} else if (c == ',') {
				fields.add(field.toString().trim());
				field.setLength(0);
			} else {
				field.append(c);
			}
		}
		fields.add(field.toString().trim());

		return fields;
	}

	/*
	 * Returns the field at the given column, or "" when the row is short or the column
	 * is absent. Short rows are common in hand-edited exports and must not abort the read.
	 */
	private static String fieldAt(List<String> fields, int column) {
		if (column < 0 || column >= fields.size()) {
			return "";
		}
		return fields.get(column);
	}

	/*
	 * Builds the label shown to the user for a row that could not be imported.
	 */
	private static String describeRow(int rowNumber, String firstName, String lastName) {
		String name = (firstName + ' ' + lastName).trim();
		if (name.isEmpty()) {
			return "Row " + rowNumber;
		}
		return "Row " + rowNumber + " (" + name + ")";
	}

	/*
	 * Spreadsheets frequently prefix a UTF-8 export with a byte order mark, which would
	 * otherwise become part of the first header cell and stop it matching.
	 */
	private static String stripBom(String line) {
		if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
			return line.substring(1);
		}
		return line;
	}
}
