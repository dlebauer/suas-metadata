package controller.importView;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.VBox;
import model.CalliopeData;
import model.analysis.CalliopeAnalysisUtils;
import model.location.Location;
import model.location.UTMCoord;
import model.util.SettingsData;

/**
 * Controller class for the location list cell
 */
public class LocationListEntryController extends ListCell<Location>
{
    ///
    /// FXML bound fields start
    ///

    // The main background pane
    @FXML
    public VBox mainPane;

    // The name of the location
    @FXML
    public Label lblName;

    // The ID of the location
    @FXML
    public Label lblId;

    // The location location (lat/lng)
    @FXML
    public Label lblLocationFirst;
    @FXML
    public Label lblLocationSecond;

    // The elevation of the location
    @FXML
    public Label lblLocationThird;

    ///
    /// FXML bound fields end
    ///

    /**
     * Initializes the location list entry by adding a listener to the format property
     */
    @FXML
    public void initialize()
    {
        // When we change the location format refresh the labels
        CalliopeData.getInstance().getSettings().locationFormatProperty().addListener((observable, oldValue, newValue) ->
        {
            if (this.getItem() != null)
                this.refreshLabels(this.getItem(), newValue, CalliopeData.getInstance().getSettings().getDistanceUnits());
        });
        // When we change the distance unit format refresh the labels
        CalliopeData.getInstance().getSettings().distanceUnitsProperty().addListener((observable, oldValue, newValue) ->
        {
            if (this.getItem() != null)
                this.refreshLabels(this.getItem(), CalliopeData.getInstance().getSettings().getLocationFormat(), newValue);
        });
    }

	/**
     * Update item is called whenever the cell gets updated
     *
     * @param location The new location
     * @param empty If the cell is empty
     */
    @Override
    protected void updateItem(Location location, boolean empty)
    {
        // Update the cell first
        super.updateItem(location, empty);

        // Set the text to null
        this.setText(null);

        // If the cell is empty we have no graphic
        if (empty && location == null)
        {
            this.setGraphic(null);
        }
        // if the cell is not empty, set the field's values and set the graphic
        else
        {
            this.lblName.setText(location.getName());
            this.lblId.setText(location.getId());
            this.refreshLabels(location, CalliopeData.getInstance().getSettings().getLocationFormat(), CalliopeData.getInstance().getSettings().getDistanceUnits());
            this.setGraphic(mainPane);
        }
    }

    /**
     * Update the location labels based on the format
     *
     * @param location The new location
     * @param format The format of the location
     * @param distanceUnits The units to be used when creating distance labels
     */
    private void refreshLabels(Location location, SettingsData.LocationFormat format, SettingsData.DistanceUnits distanceUnits)
    {
        // If we are using latitude & longitude
        if (format == SettingsData.LocationFormat.LatLong)
        {
            // Locations are stored in lat/lng so we can just use the value
            this.lblLocationFirst.setText(location.getLatitude().toString());
            this.lblLocationSecond.setText(location.getLongitude().toString());
            this.lblLocationThird.setText(Math.round(distanceUnits.formatToMeters(location.getElevation())) + distanceUnits.getSymbol());
        }
        // If we are using UTM
        else if (format == SettingsData.LocationFormat.UTM)
        {
            // Convert to UTM
            UTMCoord utmEquiv = CalliopeAnalysisUtils.Deg2UTM(location.getLatitude(), location.getLongitude());
            // Update the labels
            this.lblLocationFirst.setText(utmEquiv.getEasting().intValue() + "E");
            this.lblLocationSecond.setText(utmEquiv.getNorthing().intValue() + "N");
            this.lblLocationThird.setText("Zone " + utmEquiv.getZone().toString() + utmEquiv.getLetter().toString() + " at " + Math.round(distanceUnits.formatToMeters(location.getElevation())) + distanceUnits.getSymbol());
        }
    }
}
