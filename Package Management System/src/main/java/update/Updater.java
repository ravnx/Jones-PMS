package update;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;

import util.PropertyHandler;

/**
 * Offers the newest GitHub release when the program starts, and installs it if the user
 * agrees.
 *
 * Nothing here may keep the mail room from working: with no network, an error from
 * GitHub, or a bad download, the problem is logged and the program starts with the
 * version it has.
 */
public final class Updater {

	/** The version the user chose to skip, in config.properties */
	static final String SKIP_PROPERTY = "update_skip_version";

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final Logger logger = Logger.getLogger(Updater.class.getName());

	private enum Choice { UPDATE, LATER, SKIP }

	private Updater() { }

	/**
	 * Checks for and offers an update. Called once on the event thread before any window
	 * is shown; returns when the program should carry on starting, or exits after a
	 * successful update has started the new version.
	 */
	public static void checkAtStartup() {
		try {
			check();
		} catch (RuntimeException e) {
			logger.log(Level.WARNING, "Update check failed", e);
		}
	}

	private static void check() {
		// The version comes from the jar manifest, so it is null when run from an IDE
		String running = Updater.class.getPackage().getImplementationVersion();
		CodeSource source = Updater.class.getProtectionDomain().getCodeSource();
		Path jar = jarFrom(source == null ? null : source.getLocation());
		String problem = eligibilityProblem(running, jar);
		if (problem != null) {
			logger.info("Not checking for updates: " + problem);
			return;
		}

		ReleaseInfo latest;
		try {
			latest = new GitHubReleaseClient(GitHubReleaseClient.REPOSITORY, TIMEOUT).fetchLatest();
		} catch (IOException e) {
			logger.info("Could not check for updates: " + e.getMessage());
			return;
		}

		PropertyHandler props = PropertyHandler.getInstance();
		if (!shouldOffer(Version.parse(running), latest, props.getProperty(SKIP_PROPERTY))) {
			logger.info("No update offered; running " + running + ", newest release " + latest.version());
			return;
		}

		switch (askUser(running, latest)) {
		case UPDATE:
			install(jar, running, latest);
			break;
		case SKIP:
			logger.info("Skipping version " + latest.version());
			props.setProperty(SKIP_PROPERTY, latest.version().toString());
			break;
		case LATER:
			break;
		}
	}

	/**
	 * @return	whether to offer the release: it is newer than the running version and
	 *			is not the version the user skipped
	 */
	static boolean shouldOffer(Version running, ReleaseInfo latest, String skipped) {
		if (latest.version().compareTo(running) <= 0) {
			return false;
		}
		if (skipped == null) {
			return true;
		}
		try {
			return !Version.parse(skipped).equals(latest.version());
		} catch (IllegalArgumentException e) {
			return true;
		}
	}

	/**
	 * @param runningVersion	The running version from the jar manifest, or null
	 * @param jar				The jar the program is running from, or null
	 * @return					why this copy cannot update itself, or null if it can
	 */
	static String eligibilityProblem(String runningVersion, Path jar) {
		if (runningVersion == null) {
			return "development build";
		}
		try {
			Version.parse(runningVersion);
		} catch (IllegalArgumentException e) {
			return "unrecognised version " + runningVersion;
		}
		if (jar == null) {
			return "not running from a jar";
		}
		if (!Files.isWritable(jar.getParent())) {
			return "cannot write to " + jar.getParent();
		}
		return null;
	}

	/**
	 * @param location	Where the program's classes were loaded from
	 * @return			that location as a jar file, or null if it is not one
	 */
	static Path jarFrom(URL location) {
		if (location == null) {
			return null;
		}
		try {
			// Through a URI, so "%20" in folder names becomes a space again
			Path path = Paths.get(location.toURI());
			return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar") ? path : null;
		} catch (URISyntaxException | IllegalArgumentException | FileSystemNotFoundException e) {
			return null;
		}
	}

	private static Choice askUser(String running, ReleaseInfo latest) {
		JTextArea notes = new JTextArea(latest.notes().isEmpty() ? "(No release notes)" : latest.notes(), 15, 60);
		notes.setEditable(false);
		notes.setLineWrap(true);
		notes.setWrapStyleWord(true);
		notes.setCaretPosition(0);

		JPanel panel = new JPanel(new BorderLayout(0, 8));
		panel.add(new JLabel("Version " + latest.version() + " is available. You have version "
				+ running + "."), BorderLayout.NORTH);
		panel.add(new JScrollPane(notes), BorderLayout.CENTER);
		panel.add(new JLabel("Students, packages and settings are kept when you update."), BorderLayout.SOUTH);

		String[] options = { "Update now", "Later", "Skip this version" };
		int answer = JOptionPane.showOptionDialog(null, panel, "Update available",
				JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);
		if (answer == 0) {
			return Choice.UPDATE;
		}
		if (answer == 2) {
			return Choice.SKIP;
		}
		// "Later", or the dialog was closed
		return Choice.LATER;
	}

	private static void install(Path jar, String running, ReleaseInfo latest) {
		UpdateInstaller installer = new UpdateInstaller(jar, TIMEOUT);
		AtomicBoolean cancelled = new AtomicBoolean();

		JProgressBar bar = new JProgressBar(0, 100);
		bar.setStringPainted(true);
		JButton cancel = new JButton("Cancel");
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		buttons.add(cancel);
		JPanel panel = new JPanel(new BorderLayout(0, 8));
		panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		panel.add(new JLabel("Downloading version " + latest.version() + "..."), BorderLayout.NORTH);
		panel.add(bar, BorderLayout.CENTER);
		panel.add(buttons, BorderLayout.SOUTH);

		JDialog dialog = new JDialog((Frame) null, "Updating", true);
		dialog.setContentPane(panel);
		dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		dialog.pack();
		dialog.setLocationRelativeTo(null);

		SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
			@Override
			protected Void doInBackground() throws IOException {
				installer.install(latest, new UpdateInstaller.Progress() {
					public void downloaded(long bytes, long total) {
						int percent = (int) (bytes * 100 / Math.max(total, 1));
						SwingUtilities.invokeLater(() -> {
							bar.setValue(percent);
							// The checks and swap that follow take moments and cannot be stopped
							if (bytes >= total) {
								cancel.setEnabled(false);
							}
						});
					}

					public boolean isCancelled() {
						return cancelled.get();
					}
				});
				return null;
			}

			@Override
			protected void done() {
				dialog.dispose();
			}
		};
		cancel.addActionListener(e -> {
			cancelled.set(true);
			// Also interrupts a download stalled waiting on the network
			worker.cancel(true);
		});

		worker.execute();
		// Modal: returns once done() has closed the dialog
		dialog.setVisible(true);

		if (cancelled.get()) {
			logger.info("Update to " + latest.version() + " cancelled");
			return;
		}
		try {
			worker.get();
		} catch (InterruptedException | ExecutionException e) {
			Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
			logger.log(Level.WARNING, "Update to " + latest.version() + " failed", cause);
			JOptionPane.showMessageDialog(null, "The update could not be installed. Continuing with version "
					+ running + ".\n\n" + cause.getMessage(), "Update failed", JOptionPane.WARNING_MESSAGE);
			return;
		}

		try {
			installer.relaunch();
		} catch (IOException e) {
			logger.log(Level.WARNING, "Could not restart after updating", e);
			JOptionPane.showMessageDialog(null, "Version " + latest.version() + " is installed, but the program "
					+ "could not restart itself.\nPlease open the Package Management System again.",
					"Update installed", JOptionPane.INFORMATION_MESSAGE);
		}
		System.exit(0);
	}
}
