package update;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * A release version such as 2.1.0. A leading "v", as in GitHub tag names, is ignored,
 * and missing trailing parts count as zero, so "v2.1" equals "2.1.0".
 *
 * Only plain dotted numbers are versions. Anything else, such as "2.2.0-SNAPSHOT", is
 * rejected, so the updater never guesses at how an unusual version compares.
 */
public final class Version implements Comparable<Version> {

	private static final Pattern FORMAT = Pattern.compile("v?\\d+(\\.\\d+){0,3}");

	// Trailing zeros are dropped so that equal versions are equal and hash the same
	private final int[] parts;

	private Version(int[] parts) {
		this.parts = parts;
	}

	/**
	 * @param text			A version such as "2.1.0" or a tag name such as "v2.1.0"
	 * @throws IllegalArgumentException	if the text is not a plain dotted version
	 */
	public static Version parse(String text) {
		if (text == null || !FORMAT.matcher(text.trim()).matches()) {
			throw new IllegalArgumentException("Not a version: " + text);
		}
		String digits = text.trim();
		if (digits.startsWith("v")) {
			digits = digits.substring(1);
		}
		String[] pieces = digits.split("\\.");
		int[] parts = new int[pieces.length];
		for (int i = 0; i < pieces.length; i++) {
			// Too many digits for an int throws NumberFormatException, an IllegalArgumentException
			parts[i] = Integer.parseInt(pieces[i]);
		}
		int length = parts.length;
		while (length > 1 && parts[length - 1] == 0) {
			length--;
		}
		return new Version(Arrays.copyOf(parts, length));
	}

	@Override
	public int compareTo(Version other) {
		int length = Math.max(parts.length, other.parts.length);
		for (int i = 0; i < length; i++) {
			int mine = i < parts.length ? parts[i] : 0;
			int theirs = i < other.parts.length ? other.parts[i] : 0;
			if (mine != theirs) {
				return Integer.compare(mine, theirs);
			}
		}
		return 0;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof Version && Arrays.equals(parts, ((Version) other).parts);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(parts);
	}

	@Override
	public String toString() {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < Math.max(parts.length, 3); i++) {
			if (i > 0) {
				text.append('.');
			}
			text.append(i < parts.length ? parts[i] : 0);
		}
		return text.toString();
	}
}
