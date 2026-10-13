package update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VersionTest {

	@Test
	void ignoresTheLeadingVOfATagName() {
		assertEquals(Version.parse("2.1.0"), Version.parse("v2.1.0"));
	}

	@Test
	void missingPartsCountAsZero() {
		assertEquals(Version.parse("2.1"), Version.parse("2.1.0"));
		assertEquals(Version.parse("2.1").hashCode(), Version.parse("2.1.0").hashCode());
		assertEquals(0, Version.parse("2").compareTo(Version.parse("2.0.0")));
	}

	@Test
	void comparesNumbersNotText() {
		assertTrue(Version.parse("2.10.0").compareTo(Version.parse("2.9.0")) > 0);
		assertTrue(Version.parse("3.0.0").compareTo(Version.parse("2.99.99")) > 0);
		assertTrue(Version.parse("2.1.1").compareTo(Version.parse("2.1.0")) > 0);
		assertTrue(Version.parse("2.1.0").compareTo(Version.parse("2.1.1")) < 0);
	}

	@Test
	void printsAtLeastThreeParts() {
		assertEquals("2.0.0", Version.parse("v2").toString());
		assertEquals("2.1.0", Version.parse("2.1").toString());
		assertEquals("2.1.3", Version.parse("2.1.3").toString());
		assertEquals("2.1.3.4", Version.parse("2.1.3.4").toString());
	}

	@Test
	void acceptsSurroundingWhitespace() {
		assertEquals(Version.parse("2.1.0"), Version.parse(" v2.1.0\n"));
	}

	@Test
	void rejectsAnythingThatIsNotAPlainVersion() {
		String[] bad = { null, "", " ", "v", "latest", "2.1.0-beta", "2.2.0-SNAPSHOT", "2..1",
				"2.1.", ".2.1", "1.2.3.4.5", "99999999999" };
		for (String text : bad) {
			assertThrows(IllegalArgumentException.class, () -> Version.parse(text), "accepted: " + text);
		}
	}
}
