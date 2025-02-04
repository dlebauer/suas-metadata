package controller;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.*;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import model.CalliopeData;
import model.cyverse.CyVerseConnectionManager;
import model.cyverse.ImageCollection;
import model.elasticsearch.ElasticSearchConnectionManager;
import model.settings.SettingsData;
import model.site.Site;
import model.threading.ErrorTask;
import org.controlsfx.control.HyperlinkLabel;
import org.controlsfx.control.NotificationPane;
import org.controlsfx.validation.ValidationSupport;
import org.controlsfx.validation.Validator;

import java.awt.*;
import java.io.IOException;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/**
 * Controller class for the program main view
 */
public class CalliopeViewController
{
	///
	/// FXML Bound fields start
	///

	// The username and password text fields
	@FXML
	public TextField txtUsername;
	@FXML
	public PasswordField txtPassword;

	// Check box to remember username
	@FXML
	public CheckBox cbxRememberUsername;

	// The hyperlink label with the register and forgot password options
	@FXML
	public HyperlinkLabel hypRegisterPassword;

	// The pane containing the login information
	@FXML
	public StackPane loginPane;
	// The background rectangle of the login window
	@FXML
	public Rectangle rctLoginBackground;

	// The login button to connect to CyVerse
	@FXML
	public Button btnLogin;

	@FXML
	public TabPane tabPane;

	// A reference to the home screen
	@FXML
	public AnchorPane homePane;

	@FXML
	public NotificationPane notificationPane;

	@FXML
	public Label lblInvalidCredentials;

	///
	/// FXML Bound fields end
	///

	// The preference key which will just be "Username"
	private static final String USERNAME_PREF = "username";

	// Guassian blur is used to hide the other buttons before logging in
	private final GaussianBlur backgroundBlur = new GaussianBlur();
	// The validator used to validate the username and password (aka ensure they're not empty!)
	private final ValidationSupport USER_PASS_VALIDATOR = new ValidationSupport();

	// Property used to detect if we are logging in or not
	private BooleanProperty loggingIn = new SimpleBooleanProperty(false);

	@FXML
	public void initialize()
	{
		// Whenever we get a new notification, make sure the buttons are scaled correctly
		this.notificationPane.setOnShowing(event ->
		{
			// Make sure the notification bar is there
			Node notificationBar = this.notificationPane.lookup(".notification-bar");
			if (notificationBar != null)
			{
				// Make sure the notification pane is there
				Node notificationPane = notificationBar.lookup(".pane");
				if (notificationPane != null)
				{
					// Make sure the button bar is there
					Node buttonBarNode = notificationPane.lookup(".button-bar");
					// Update each child button
					if (buttonBarNode instanceof ButtonBar)
					{
						// Grab the button bar
						ButtonBar buttonBar = (ButtonBar) buttonBarNode;
						buttonBar.getButtons().forEach(node ->
						{
							// Make sure the node inside is a button
							if (node instanceof Button)
							{
								// Set the buttons to be non-uniformly sized and their text to wrap
								ButtonBar.setButtonUniformSize(node, false);
								((Button) node).setWrapText(true);
							}
						});
						buttonBar.setButtonMinWidth(100);
					}

					// Make sure the label wraps
					Node label = notificationPane.lookup(".label");
					GridPane.setHgrow(label, Priority.NEVER);
					// If we did indeed get a label, make sure it wraps
					if (label instanceof Label)
						((Label) label).setWrapText(true);
				}
			}
		});

		// Grab the logged in property
		ReadOnlyBooleanProperty loggedIn = CalliopeData.getInstance().loggedInProperty();

		// Disable the main pane when not logged in
		this.tabPane.disableProperty().bind(loggedIn.not());

		// Blur the main pane when not logged in
		this.tabPane.effectProperty().bind(Bindings.when(loggedIn.not()).then(backgroundBlur).otherwise((GaussianBlur) null));

		// Bind the rectangles width and height to the login pane's width and height
		this.rctLoginBackground.widthProperty().bind(this.loginPane.widthProperty());
		this.rctLoginBackground.heightProperty().bind(this.loginPane.heightProperty());

		// Disable the login property if the username and password are empty, if we're logging in, or if the ES connection failed due to bad configuration
		this.btnLogin.disableProperty().bind(this.USER_PASS_VALIDATOR.invalidProperty().or(loggingIn).or(CalliopeData.getInstance().getSensitiveConfigurationManager().configurationValidProperty().not()));

		// Hide the login pane when logged in
		this.loginPane.visibleProperty().bind(loggedIn.not());

		// Register validators for username and password. This simply makes sure that they're both not empty
		this.USER_PASS_VALIDATOR.registerValidator(this.txtUsername, Validator.createEmptyValidator("Username cannot be empty!"));
		this.USER_PASS_VALIDATOR.registerValidator(this.txtPassword, Validator.createEmptyValidator("Password cannot be empty!"));

		// Ensure that the tabs always cover the top of the screen
		tabPane.tabMinWidthProperty().bind(tabPane.widthProperty().divide(tabPane.getTabs().size()).subtract(25));

		// Grab the stored username if the user had 'remember username' selected
		String storedUsername = CalliopeData.getInstance().getPreferences().get(USERNAME_PREF, "");

		// Load default username if it was stored
		if (!storedUsername.isEmpty())
		{
			this.txtUsername.setText(storedUsername);
			this.cbxRememberUsername.setSelected(true);
		}

		// If the user deselects the remember username box, remove the stored username
		this.cbxRememberUsername.selectedProperty().addListener((observable, oldValue, newValue) -> {
			if (!newValue)
				CalliopeData.getInstance().getPreferences().put(USERNAME_PREF, "");
		});
	}

	/**
	 * When the login button is pressed
	 *
	 * @param actionEvent consumed
	 */
	@FXML
	public void loginPressed(ActionEvent actionEvent)
	{
		// Login
		this.performLogin();
		actionEvent.consume();
	}

	/**
	 * When the enter key gets pressed, also try and login
	 *
	 * @param keyEvent Used to test if the key was enter or not
	 */
	public void enterPressed(KeyEvent keyEvent)
	{
		// Ensure the username and password are valid, the ES connection is good, and we're not logging in, and the key pressed was enter
		if (keyEvent.getCode() == KeyCode.ENTER && !this.USER_PASS_VALIDATOR.isInvalid() && !this.loggingIn.getValue())
		{
			// Login! and comsume the event
			this.performLogin();
			keyEvent.consume();
		}
	}

	/**
	 * Login to the given cyverse account
	 */
	private void performLogin()
	{
		// Save username preference if the box is checked
		if (this.cbxRememberUsername.isSelected())
			CalliopeData.getInstance().getPreferences().put(USERNAME_PREF, this.txtUsername.getText());

		// Only login if we're not logged in
		if (!CalliopeData.getInstance().loggedInProperty().getValue())
		{
			this.loggingIn.setValue(true);

			// Show the loading icon graphic
			this.btnLogin.setGraphic(new ImageView(new Image("/images/mainMenu/loading.gif", 26, 26, true, true)));
			// Grab our connection managers
			ElasticSearchConnectionManager esConnectionManager = CalliopeData.getInstance().getEsConnectionManager();
			CyVerseConnectionManager cyConnectionManager = CalliopeData.getInstance().getCyConnectionManager();
			// Grab the username and password
			String username = this.txtUsername.getText();
			String password = this.txtPassword.getText();
			// Thread off logging in...
			ErrorTask<Boolean> loginAttempt = new ErrorTask<Boolean>()
			{
				@Override
				protected Boolean call()
				{
					int NUM_STEPS = 6;

					// First login
					this.updateMessage("Logging in...");
					this.updateProgress(1, NUM_STEPS);
					Boolean loginSuccessful = cyConnectionManager.login(username, password);
					if (loginSuccessful)
						loginSuccessful = esConnectionManager.login(username, password);

					if (loginSuccessful)
					{
						// Set the authenticator for any HTTP request to use this username and password
						Authenticator.setDefault(new Authenticator()
						{
							@Override
							protected PasswordAuthentication getPasswordAuthentication()
							{
								return new PasswordAuthentication(username, password.toCharArray());
							}
						});

						Platform.runLater(() ->
						{
							CalliopeData.getInstance().setUsername(username);
							CalliopeData.getInstance().setLoggedIn(true);
							CalliopeData.getInstance().getErrorDisplay().setNotificationPane(notificationPane);
						});

						// Uncomment to destroy and recreate the ElasticSearch Index
						// esConnectionManager.nukeAndRecreateUserIndex();
						// esConnectionManager.nukeAndRecreateMetadataIndex();
						// esConnectionManager.nukeAndRecreateCollectionsIndex();
                        // esConnectionManager.nukeAndRecreateSitesIndex();

						// Then initialize the remove calliope and cyverse directory
						this.updateMessage("Initializing Calliope elastic index and CyVerse directory...");
						this.updateProgress(2, NUM_STEPS);
						esConnectionManager.initCalliopeRemoteDirectory(username);
						cyConnectionManager.initCalliopeRemoteDirectory();

						// Pull Calliope settings from the elastic index
						this.updateMessage("Pulling settings from elastic index...");
						this.updateProgress(3, NUM_STEPS);
						SettingsData settingsData = esConnectionManager.pullRemoteSettings(username);

						// Set the settings data
						Platform.runLater(() -> CalliopeData.getInstance().getSettings().loadFromOther(settingsData));

						// Pull any locations from the elastic index
						this.updateMessage("Pulling locations from elastic index...");
						this.updateProgress(4, NUM_STEPS);
						List<Site> sites = esConnectionManager.pullRemoteSites();

						// Set the location list to be these locations
						Platform.runLater(() -> CalliopeData.getInstance().getSiteManager().getSites().addAll(sites));

						// Pull any collections from the elastic index
						this.updateMessage("Pulling collections from elastic index...");
						this.updateProgress(5, NUM_STEPS);
						List<ImageCollection> imageCollections = esConnectionManager.pullRemoteCollections();

						// Set the image collection list to be these collections
						Platform.runLater(() -> CalliopeData.getInstance().getCollectionList().addAll(imageCollections));

						this.updateProgress(6, NUM_STEPS);
					}

					return loginSuccessful;
				}
			};
			// Once the task succeeds
			loginAttempt.setOnSucceeded(event -> {
				Boolean loginSucceeded = loginAttempt.getValue();
				// If we did not succeed, notify the user
				if (!loginSucceeded)
				{
					this.lblInvalidCredentials.setVisible(true);
				}
				this.loggingIn.setValue(false);
				// Hide the loading graphic
				this.btnLogin.setGraphic(null);
			});
			// Perform the task
			CalliopeData.getInstance().getExecutor().getQueuedExecutor().addTask(loginAttempt);
		}
	}

	/**
	 * When we click a hyperlink, this gets called
	 *
	 * @param actionEvent consumed
	 */
	public void linkPressed(ActionEvent actionEvent)
	{
		try
		{
			switch (((Hyperlink) actionEvent.getSource()).getText())
			{
				// Either open the register or password page on cyverse's website
				case "Register":
					Desktop.getDesktop().browse(new URI("https://user.cyverse.org/register"));
					actionEvent.consume();
					break;
				case "Password":
					Desktop.getDesktop().browse(new URI("https://user.cyverse.org/password/forgot"));
					actionEvent.consume();
					break;
			}
		}
		catch (URISyntaxException | IOException ignored)
		{
		}
	}
}
