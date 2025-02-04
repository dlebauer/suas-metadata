package controller.importView;

import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.image.ImageView;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import model.CalliopeData;
import model.image.ImageContainer;
import model.image.ImageDirectory;
import model.image.ImageEntry;
import model.site.Site;

import java.util.Optional;

import static model.constant.CalliopeDataFormats.SITE_CODE_FORMAT;

/**
 * Controller class for the image tree cells
 */
public class ImageTreeCellController extends TreeCell<ImageContainer>
{
	///
	/// FXML Bound Fields start
	///

	// The icon of the image or folder
	@FXML
	public ImageView imgIcon;
	// The label for the file name
	@FXML
	public Label lblText;
	// Reference to the main pane of the tree cell
	@FXML
	public HBox mainPane;
	@FXML
	public FlowPane fpnIcons;

	///
	/// FXML Bound Fields end
	///

	// Expanded listener is used to test: if a node is expanded, and the node is being uploaded, collapse the node again
	private ChangeListener<Number> expandedListener = (ignored, oldValue, newValue) ->
	{
		if (newValue.doubleValue() != -1)
			if (this.getTreeItem() != null)
				this.getTreeItem().setExpanded(false);
	};

	private ChangeListener<Boolean> tempListenerRef;

	/**
	 * Initializes the cell with listeners
	 */
	@FXML
	public void initialize()
	{
		// If a new tree item is expanded that is also disabled, hide it
		this.treeItemProperty().addListener((observable, oldValue, newValue) ->
		{
			// Unbind any old values since we don't need them to be listened too anymore
			if (oldValue != null && tempListenerRef != null)
				oldValue.expandedProperty().removeListener(tempListenerRef);
			// Bind the new value
			if (newValue != null)
			{
				tempListenerRef = (ignored, oldExpanded, newExpanded) ->
				{
					if (ImageTreeCellController.this.isDisabled() && newExpanded)
						newValue.setExpanded(false);
				};
				newValue.expandedProperty().addListener(tempListenerRef);
			}
		});
	}

	/**
	 * If this cell should display a new image container
	 *
	 * @param item The new item to display
	 * @param empty If the item is empty
	 */
	@Override
	protected void updateItem(ImageContainer item, boolean empty)
	{
		// Remove the previous listener if there was one
		if (this.getItem() instanceof ImageDirectory)
		{
			((ImageDirectory) this.getItem()).uploadProgressProperty().removeListener(expandedListener);
			this.disableProperty().unbind();
			this.setDisable(false);
		}

		// Call the internal item update
		super.updateItem(item, empty);

		// Set the text to null
		this.setText(null);

		// If the cell is empty we have no graphic
		if (empty && item == null)
		{
			this.setGraphic(null);
		}
		// if the cell is not empty, set the field's values and set the graphic
		else
		{
			// Unbind the existing image and bind it to the new image icon
			this.imgIcon.imageProperty().unbind();
			this.imgIcon.imageProperty().bind(item.treeIconProperty());
			this.lblText.setText(item.toString());

			// If the item is a directory, we disable the node if it is being uploaded
			if (item instanceof ImageDirectory)
			{
				ImageDirectory imageDirectory = (ImageDirectory) item;
				this.disableProperty().bind(imageDirectory.uploadProgressProperty().isNotEqualTo(-1));
				imageDirectory.uploadProgressProperty().addListener(expandedListener);
			}
			else if (item instanceof ImageEntry)
				((ImageEntry) item).buildAndStoreIcon();

			// Show the UI
			this.setGraphic(mainPane);
		}
	}

	/**
	 * If our mouse hovers over the image pane and we're dragging, we accept the transfer
	 *
	 * @param dragEvent The event that means we are dragging over the image pane
	 */
	public void cellDragOver(DragEvent dragEvent)
	{
		Dragboard dragboard = dragEvent.getDragboard();
		// If we started dragging at the species or location view and the dragboard has a string we play the fade animation and consume the event
		if (dragboard.hasContent(SITE_CODE_FORMAT) && (this.getItem() instanceof ImageEntry || this.getItem() instanceof ImageDirectory))
			dragEvent.acceptTransferModes(TransferMode.COPY);
		dragEvent.consume();
	}

	/**
	 * When the drag from the species or location list enters the image
	 *
	 * @param dragEvent The event that means we are dragging over the image pane
	 */
	public void cellDragEntered(DragEvent dragEvent)
	{
		Dragboard dragboard = dragEvent.getDragboard();
		// If we started dragging at the species or location view and the dragboard has a string we play the fade animation and consume the event
		if (dragboard.hasContent(SITE_CODE_FORMAT) && (this.getItem() instanceof ImageEntry || this.getItem() instanceof ImageDirectory))
			if (!this.mainPane.getStyleClass().contains("draggedOver"))
				this.mainPane.getStyleClass().add("draggedOver");
		dragEvent.consume();
	}

	/**
	 * When the drag from the species or location list exits the image
	 *
	 * @param dragEvent The event that means we are dragging away from the image pane
	 */
	public void cellDragExited(DragEvent dragEvent)
	{
		Dragboard dragboard = dragEvent.getDragboard();
		// If we started dragging at the species or location view and the dragboard has a string we play the fade animation and consume the event
		if (dragboard.hasContent(SITE_CODE_FORMAT) && (this.getItem() instanceof ImageEntry || this.getItem() instanceof ImageDirectory))
			this.mainPane.getStyleClass().remove("draggedOver");
		dragEvent.consume();
	}

	/**
	 * When we drop the species or location onto the image, we add that species or location to the list
	 *
	 * @param dragEvent The event that means we are dragging away from the image pane
	 */
	public void cellDragDropped(DragEvent dragEvent)
	{
		// Create a flag that will be set to true if everything went well
		Boolean success = false;
		// Grab the dragboard
		Dragboard dragboard = dragEvent.getDragboard();
		// If our dragboard has a string we have data which we need
		if (dragboard.hasContent(SITE_CODE_FORMAT))
		{
			String siteCode = (String) dragboard.getContent(SITE_CODE_FORMAT);
			// Grab the species with the given ID
			Optional<Site> toAdd = CalliopeData.getInstance().getSiteManager().getSites().stream().filter(site -> site.getCode().equals(siteCode)).findFirst();
			// Add the species to the image
			if (toAdd.isPresent())
			{
				this.getItem().setSiteTaken(toAdd.get());
				success = true;
			}
		}
		// Set the success equal to the flag, and consume the event
		dragEvent.setDropCompleted(success);
		dragEvent.consume();
	}
}
