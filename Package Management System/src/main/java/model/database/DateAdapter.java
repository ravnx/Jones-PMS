package model.database;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.Locale;

import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

/**
 * Stores dates in person files as ISO-8601 UTC, e.g. "2025-04-23T23:28:40Z".
 *
 * Earlier versions let Gson write dates as US display text, whose exact form depends on
 * the Java version that wrote it: Java 8 wrote "Apr 23, 2025 6:28:40 PM", Java 9-19
 * added a comma after the year, and Java 20+ puts a narrow no-break space (U+202F)
 * before "PM". Gson only parses the form the running Java writes, so upgrading Java
 * could leave every existing file unreadable. All of those forms are still accepted
 * here, read in the local time zone they were written in.
 */
class DateAdapter extends TypeAdapter<Date> {

	private static final String[] LEGACY_PATTERNS = {
		"MMM d, yyyy, h:mm:ss a",	// Java 9 and later
		"MMM d, yyyy h:mm:ss a",	// Java 8
	};

	@Override
	public void write(JsonWriter out, Date date) throws IOException {
		if (date == null) {
			out.nullValue();
		} else {
			out.value(date.toInstant().toString());
		}
	}

	@Override
	public Date read(JsonReader in) throws IOException {
		JsonToken token = in.peek();
		if (token == JsonToken.NULL) {
			in.nextNull();
			return null;
		}
		if (token == JsonToken.NUMBER) {
			return new Date(in.nextLong());
		}
		return parse(in.nextString());
	}

	static Date parse(String text) {
		try {
			return Date.from(Instant.parse(text));
		} catch (DateTimeParseException e) {
			// not ISO-8601 - an older file
		}

		// Java 20+ uses narrow and regular no-break spaces; older versions a plain space
		String normalised = text.replace('\u202f', ' ').replace('\u00a0', ' ').trim();
		for (String pattern : LEGACY_PATTERNS) {
			SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
			format.setLenient(false);
			try {
				return format.parse(normalised);
			} catch (ParseException e) {
				// try the next form
			}
		}
		throw new JsonSyntaxException("Unrecognised date: " + text);
	}
}
