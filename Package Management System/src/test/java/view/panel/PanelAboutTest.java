package view.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.Test;

/**
 * The About page tells the mail room which version they have and who to contact.
 */
class PanelAboutTest {

	@Test
	void showsTheVersion() {
		assertTrue(PanelAbout.html("2.4.0").contains("Version 2.4.0"));
	}

	@Test
	void aBuildWithoutAVersionSaysSo() {
		assertTrue(PanelAbout.html(null).contains("Development build"));
	}

	@Test
	void creditsTheOriginalAuthor() {
		assertTrue(PanelAbout.html("2.4.0").contains("Navin Pathak"));
	}

	@Test
	void namesTheMaintainerAndHowToReachThem() {
		String html = PanelAbout.html("2.4.0");

		assertTrue(html.contains("Michael Palmer"));
		assertTrue(html.contains("href=\"" + PanelAbout.contactLink("2.4.0") + "\""));
	}

	@Test
	void theContactEmailSaysWhichVersionItIsAbout() {
		URI link = URI.create(PanelAbout.contactLink("2.4.0"));

		assertEquals("mailto", link.getScheme());
		assertEquals("michaelp@gmail.com?subject=Package Management System 2.4.0", link.getSchemeSpecificPart());
	}

	@Test
	void theContactEmailWorksWithoutAVersion() {
		URI link = URI.create(PanelAbout.contactLink(null));

		assertEquals("michaelp@gmail.com?subject=Package Management System", link.getSchemeSpecificPart());
	}
}
