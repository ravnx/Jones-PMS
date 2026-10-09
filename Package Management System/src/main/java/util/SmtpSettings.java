package util;

/**
 * The mail server the program sends through: host, port and connection security.
 * Stored in config.properties as email.smtp_host, email.smtp_port and
 * email.smtp_security. Any value that is missing or unreadable falls back to the
 * Gmail default, so existing installs keep working without these keys.
 */
public final class SmtpSettings {

	public enum Security {
		STARTTLS("STARTTLS", 587),
		SSL("SSL/TLS", 465),
		NONE("None", 25);

		private final String label;
		private final int defaultPort;

		Security(String label, int defaultPort) {
			this.label = label;
			this.defaultPort = defaultPort;
		}

		public int getDefaultPort() {
			return defaultPort;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	public static final String HOST_KEY = "email.smtp_host";
	public static final String PORT_KEY = "email.smtp_port";
	public static final String SECURITY_KEY = "email.smtp_security";

	public static final SmtpSettings GMAIL = new SmtpSettings("smtp.gmail.com", 587, Security.STARTTLS);

	private final String host;
	private final int port;
	private final Security security;

	public SmtpSettings(String host, int port, Security security) {
		this.host = host;
		this.port = port;
		this.security = security;
	}

	/**
	 * Builds settings from stored strings, using the Gmail default for any value that
	 * is missing or invalid.
	 */
	public static SmtpSettings parse(String host, String port, String security) {
		String h = (host == null || host.trim().isEmpty()) ? GMAIL.host : host.trim();
		Integer p = parsePort(port);
		Security s = GMAIL.security;
		if (security != null) {
			try {
				s = Security.valueOf(security.trim().toUpperCase());
			} catch (IllegalArgumentException e) {
				// unknown value - keep the default
			}
		}
		return new SmtpSettings(h, p == null ? GMAIL.port : p, s);
	}

	public static SmtpSettings load(PropertyHandler props) {
		return parse(props.getProperty(HOST_KEY), props.getProperty(PORT_KEY),
				props.getProperty(SECURITY_KEY));
	}

	public void save(PropertyHandler props) {
		props.setProperty(HOST_KEY, host);
		props.setProperty(PORT_KEY, String.valueOf(port));
		props.setProperty(SECURITY_KEY, security.name());
	}

	/**
	 * @return		The port number, or null if the text is not a valid port
	 */
	public static Integer parsePort(String text) {
		if (text == null) {
			return null;
		}
		try {
			int port = Integer.parseInt(text.trim());
			return (port >= 1 && port <= 65535) ? port : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	public String getHost() {
		return host;
	}

	public int getPort() {
		return port;
	}

	public Security getSecurity() {
		return security;
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof SmtpSettings)) {
			return false;
		}
		SmtpSettings other = (SmtpSettings) o;
		return host.equalsIgnoreCase(other.host) && port == other.port && security == other.security;
	}

	@Override
	public int hashCode() {
		return host.toLowerCase().hashCode() * 31 + port * 7 + security.hashCode();
	}

	@Override
	public String toString() {
		return host + ":" + port + " (" + security + ")";
	}
}
