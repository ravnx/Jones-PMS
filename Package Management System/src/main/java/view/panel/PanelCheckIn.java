package view.panel;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

import com.jgoodies.forms.factories.FormFactory;
import com.jgoodies.forms.layout.ColumnSpec;
import com.jgoodies.forms.layout.FormLayout;
import com.jgoodies.forms.layout.RowSpec;

import util.Person;
import view.IViewToModelAdaptor;
import view.component.PersonComboBox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

public class PanelCheckIn extends JPanel {

	private static final long serialVersionUID = -5330361979358721465L;
	
	private PersonComboBox comboBoxStudentName;
	private JTextField textFieldComment;
	private JFrame frame;
	private IViewToModelAdaptor modelAdaptor;

	/**
	 * Create the panel.
	 */
	public PanelCheckIn(JFrame frame, IViewToModelAdaptor modelAdaptor) {
		
		this.frame = frame;
		this.modelAdaptor = modelAdaptor;

		setLayout(new FormLayout(new ColumnSpec[] {
				ColumnSpec.decode("default:grow"),
				FormFactory.LABEL_COMPONENT_GAP_COLSPEC,
				FormFactory.DEFAULT_COLSPEC,
				FormFactory.RELATED_GAP_COLSPEC,
				ColumnSpec.decode("pref:grow"),},
			new RowSpec[] {
				RowSpec.decode("36px:grow"),
				FormFactory.DEFAULT_ROWSPEC,
				RowSpec.decode("20px"),
				FormFactory.RELATED_GAP_ROWSPEC,
				FormFactory.DEFAULT_ROWSPEC,
				FormFactory.DEFAULT_ROWSPEC,
				FormFactory.RELATED_GAP_ROWSPEC,
				FormFactory.DEFAULT_ROWSPEC,
				FormFactory.RELATED_GAP_ROWSPEC,
				FormFactory.DEFAULT_ROWSPEC,
				FormFactory.RELATED_GAP_ROWSPEC,
				RowSpec.decode("default:grow"),}));
		
		JLabel lblStudent = new JLabel("Student:");
		add(lblStudent, "3, 2, left, bottom");
		
		comboBoxStudentName = new PersonComboBox();

		add(comboBoxStudentName, "3, 3, fill, top");

		JLabel lblNewLabel = new JLabel("Comment:");
		add(lblNewLabel, "3, 5, left, bottom");
		
		textFieldComment = new JTextField();
		add(textFieldComment, "3, 6, fill, default");
		textFieldComment.setColumns(30);
		textFieldComment.setToolTipText("Optional comment to be displayed with email notification");
		
		JButton btnConfirmCheckIn = new JButton("Confirm");
		btnConfirmCheckIn.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent arg0) {
				checkInSelection();
			}
		});
		add(btnConfirmCheckIn, "3, 8, center, default");
		
	}
	
	/**
	 * Get a list of person objects from the database
	 */
	private ArrayList<Person> getPersonList() {
		ArrayList<Person> personList = modelAdaptor.getPersonList("");
		
		// sort
		Collections.sort(personList, new Comparator<Person>() {
			public int compare(Person p1, Person p2) {
				// last name
				if(p1.getLastName().toLowerCase() != p2.getLastName().toLowerCase()) {
					return p1.getLastName().toLowerCase().compareTo(p2.getLastName().toLowerCase());
				} 
				// first name
				if (p1.getFirstName().toLowerCase() != p2.getFirstName().toLowerCase()) {
					return p1.getFirstName().toLowerCase().compareTo(p2.getFirstName().toLowerCase());
				} 
				// personID
				return p1.getPersonID().toLowerCase().compareTo(p2.getPersonID().toLowerCase());
			}
		});
		
		return personList;
	}
	
	/**
	 * Check in the selected person
	 */

	private void checkInSelection() {

		Person owner = comboBoxStudentName.getSelectedPerson();

		if (owner == null) {
			JOptionPane.showMessageDialog(frame, "Please choose a name from the provided list.",
					"Invalid Person", JOptionPane.WARNING_MESSAGE);
			comboBoxStudentName.getEditor().getEditorComponent().requestFocus();
			return;
		}

		// Check into the database. A negative ID means the package was not stored, so
		// there is nothing to print a label for or send a notification about - printing
		// one anyway would put a barcode on the box that belongs to a different package.
		long pkgID = modelAdaptor.checkInPackage(owner.getPersonID(), textFieldComment.getText());

		if (pkgID < 0) {
			JOptionPane.showMessageDialog(frame,
					"The package could not be checked in, and was not saved.\n"
					+ "No label was printed and no email was sent.\n\n"
					+ "Please try again.",
					"Check In Failed", JOptionPane.ERROR_MESSAGE);
			resetFields();
			return;
		}

		boolean printed = modelAdaptor.printLabel(pkgID);
		boolean notified = modelAdaptor.sendPackageNotification(owner.getPersonID(), pkgID);

		// Report what actually happened. Previously each failure got its own dialog and
		// then success was announced regardless, so both could fail and still say it worked.
		StringBuilder message = new StringBuilder();
		message.append("Package for ").append(owner.getFullName()).append(" was checked in.\n\n");
		message.append(printed
				? " - Label printed\n"
				: " - Label NOT printed. Reprint it from the Packages tab of the admin panel.\n");
		message.append(notified
				? " - Notification emailed\n"
				: " - Notification NOT sent. Resend it from the Packages tab of the admin panel.\n");

		boolean allGood = printed && notified;
		JOptionPane.showMessageDialog(frame, message.toString(),
				allGood ? "Success" : "Checked In With Problems",
				allGood ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);

		resetFields();
	}

	/**
	 * Clears the entry fields and returns focus to the student box
	 */
	private void resetFields() {
		textFieldComment.setText("");
		comboBoxStudentName.getEditor().setItem("");
		comboBoxStudentName.getEditor().getEditorComponent().requestFocus();
	}

	public void init() {
		comboBoxStudentName.removeAllItems();
		comboBoxStudentName.setPersonList(getPersonList());
		comboBoxStudentName.getEditor().setItem("");
	}
	
}
