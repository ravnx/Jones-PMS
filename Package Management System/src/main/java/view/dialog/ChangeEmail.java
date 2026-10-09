package view.dialog;

import java.awt.BorderLayout;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import com.jgoodies.forms.factories.FormFactory;
import com.jgoodies.forms.layout.ColumnSpec;
import com.jgoodies.forms.layout.FormLayout;
import com.jgoodies.forms.layout.RowSpec;

import util.SmtpSettings;

import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;

/**
 * Dialog that allows the user to change an email.
 * @author Navin
 *
 */
public class ChangeEmail extends JDialog {

	private static final long serialVersionUID = -8304864601186531112L;
	
	private final JPanel panelEditEmail = new JPanel();
	private JTextField textFieldNewEmailName;
	private JTextField textFieldNewEmail;
	private JPasswordField passwordNew;
	private String[] result;

	// mail server settings, hidden unless the admin opens them
	private static final String SHOW_SERVER = "\u25B8 Mail server settings (advanced)";
	private static final String HIDE_SERVER = "\u25BE Mail server settings (advanced)";
	private final JPanel panelServer = new JPanel();
	private JTextField textFieldHost;
	private JTextField textFieldPort;
	private JComboBox<SmtpSettings.Security> comboSecurity;
	private SmtpSettings smtpResult;


	/**
	 * Create the dialog with specified previous Alias, email and mail server
	 */
	public ChangeEmail(JFrame frame, String oldName, String oldEmail, SmtpSettings oldSmtp) {
		super(frame,true);
		setTitle("Change Email");
		setSize(550, 250);
		setLocationRelativeTo(frame);
		panelEditEmail.setLayout(new FormLayout(new ColumnSpec[] {
				FormFactory.RELATED_GAP_COLSPEC,
				ColumnSpec.decode("pref:grow"),
				FormFactory.RELATED_GAP_COLSPEC,
				FormFactory.DEFAULT_COLSPEC,
				FormFactory.RELATED_GAP_COLSPEC,
				ColumnSpec.decode("max(90dlu;default)"),
				FormFactory.RELATED_GAP_COLSPEC,
				FormFactory.DEFAULT_COLSPEC,
				FormFactory.RELATED_GAP_COLSPEC,
				ColumnSpec.decode("pref:grow"),},
			new RowSpec[] {
				RowSpec.decode("default:grow"),
				FormFactory.PREF_ROWSPEC,
				FormFactory.RELATED_GAP_ROWSPEC,
				FormFactory.DEFAULT_ROWSPEC,
				FormFactory.DEFAULT_ROWSPEC,
				FormFactory.DEFAULT_ROWSPEC,
				FormFactory.RELATED_GAP_ROWSPEC,
				FormFactory.DEFAULT_ROWSPEC,
				FormFactory.DEFAULT_ROWSPEC,
				FormFactory.RELATED_GAP_ROWSPEC,
				FormFactory.DEFAULT_ROWSPEC,
				RowSpec.decode("default:grow"),}));
		
		// Warning label
		JLabel lblNote = new JLabel("<html><center>This email address should be specifically created for this purpose.<br><b>DO NOT use a personal/business address.</b></center></html>");
		panelEditEmail.add(lblNote, "4, 2, 5, 1, center, default");
		
		// New Name Alias field
		JLabel lblChangeEmailName = new JLabel("Alias");
		panelEditEmail.add(lblChangeEmailName, "4, 4, left, default");
		
		textFieldNewEmailName = new JTextField();
		textFieldNewEmailName.setToolTipText(
				"The name displayed in the \"From:\" field. For example, Jones Mail Room");
		textFieldNewEmailName.setColumns(10);
		textFieldNewEmailName.setText(oldName);
		textFieldNewEmailName.addFocusListener(new FocusAdapter() {
			public void focusGained(FocusEvent arg0) {
	   	    	SwingUtilities.invokeLater( new Runnable() {
	    				@Override
	    				public void run() {
	    					textFieldNewEmailName.selectAll();		
	    				}
	   	    	});
			}
		});
		panelEditEmail.add(textFieldNewEmailName, "6, 4, fill, default");

		// New Email Address field
		JLabel lblNewEmail = new JLabel("Email");
		panelEditEmail.add(lblNewEmail, "4, 5, left, default");
		
		textFieldNewEmail = new JTextField();
		textFieldNewEmail.setToolTipText("The email address from which all notifications and reminders will be sent");
		textFieldNewEmail.setColumns(10);
		textFieldNewEmail.setText(oldEmail);
		textFieldNewEmail.addFocusListener(new FocusAdapter() {
			public void focusGained(FocusEvent arg0) {
	   	    	SwingUtilities.invokeLater( new Runnable() {
	    				@Override
	    				public void run() {
	    					textFieldNewEmail.selectAll();		
	    				}
	   	    	});
			}
		});
		panelEditEmail.add(textFieldNewEmail, "6, 5, fill, default");
		
		// New Password Field
		JLabel lblNewPassword = new JLabel("Password");
		panelEditEmail.add(lblNewPassword, "4, 6, left, default");
		
		passwordNew = new JPasswordField();
		passwordNew.setToolTipText("The password for the email address account");
		panelEditEmail.add(passwordNew, "6, 6, fill, default");

		// Mail server settings - collapsed by default so they are only changed on purpose
		buildServerPanel(oldSmtp == null ? SmtpSettings.GMAIL : oldSmtp);
		final JButton btnToggleServer = new JButton(SHOW_SERVER);
		btnToggleServer.setBorderPainted(false);
		btnToggleServer.setContentAreaFilled(false);
		btnToggleServer.setFocusPainted(false);
		btnToggleServer.setToolTipText("Only change these if you have been told to. The defaults are for Gmail.");
		btnToggleServer.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				boolean show = !panelServer.isVisible();
				panelServer.setVisible(show);
				btnToggleServer.setText(show ? HIDE_SERVER : SHOW_SERVER);
				fitToContents();
			}
		});
		panelEditEmail.add(btnToggleServer, "4, 8, 3, 1, left, default");
		panelEditEmail.add(panelServer, "4, 9, 3, 1, fill, default");
		
		
		// Submit changes
		JButton btnSubmitEmailChanges = new JButton("Submit");
		btnSubmitEmailChanges.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent arg0) {
				String newEmailName = textFieldNewEmailName.getText();
				String newEmailAddress = textFieldNewEmail.getText();
				String newPassword = new String(passwordNew.getPassword());
				String newHost = textFieldHost.getText().trim();
				Integer newPort = SmtpSettings.parsePort(textFieldPort.getText());
				
				// If all fields are nonempty and the passwords match, set result and dispose
				if(newEmailName.length() > 0 &&
						newEmailAddress.length() > 0 &&
						newPassword.length() > 0) {

					if (newHost.isEmpty() || newPort == null) {
						if (!panelServer.isVisible()) {
							btnToggleServer.doClick();
						}
						JOptionPane.showMessageDialog(ChangeEmail.this,
								"Please enter a mail server and a port number between 1 and 65535.");
						return;
					}
					smtpResult = new SmtpSettings(newHost, newPort,
							(SmtpSettings.Security) comboSecurity.getSelectedItem());
						
					//TODO Check and see if it works with the server
					result = new String[3];
					result[0] = newEmailName;
					result[1] = newEmailAddress;
					result[2] = newPassword;
					
					setVisible(false);
					dispose();
				} else {
					// send message saying that all fields must be filled
					JOptionPane.showMessageDialog(null, "Please fill out all fields.");
				}
			}
		});
		getContentPane().setLayout(new BorderLayout(0, 0));
		
		panelEditEmail.add(btnSubmitEmailChanges, "6, 11, center, default");
		getContentPane().add(panelEditEmail);

		// open it straight away when a non-Gmail server is in use, so it is not missed
		if (oldSmtp != null && !oldSmtp.equals(SmtpSettings.GMAIL)) {
			btnToggleServer.doClick();
		}
	}

	/*
	 * Host, port and security fields, plus a button to put the Gmail defaults back.
	 */
	private void buildServerPanel(SmtpSettings smtp) {
		panelServer.setLayout(new FormLayout(new ColumnSpec[] {
				FormFactory.DEFAULT_COLSPEC,
				FormFactory.RELATED_GAP_COLSPEC,
				ColumnSpec.decode("max(90dlu;default):grow"),},
			new RowSpec[] {
				FormFactory.DEFAULT_ROWSPEC,
				FormFactory.RELATED_GAP_ROWSPEC,
				FormFactory.DEFAULT_ROWSPEC,
				FormFactory.DEFAULT_ROWSPEC,
				FormFactory.DEFAULT_ROWSPEC,
				FormFactory.RELATED_GAP_ROWSPEC,
				FormFactory.DEFAULT_ROWSPEC,}));

		JLabel lblServerNote = new JLabel("<html><i>Only change these if you have<br>been told to. Defaults are for Gmail.</i></html>");
		panelServer.add(lblServerNote, "1, 1, 3, 1, left, default");

		panelServer.add(new JLabel("Server"), "1, 3, left, default");
		textFieldHost = new JTextField(smtp.getHost());
		textFieldHost.setToolTipText("The SMTP server to send through, for example smtp.gmail.com");
		panelServer.add(textFieldHost, "3, 3, fill, default");

		panelServer.add(new JLabel("Port"), "1, 4, left, default");
		textFieldPort = new JTextField(String.valueOf(smtp.getPort()));
		textFieldPort.setToolTipText("Usually 587 for STARTTLS or 465 for SSL/TLS");
		panelServer.add(textFieldPort, "3, 4, fill, default");

		panelServer.add(new JLabel("Security"), "1, 5, left, default");
		comboSecurity = new JComboBox<SmtpSettings.Security>(SmtpSettings.Security.values());
		comboSecurity.setSelectedItem(smtp.getSecurity());
		comboSecurity.addActionListener(new ActionListener() {
			private SmtpSettings.Security previous = smtp.getSecurity();
			public void actionPerformed(ActionEvent e) {
				// move the port along with the security type, unless it was set by hand
				SmtpSettings.Security selected = (SmtpSettings.Security) comboSecurity.getSelectedItem();
				Integer port = SmtpSettings.parsePort(textFieldPort.getText());
				if (port == null || port == previous.getDefaultPort()) {
					textFieldPort.setText(String.valueOf(selected.getDefaultPort()));
				}
				previous = selected;
			}
		});
		panelServer.add(comboSecurity, "3, 5, fill, default");

		JButton btnDefaults = new JButton("Use Gmail Defaults");
		btnDefaults.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				textFieldHost.setText(SmtpSettings.GMAIL.getHost());
				comboSecurity.setSelectedItem(SmtpSettings.GMAIL.getSecurity());
				textFieldPort.setText(String.valueOf(SmtpSettings.GMAIL.getPort()));
			}
		});
		panelServer.add(btnDefaults, "3, 7, right, default");

		panelServer.setVisible(false);
	}

	// grow or shrink the dialog when the server settings are shown or hidden
	private void fitToContents() {
		pack();
		setSize(Math.max(550, getWidth()), Math.max(250, getHeight()));
	}
	
	/**
	 * Opens the dialog and returns the new email information
	 * @return		0 - New Alias
	 * 				1 - New Email Address
	 * 				2 - New Password
	 */
	public String[] showDialog() {
		setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		setVisible(true);
		return result;
	}

	/**
	 * The mail server entered, once showDialog has returned a result
	 * @return		New mail server settings, or null if the dialog was cancelled
	 */
	public SmtpSettings getSmtpSettings() {
		return smtpResult;
	}
}
