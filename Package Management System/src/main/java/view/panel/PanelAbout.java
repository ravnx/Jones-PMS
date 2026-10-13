package view.panel;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.JEditorPane;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.event.HyperlinkEvent;

/**
 * What the program is, which version is running, who wrote it, and who to contact.
 */
public class PanelAbout extends JPanel {

	private static final long serialVersionUID = 4418925316045286310L;

	private static final Logger logger = Logger.getLogger(PanelAbout.class.getName());

	private static final String NAME = "Package Management System";
	private static final String MAINTAINER = "Michael Palmer";
	private static final String CONTACT = "michaelp@gmail.com";
	private static final String SOURCE = "https://github.com/ravnx/Jones-PMS";

	public PanelAbout() {
		this(runningVersion());
	}

	PanelAbout(String version) {
		setLayout(new BorderLayout());
		setBorder(new EmptyBorder(10, 10, 10, 10));

		JEditorPane text = new JEditorPane("text/html", html(version));
		text.setEditable(false);
		text.setOpaque(false);
		// Use the normal Swing font rather than the HTML default
		text.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
		text.setFont(UIManager.getFont("Label.font"));
		text.addHyperlinkListener(event -> {
			if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
				open(event.getDescription());
			}
		});
		// Lay the text out at its width first, or its preferred height comes out too short
		text.setSize(new Dimension(400, Short.MAX_VALUE));
		text.setPreferredSize(new Dimension(400, text.getPreferredSize().height));
		add(text, BorderLayout.NORTH);
	}

	/**
	 * Show the About page in a dialog, for the About item in the macOS application menu
	 * @param parent			Component the dialog is shown over
	 */
	public static void showDialog(Component parent) {
		JOptionPane.showMessageDialog(parent, new PanelAbout(), "About " + NAME, JOptionPane.PLAIN_MESSAGE);
	}

	/**
	 * @return					The version in the jar manifest, or null when run from an IDE
	 */
	static String runningVersion() {
		return PanelAbout.class.getPackage().getImplementationVersion();
	}

	static String html(String version) {
		return "<html><body>"
				+ "<h2>" + NAME + "</h2>"
				+ "<p>" + (version == null ? "Development build" : "Version " + version) + "</p>"
				+ "<p>Keeps track of the packages that arrive at the Jones College Mail Room. It"
				+ " prints a label for each package, emails the student when one arrives, sends"
				+ " reminders until it is picked up, and checks it out when the label is scanned.</p>"
				+ "<p>Originally written in 2014 by Navin Pathak, with thanks to Christopher Henderson"
				+ " and the members of Jones 3rd South who tested it.</p>"
				+ "<p><b>Questions, problems or ideas?</b><br>"
				+ "The program is maintained by " + MAINTAINER + ".<br>"
				+ "Email <a href=\"" + contactLink(version) + "\">" + CONTACT + "</a> and say which"
				+ " version you have.</p>"
				+ "<p>Releases and source code: <a href=\"" + SOURCE + "\">" + SOURCE + "</a></p>"
				+ "</body></html>";
	}

	/**
	 * @return					A mailto link whose subject names the program and version
	 */
	static String contactLink(String version) {
		String subject = version == null ? NAME : NAME + " " + version;
		return "mailto:" + CONTACT + "?subject=" + subject.replace(" ", "%20");
	}

	private static void open(String link) {
		try {
			URI uri = URI.create(link);
			Desktop desktop = Desktop.getDesktop();
			if ("mailto".equals(uri.getScheme()) && desktop.isSupported(Desktop.Action.MAIL)) {
				desktop.mail(uri);
			} else {
				desktop.browse(uri);
			}
		} catch (IOException | RuntimeException e) {
			logger.log(Level.WARNING, "Could not open " + link, e);
		}
	}
}
