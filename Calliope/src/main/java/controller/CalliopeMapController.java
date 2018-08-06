package controller;

import controller.importView.SitePopOverController;
import controller.mapView.MapCircleController;
import fxmapcontrol.*;
import javafx.animation.*;
import javafx.beans.binding.Bindings;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.SortedList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.Point3D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.*;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.util.Duration;
import javafx.util.StringConverter;
import library.AlignedMapNode;
import model.CalliopeData;
import model.constant.CalliopeDataFormats;
import model.constant.MapProviders;
import model.elasticsearch.GeoBucket;
import model.elasticsearch.GeoImageResult;
import model.elasticsearch.query.ElasticSearchQuery;
import model.elasticsearch.query.QueryCondition;
import model.elasticsearch.query.QueryEngine;
import model.elasticsearch.query.conditions.MapPolygonCondition;
import model.site.Boundary;
import model.site.Site;
import model.threading.ErrorTask;
import model.threading.ReRunnableService;
import model.transitions.HeightTransition;
import model.util.FXMLLoaderUtils;
import org.apache.commons.lang3.StringUtils;
import org.controlsfx.control.CheckComboBox;
import org.controlsfx.control.HyperlinkLabel;
import org.controlsfx.control.PopOver;
import org.controlsfx.control.ToggleSwitch;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.Subscription;
import org.locationtech.jts.math.MathUtil;

import javax.swing.filechooser.FileSystemView;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Controller class for the map page
 */
public class CalliopeMapController
{
	///
	/// FXML bound fields start
	///

	// The primary map object to display sites and images on
	@FXML
	public Map map;

	// A box containing any map specific settings (not query filters)
	@FXML
	public GridPane gpnMapSettings;

	// A combo-box of possible map providers like OSM or Esri
	@FXML
	public ComboBox<MapProviders> cbxMapProvider;
	// A toggle switch to enable or disable NEON site markers
	@FXML
	public ToggleSwitch tswNEON;
	// A toggle switch to enable or disable NEON site boundaries
	@FXML
	public ToggleSwitch tswLTAR;
	// A toggle switch to enable or disable image count circles
	@FXML
	public ToggleSwitch tswImageCounts;
	// A toggle switch to enable or disable query polygons
	@FXML
	public ToggleSwitch tswQueryPolygons;

	// Bottom pane which holds the query specifics
	@FXML
	public SplitPane queryPane;

	// The list of query conditions
	@FXML
	public ListView<QueryCondition> lvwQueryConditions;
	// The list of possible filters
	@FXML
	public ListView<QueryEngine.QueryFilters> lvwFilters;
	// The Vbox with the query parameters, used to hide the event interval
	@FXML
	public VBox vbxQuery;
	// The imageview with the arrow divider
	@FXML
	public ImageView imgArrow;

	// A button to expand or contract the query
	@FXML
	public Button btnExpander;

	// A titled pane which can hide our metadata entries
	@FXML
	public TitledPane tpnCircleMetadata;

	// A spinner that lets us pick the max images per bucket
	@FXML
	public Spinner<Integer> spnMaxImagesPerBucket;

	// The table view with metadata columns
	@FXML
	public TableView<GeoImageResult> tbvImageMetadata;
	// 5 Columns of metadata, the image file name, collection name, altitude taken, camera model, and date taken
	@FXML
	public TableColumn<GeoImageResult, String> clmName;
	@FXML
	public TableColumn<GeoImageResult, String> clmCollection;
	@FXML
	public TableColumn<GeoImageResult, Double> clmAltitude;
	@FXML
	public TableColumn<GeoImageResult, String> clmCameraModel;
	@FXML
	public TableColumn<GeoImageResult, LocalDateTime> clmDate;

	// Check boxes to hide & show columns
	public CheckComboBox<TableColumn<GeoImageResult, ?>> ccbxColumns;

	// Disable the download query button if the query is invalid
	@FXML
	public Button btnDownloadQuery;

	// The map scale label
	@FXML
	public Label lblScale;
	// The map scale box
	@FXML
	public HBox hbxScale;

	// The label containing any map credits
	@FXML
	public HyperlinkLabel lblMapCredits;

	///
	/// FXML bound fields end
	///

	// Two image textures used for the arrow divider button
	private static final Image STANDARD_ARROW = new Image("/images/analysisWindow/arrowDivider.png");
	private static final Image HIGHLIGHTED_ARROW = new Image("/images/analysisWindow/arrowDividerSelected.png");

	// A list of current pins on the map displaying circles with an image amount inside
	private List<MapNode> currentCircles = new ArrayList<>();
	// A parallel list of controllers to that list of pins
	private List<MapCircleController> currentCircleControllers = new ArrayList<>();
	// The zoom threshold where we start to render polygons instead of pins
	private static final Double PIN_TO_POLY_THRESHOLD = 10D;

	// Next 3 variables are a bit of a hack, but they allow us to have a z-order for our map
	// A cache of each node's Z order field
	private java.util.Map<Node, Integer> zOrder;
	// A sorted list which will be sorted by z-order
	private ObservableList<Node> sortedNodes;
	// A cache to a listener to avoid early garbage collection
	private Subscription subscriptionCache;

	// Flag telling us if the query box is currently expanded or contracted
	private Boolean expandedQuery = false;
	// The currently 'in-use' query used to filter showing images on the map
	private ObjectProperty<QueryBuilder> currentQuery = new SimpleObjectProperty<>(QueryBuilders.matchAllQuery());

	// Two transitions used to fade the query tab in and out
	private Transition fadeQueryIn;
	private Transition fadeQueryOut;

	// The currently selected circle
	private ObjectProperty<MapCircleController> selectedCircle = new SimpleObjectProperty<>();

	// An enum with a set of map layers and their respective Z-layer
	private enum MapLayers
	{
		TILE_PROVIDER(0),
		BORDER_POLYGON(1),
		CIRCLES(2),
		QUERY_BOUNDARY(3),
		QUERY_CORNER(4),
		SITE_PINS(5);

		// The z-layer
		private Integer zLayer;

		/**
		 * Constructor just sets the z-layer field
		 *
		 * @param zLayer The map z-layer
		 */
		MapLayers(Integer zLayer)
		{
			this.zLayer = zLayer;
		}
	}

	/**
	 * Initialize sets up the analysis window and bindings
	 */
	@FXML
	public void initialize()
	{
		// Store image tiles inside of the user's home directory
		TileImageLoader.setCache(new ImageFileCache(new File(System.getProperty("user.home") + File.separator + "CalliopeMapCache").toPath()));

		///
		/// Setup the Z-Layer ordering system
		///

		// Initialize our z-order cache
		this.zOrder = new HashMap<>();
		// Let the base list be our custom observable list, we add to this list not map.getChildren()
		this.sortedNodes = FXCollections.observableArrayList();
		// Sort this custom list of nodes by the z-order, and then assign it to the map's children field. Now any nodes added to the sorted nodes list
		// will be automatically added to the map's children list in sorted order. Store a useless reference to the subscription otherwise it will be
		// garbage collected early
		this.subscriptionCache = EasyBind.listBind(this.map.getChildren(), new SortedList<>(this.sortedNodes, Comparator.comparing(node -> zOrder.getOrDefault(node, -1))));

		///
		/// Setup the tile providers
		///

		// Add the default tile layer to the background, use OpenStreetMap by default
		this.addNodeToMap(MapProviders.OpenStreetMaps.getMapTileProvider(), MapLayers.TILE_PROVIDER);
		// Setup our map provider combobox, first set the items to be an unmodifiable list of enums
		this.cbxMapProvider.setItems(FXCollections.unmodifiableObservableList(FXCollections.observableArrayList(MapProviders.values())));
		// Select OSM as the default map provider
		this.cbxMapProvider.getSelectionModel().select(MapProviders.OpenStreetMaps);
		// When we select a new map provider, swap tile providers
		this.cbxMapProvider.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) ->
		{
			// This should always be true...
			if (newValue != null && oldValue != null)
			{
				// Grab the old and new tile providers
				MapTileLayer oldMapTileProvider = oldValue.getMapTileProvider();
				MapTileLayer newMapTileProvider = newValue.getMapTileProvider();
				// Remove the old provider, add the new one
				this.removeNodeFromMap(oldMapTileProvider);
				this.addNodeToMap(newMapTileProvider, MapLayers.TILE_PROVIDER);
			}
		});
		// Update the credits label whenever the map provider changes
		this.lblMapCredits.textProperty().bind(EasyBind.monadic(this.cbxMapProvider.getSelectionModel().selectedItemProperty()).map(mapProvider -> "Map tiles by [" + mapProvider.toString() + "]"));
		// Update the action to reflect the currently selected map provider
		this.lblMapCredits.onActionProperty().bind(EasyBind.monadic(this.cbxMapProvider.getSelectionModel().selectedItemProperty()).map(mapProvider -> event -> { try { Desktop.getDesktop().browse(new URI(mapProvider.getCreditURL())); } catch (URISyntaxException | IOException ignored) {} }));

		///
		/// Setup the pop-over which is shown if a site pin is clicked
		///

		// Add a popover that we use to display location specifics
		PopOver popOver = new PopOver();
		popOver.setHeaderAlwaysVisible(false);
		popOver.setArrowLocation(PopOver.ArrowLocation.BOTTOM_CENTER);
		popOver.setArrowSize(20);
		popOver.setCloseButtonEnabled(true);
		// Load the content of the popover from the FXML file once and store it
		FXMLLoader fxmlLoader = FXMLLoaderUtils.loadFXML("importView/SitePopOver.fxml");
		// Grab the controller for use later
		SitePopOverController sitePopOverController = fxmlLoader.getController();
		// Store the content into the popover
		popOver.setContentNode(fxmlLoader.getRoot());

		///
		/// Setup our sites on the map. When a new site gets added we add a polygon & pin, when it gets removed we clear the polygon & pin
		///

		List<Node> mapSiteNodes = new ArrayList<>();
		ReRunnableService<List<String>> siteBoundaryDrawingService = new ReRunnableService<>(() ->
		{
			// Compute the bounds of the map inside of the window, this is used to compute the extent to which we can see the map
			// We do it here so that it's on the FXApplicationThread and not in our custom task thread
			Bounds boundsInParent = map.getBoundsInParent();
			return new ErrorTask<List<String>>()
			{
				@Override
				protected List<String> call()
				{
					// Using the bounds we compute the maximum and minimum lat/long values which we will pass to elasticsearch later
					Location topLeft = CalliopeMapController.this.map.getProjection().viewportPointToLocation(new Point2D(boundsInParent.getMinX(), boundsInParent.getMinY()));
					Location bottomRight = CalliopeMapController.this.map.getProjection().viewportPointToLocation(new Point2D(boundsInParent.getMaxX(), boundsInParent.getMaxY()));

					// This is the important line. We call ES to grab all site codes within our viewport
					return CalliopeData.getInstance().getEsConnectionManager().grabSiteCodesWithin(
							MathUtil.clamp(topLeft.getLatitude(), -90.0, 90.0),
							MathUtil.clamp(topLeft.getLongitude(), -180.0, 180.0),
							MathUtil.clamp(bottomRight.getLatitude(), -90.0, 90.0),
							MathUtil.clamp(bottomRight.getLongitude(), -180.0, 180.0));
				}
			};
		});
		siteBoundaryDrawingService.addFinishListener(siteCodesToDraw ->
		{
			// Remove any known nodes from the map and clear the site nodes list
			mapSiteNodes.forEach(this::removeNodeFromMap);
			mapSiteNodes.clear();
			// Grab the current list of sites
			ObservableList<Site> sites = CalliopeData.getInstance().getSiteManager().getSites();
			// For each of the returned site codes....
			for (String siteCode : siteCodesToDraw)
			{
				// ... find the corresponding site and process it
				sites.stream().filter(siteToTest -> siteToTest.getCode().equals(siteCode)).findFirst().ifPresent(site ->
				{
					// Convert the site's center to a location
					Location centerPoint = new Location(site.getCenter().getLat(), site.getCenter().getLon());

					// If we're zoomed in far enough, show the polygon, otherwise show the pin
					if (this.map.getZoomLevel() > PIN_TO_POLY_THRESHOLD)
					{
						// Grab the polygon representing the boundary of this site
						Boundary polygon = site.getBoundary();
						// Create a map polygon to render this site's boundary
						MapPolygon mapPolygon = new MapPolygon();
						// Setup the polygon's boundary
						mapPolygon.getLocations().addAll(polygon.getOuterBoundary().stream().map(coordinate -> new Location(coordinate.getLat(), coordinate.getLon())).collect(Collectors.toList()));
						// Setup the polygon's center location point
						mapPolygon.setLocation(centerPoint);
						// Add a CSS attribute to all polygons so that we can style them later
						mapPolygon.getStyleClass().add("site-boundary");
						// Make sure we can drag & drop through the polygon
						mapPolygon.setMouseTransparent(true);
						// Hide the polygon if the toggle switch is off
						mapPolygon.visibleProperty().bind(
								this.tswNEON.selectedProperty().and(Bindings.createBooleanBinding(() -> StringUtils.startsWithIgnoreCase(site.getCode(), "NEON"), site.nameProperty()))
							.or(this.tswLTAR.selectedProperty().and(Bindings.createBooleanBinding(() -> StringUtils.startsWithIgnoreCase(site.getCode(), "LTAR"), site.nameProperty()))));

						mapSiteNodes.add(mapPolygon);
						this.addNodeToMap(mapPolygon, MapLayers.BORDER_POLYGON);
					}
					else
					{
						// Create a map pin to render the site's center point when zoomed out
						MapNode mapPin = new AlignedMapNode(Pos.CENTER);
						// Set the pin's center to be the node's center
						mapPin.setLocation(centerPoint);
						// Add a new imageview to the pin
						ImageView pinImageView = new ImageView();
						// Make sure the image represents if the pin is hovered or not
						pinImageView.imageProperty().bind(EasyBind.monadic(mapPin.hoverProperty()).map(site::getIcon));
						// Add the image to the pin
						mapPin.getChildren().add(pinImageView);
						// When we click a pin, show the popover
						mapPin.setOnMouseClicked(event ->
						{
							// Call our controller's update method and then show the popup
							sitePopOverController.updateSite(site);
							popOver.show(mapPin);
							event.consume();
						});

						// Hide/Show pins when the toggle switches are toggled
						mapPin.visibleProperty().bind(
								this.tswNEON.selectedProperty().and(Bindings.createBooleanBinding(() -> StringUtils.startsWithIgnoreCase(site.getCode(), "NEON"), site.nameProperty()))
						    .or(this.tswLTAR.selectedProperty().and(Bindings.createBooleanBinding(() -> StringUtils.startsWithIgnoreCase(site.getCode(), "LTAR"), site.nameProperty()))));

						mapSiteNodes.add(mapPin);
						this.addNodeToMap(mapPin, MapLayers.SITE_PINS);
					}
				});
			}
		});

		///
		/// Setup the circles that aggregate images into bucket. Use a service to thread this work off
		///

		// Service that runs in the background drawing new circles every time the user zooms in and out
		ReRunnableService<List<GeoBucket>> circleDrawingService = new ReRunnableService<>(() ->
		{
			// Compute the bounds of the map inside of the window, this is used to compute the extent to which we can see the map
			// We do it here so that it's on the FXApplicationThread and not in our custom task thread
			Bounds boundsInParent = map.getBoundsInParent();
			return new ErrorTask<List<GeoBucket>>()
			{
				@Override
				protected List<GeoBucket> call()
				{
					// Using the bounds we compute the maximum and minimum lat/long values which we will pass to elasticsearch later
					Location topLeft = CalliopeMapController.this.map.getProjection().viewportPointToLocation(new Point2D(boundsInParent.getMinX(), boundsInParent.getMinY()));
					Location bottomRight = CalliopeMapController.this.map.getProjection().viewportPointToLocation(new Point2D(boundsInParent.getMaxX(), boundsInParent.getMaxY()));

					// This is the important line. We call ES to perform an aggregation of all uploaded images given bounds and a zoom level.
					// This call will return a list of buckets including number of images per bucket and centroids for each bucket
					return CalliopeData.getInstance().getEsConnectionManager().performGeoAggregation(
							MathUtil.clamp(topLeft.getLatitude(), -90.0, 90.0),
							MathUtil.clamp(topLeft.getLongitude(), -180.0, 180.0),
							MathUtil.clamp(bottomRight.getLatitude(), -90.0, 90.0),
							MathUtil.clamp(bottomRight.getLongitude(), -180.0, 180.0),
							CalliopeMapController.this.depthForCurrentZoom(),
							CalliopeMapController.this.currentQuery.getValue(),
							CalliopeMapController.this.spnMaxImagesPerBucket.getValue());
				}
			};
		});
		// Once the service is done with its thread, take the results and process them
		circleDrawingService.addFinishListener(geoBuckets ->
		{
			// If we have more buckets than currently existing circles, create more pins until the two buckets have the same size.
			// We do this so we avoid allocating significant chunks of memory every time this gets called. Try to re-use buckets we already have
			while (geoBuckets.size() > currentCircles.size())
			{
				MapNode newCircle = this.createCircle();
				newCircle.visibleProperty().bind(this.tswImageCounts.selectedProperty());
				this.addNodeToMap(newCircle, MapLayers.CIRCLES);
			}
			// If we have less buckets than currently existing circles, remove circles until the two buckets have the same size. We
			// do this to avoid having to allocate any memory at all
			while (currentCircles.size() > geoBuckets.size())
			{
				MapNode toRemove = currentCircles.remove(currentCircles.size() - 1);
				currentCircleControllers.remove(currentCircleControllers.size() - 1);
				this.removeNodeFromMap(toRemove);
			}

			// At this point our buckets should have the same size, test that here
			if (currentCircles.size() == geoBuckets.size())
			{
				// Iterate over all pins on the map, we're going to update all of their positions and labels
				for (Integer i = 0; i < currentCircles.size(); i++)
				{
					// Grab the bucket for that pin
					GeoBucket geoBucket = geoBuckets.get(i);
					// Grab the pin
					MapNode mapNode = currentCircles.get(i);
					// Grab the controller for that pin
					MapCircleController mapCircleController = currentCircleControllers.get(i);
					// Update the pin and the controller for that pin
					mapNode.setLocation(new Location(geoBucket.getCenterLatitude(), geoBucket.getCenterLongitude()));
					mapCircleController.updateItem(geoBucket);
				}
			}
			else
			{
				// If the buckets are not the same size something went wrong, throw an error
				CalliopeData.getInstance().getErrorDisplay().notify("Pin bucket and geo bucket were not the same size after normalization, this is an error!");
			}
			// Make sure that the selected circle is cleared because it's no longer invalid
			this.selectedCircle.setValue(null);
		});
		// When the mouse gets dragged and released we update our pins
		this.map.mouseDraggingProperty().addListener((observable, oldValue, newValue) ->
		{
			// If we started dragging or got a null don't do anything
			if (newValue == null || newValue)
				return;
			// Redraw all the site boundaries in our view because we moved our map
			siteBoundaryDrawingService.requestAnotherRun();
			// Redraw all the pin circles with new values because we moved our map
			circleDrawingService.requestAnotherRun();
		});
		// When the mouse scroll is touched and the zoom is changed we update our circles too
		this.map.zoomLevelProperty().addListener((observable, oldValue, newValue) ->
		{
			siteBoundaryDrawingService.requestAnotherRun();
			circleDrawingService.requestAnotherRun();
		});
		// When our query changes we request another circle drawing run which updates the mini circles containing images
		this.currentQuery.addListener((observable, oldValue, newValue) -> circleDrawingService.requestAnotherRun());

		///
		/// Setup the transition to fade the query tab in and out from the bottom of the screen
		///

		// How many seconds the transition will take
		final double TRANSITION_DURATION = 0.6;
		final double MAX_QUERY_PANE_HEIGHT = 370;
		final double MIN_QUERY_PANE_HEIGHT = 100;
		// Reduce the height of the pane
		HeightTransition heightDownTransition = new HeightTransition(Duration.seconds(TRANSITION_DURATION), this.queryPane, MAX_QUERY_PANE_HEIGHT, MIN_QUERY_PANE_HEIGHT);
		// Reduce the opacity of the pane
		FadeTransition fadeOutTransition = new FadeTransition(Duration.seconds(TRANSITION_DURATION * 0.8), this.queryPane);
		fadeOutTransition.setFromValue(1.0);
		fadeOutTransition.setToValue(0.0);
		// Rotate the expander button 180 degrees across the X axis
		RotateTransition rotateUpTransition = new RotateTransition(Duration.seconds(TRANSITION_DURATION), this.btnExpander);
		rotateUpTransition.setFromAngle(180);
		rotateUpTransition.setToAngle(0);
		rotateUpTransition.setAxis(new Point3D(1, 0, 0));
		// Move the expander button to the bottom
		TranslateTransition translateDownTransition = new TranslateTransition(Duration.seconds(TRANSITION_DURATION), this.btnExpander);
		translateDownTransition.setFromY(-MAX_QUERY_PANE_HEIGHT);
		translateDownTransition.setToY(0);
		// Expand the expander button
		Timeline maxWidthExpansion = new Timeline(new KeyFrame(Duration.seconds(TRANSITION_DURATION), new KeyValue(this.btnExpander.prefWidthProperty(), 170)));
		// Move the scale to the bottom
		TranslateTransition translateScaleDownTransition = new TranslateTransition(Duration.seconds(TRANSITION_DURATION), this.hbxScale);
		translateScaleDownTransition.setFromY(-MAX_QUERY_PANE_HEIGHT);
		translateScaleDownTransition.setToY(0);
		// Move the map credits to the bottom
		TranslateTransition translateCreditsDownTransition = new TranslateTransition(Duration.seconds(TRANSITION_DURATION), this.lblMapCredits);
		translateCreditsDownTransition.setFromY(-MAX_QUERY_PANE_HEIGHT);
		translateCreditsDownTransition.setToY(0);
		// Setup the parallel transition
		this.fadeQueryOut = new ParallelTransition(fadeOutTransition, heightDownTransition, rotateUpTransition, translateDownTransition, translateScaleDownTransition, translateCreditsDownTransition, maxWidthExpansion);
		// Once finished, hide the query pane
		this.fadeQueryOut.setOnFinished(event ->
		{
			this.queryPane.setVisible(false);
			this.btnExpander.setText("Query");
		});

		// Increase the height of the pane
		HeightTransition heightUpTransition = new HeightTransition(Duration.seconds(TRANSITION_DURATION), this.queryPane, MIN_QUERY_PANE_HEIGHT, MAX_QUERY_PANE_HEIGHT);
		// Increase the opacity of the pane
		FadeTransition fadeInTransition = new FadeTransition(Duration.seconds(TRANSITION_DURATION * 0.8), this.queryPane);
		fadeInTransition.setFromValue(0.0);
		fadeInTransition.setToValue(1.0);
		// Rotate the expander button 180 degrees across the X axis
		RotateTransition rotateDownTransition = new RotateTransition(Duration.seconds(TRANSITION_DURATION), this.btnExpander);
		rotateDownTransition.setFromAngle(0);
		rotateDownTransition.setToAngle(180);
		rotateDownTransition.setAxis(new Point3D(1, 0, 0));
		// Move the expander button to the top
		TranslateTransition translateUpTransition = new TranslateTransition(Duration.seconds(TRANSITION_DURATION), this.btnExpander);
		translateUpTransition.setFromY(0);
		translateUpTransition.setToY(-MAX_QUERY_PANE_HEIGHT);
		// Contract the expander button
		Timeline maxWidthContraction = new Timeline(new KeyFrame(Duration.seconds(TRANSITION_DURATION), new KeyValue(this.btnExpander.prefWidthProperty(), 64)));
		// Move the scale to the top
		TranslateTransition translateScaleUpTransition = new TranslateTransition(Duration.seconds(TRANSITION_DURATION), this.hbxScale);
		translateScaleUpTransition.setFromY(0);
		translateScaleUpTransition.setToY(-MAX_QUERY_PANE_HEIGHT);
		// Move the credits to the top
		TranslateTransition translateCreditsUpTransition = new TranslateTransition(Duration.seconds(TRANSITION_DURATION), this.lblMapCredits);
		translateCreditsUpTransition.setFromY(0);
		translateCreditsUpTransition.setToY(-MAX_QUERY_PANE_HEIGHT);
		// Setup the parallel transition
		this.fadeQueryIn = new ParallelTransition(fadeInTransition, heightUpTransition, rotateDownTransition, translateUpTransition, translateScaleUpTransition, translateCreditsUpTransition, maxWidthContraction);

		///
		/// Setup the query panel on the bottom of the screen
		///

		// Set the query conditions to be specified by the data model
		this.lvwQueryConditions.setItems(CalliopeData.getInstance().getQueryEngine().getQueryConditions());
		// Set the cell factory to be our custom query condition cell which adapts itself to the specific condition
		this.lvwQueryConditions.setCellFactory(x -> FXMLLoaderUtils.loadFXML("mapView/QueryConditionsListCell.fxml").getController());
		// Set the items in the list to be the list of possible query filters
		this.lvwFilters.setItems(CalliopeData.getInstance().getQueryEngine().getQueryFilters());

		///
		/// Create a mapping of polygon condition to the visual polygon on the map. This will update the map if a polygon condition is created and populated
		///

		// A mapping of map polygon condition -> map polygon visual
		java.util.Map<MapPolygonCondition, MapPolygon> modelToVisual = new HashMap<>();
		// Add a special interaction for our MapPolygonCondition that allows us to draw on the map
		CalliopeData.getInstance().getQueryEngine().getQueryConditions().addListener((ListChangeListener<QueryCondition>) change ->
		{
			while (change.next())
				// If we added a new query condition, test if we need to add a map polygon
				if (change.wasAdded())
				{
					// Iterate over all new conditions and see if they're map polygon conditions
					for (QueryCondition addedQueryCondition : change.getAddedSubList())
						if (addedQueryCondition instanceof MapPolygonCondition)
						{
							// Grab the query condition as a map polygon condition
							MapPolygonCondition mapPolygonCondition = (MapPolygonCondition) addedQueryCondition;
							// Create a new map polygon
							MapPolygon mapPolygon = new MapPolygon();
							// Hide the polygon if there's less than 3 points
							mapPolygon.visibleProperty().bind(Bindings.size(mapPolygonCondition.getPoints()).greaterThan(2));
							// Map the model polygon's points to the map polygon's points
							mapPolygon.setLocations(EasyBind.map(mapPolygonCondition.getPoints(), polygonPoint -> new Location(polygonPoint.getLatitude(), polygonPoint.getLongitude())));
							// Add a CSS attribute to all polygons so that we can style them later
							mapPolygon.getStyleClass().add("geo-query-boundary");
							// Make sure we can drag & drop through the polygon
							mapPolygon.setMouseTransparent(true);
							// Hide the map polygon if the toggle switch is off
							mapPolygon.visibleProperty().bind(this.tswQueryPolygons.selectedProperty());
							// Store the mapping that we can use for removal later
							modelToVisual.put(mapPolygonCondition, mapPolygon);
							// Add the polygon at the query boundary layer
							this.addNodeToMap(mapPolygon, MapLayers.QUERY_BOUNDARY);
						}
				}
				// If we remove a query condition, test if we need to remove a map polygon
				else if (change.wasRemoved())
					// Iterate over all new conditions and see if they're map polygon conditions
					for (QueryCondition removedQueryCondition : change.getRemoved())
						if (removedQueryCondition instanceof MapPolygonCondition)
							// If it is, grab the visual element from our map and remove it
							this.removeNodeFromMap(modelToVisual.remove(removedQueryCondition));
		});



		///
		/// Setup the top left box of settings
		///

		// Each column is bound to a different image metadata tag
		this.clmName.setCellValueFactory(param -> param.getValue().nameProperty());
		this.clmCollection.setCellValueFactory(param -> param.getValue().collectionNameProperty());
		this.clmAltitude.setCellValueFactory(param -> param.getValue().altitudeProperty().asObject());
		this.clmCameraModel.setCellValueFactory(param -> param.getValue().cameraModelProperty());
		this.clmDate.setCellValueFactory(param -> param.getValue().dateProperty());
		// Sort the date column by the date comparator
		this.clmDate.setComparator(Comparator.naturalOrder());
		// Set the date format. This code is taken from DEFAULT_CELL_FACTORY in TableColumn.class
		this.clmDate.setCellFactory(param -> new TableCell<GeoImageResult, LocalDateTime>()
		{
			/**
			 * Called when a new item is added to this cell
			 *
			 * @param localDateTime The new local date time to display
			 * @param empty If the cell should be empty
			 */
			@Override
			protected void updateItem(LocalDateTime localDateTime, boolean empty)
			{
				// If the date is null, return
				if (localDateTime == getItem())
					return;
				// Update the item internally
				super.updateItem(localDateTime, empty);
				// If the date is null, set the text and graphic to null
				if (localDateTime == null) { super.setText(null); super.setGraphic(null); }
				// Otherwise, update the text with the properly formatted date
				else
				{
					super.setText(CalliopeData.getInstance().getSettings().formatDateTime(localDateTime, " "));
					super.setGraphic(null);
				}
			}
		});

		// Add all columns to the check box combo box
		this.ccbxColumns.getItems().add(this.clmName);
		this.ccbxColumns.getItems().add(this.clmCollection);
		this.ccbxColumns.getItems().add(this.clmAltitude);
		this.ccbxColumns.getItems().add(this.clmCameraModel);
		this.ccbxColumns.getItems().add(this.clmDate);

		// Start with just name and date checked
		this.ccbxColumns.getCheckModel().check(this.clmName);
		this.ccbxColumns.getCheckModel().check(this.clmDate);

		// Remove the columns that are not checked by default
		for (int index = 0; index < this.ccbxColumns.getItems().size(); index++)
			if (!this.ccbxColumns.getCheckModel().isChecked(index))
				this.tbvImageMetadata.getColumns().remove(this.ccbxColumns.getItems().get(index));

		// The combo box will show the title of the column as its text
		this.ccbxColumns.setConverter(new StringConverter<TableColumn<GeoImageResult, ?>>()
		{
			@Override
			public String toString(TableColumn<GeoImageResult, ?> tableColumn) { return tableColumn.getText(); }
			@Override
			public TableColumn<GeoImageResult, ?> fromString(String columnName) { return null; }
		});
		// When the checked indices list changes, we update the shown columns
		this.ccbxColumns.getCheckModel().getCheckedIndices().addListener((ListChangeListener<Integer>) c ->
		{
			while (c.next())
				// If the index was removed...
				if (c.wasRemoved())
					for (Integer removedIndex : c.getRemoved())
					{
						// Grab the removed column and remove it
						TableColumn<GeoImageResult, ?> removedColumn = ccbxColumns.getCheckModel().getItem(removedIndex);
						this.tbvImageMetadata.getColumns().remove(removedColumn);
					}
				// If the index was added...
				else if (c.wasAdded())
					for (Integer addedIndex : c.getAddedSubList())
					{
						// Grab the column to add and make sure it's in the table
						TableColumn<GeoImageResult, ?> addedColumn = ccbxColumns.getCheckModel().getItem(addedIndex);
						if (!this.tbvImageMetadata.getColumns().contains(addedColumn))
							this.tbvImageMetadata.getColumns().add(addedColumn);
					}
		});

		///
		/// Setup the service used to download a specific circle's image metadata
		///

		// A service that can download a selected circle's metadata
		ReRunnableService<List<GeoImageResult>> circleMetadataDownloader = new ReRunnableService<>(() ->
			new ErrorTask<List<GeoImageResult>>()
			{
				@Override
				protected List<GeoImageResult> call()
				{
					// Test if our input is non-null (it should never be null)
					if (selectedCircle.getValue() != null)
						// If it's not null, perform our DB access and return the result
						return CalliopeData.getInstance().getEsConnectionManager().performCircleLookup(selectedCircle.getValue().getGeoBucket());
					else
						return null;
				}
			}, CalliopeData.getInstance().getExecutor().getImmediateExecutor());
		// Once the service finishes we update our tableview with the new items
		circleMetadataDownloader.addFinishListener(geoImageResults ->
		{
			// Update the items
			this.tbvImageMetadata.getItems().setAll(geoImageResults);
			// Make sure our title pane is expanded too
			this.tpnCircleMetadata.setExpanded(true);
		});
		// Whenever we select a new circle we ask our circle thread to perform another run
		this.selectedCircle.addListener((observable, oldValue, newValue) ->
		{
			if (newValue != null)
			{
				// Highlight the new circle
				newValue.setSelected(true);
				circleMetadataDownloader.requestAnotherRun();
			}
			if (oldValue != null)
			{
				// Remove the circle highlight
				oldValue.setSelected(false);
			}
		});

		///
		/// Setup the map scale indicator on the bottom left
		///

		EasyBind.subscribe(this.map.zoomLevelProperty(), newValue ->
		{
			// The minimum size in pixels of the scale in the bottom left
			final double MIN_SIZE = 100;
			// The maximum size in pixels of the scale in the bottom left
			final double MAX_SIZE = 300;
			// Pixels per meter to start with here
			double pixelsPerPowerOf10 = this.map.getProjection().getMapScale(this.map.getCenter()).getY();
			// Iterate up to 25 times (or 10^25)
			for (int currentPowerOf10 = 0; currentPowerOf10 < 25; currentPowerOf10++)
			{
				// If the pixels per meter is greater than the minimum, we stop and draw it at that size
				if (pixelsPerPowerOf10 > MIN_SIZE)
				{
					// Compute the scale based on the power of 10
					long scale = Math.round(Math.pow(10, currentPowerOf10));
					// Test if we want it to use KM or M based on the power of 10.
					boolean willUseKM = currentPowerOf10 > 3;

					// If the pixels per power of 10 is bigger than our max size, draw it at 1/2 size
					if (pixelsPerPowerOf10 < MAX_SIZE)
					{
						// Set the text based on if it's KM or M
						this.lblScale.setText(willUseKM ? Long.toString(scale / 1000) + " km" : Long.toString(scale) + " m");
						// Force the HBox width
						this.hbxScale.setMinWidth(pixelsPerPowerOf10);
						this.hbxScale.setMaxWidth(pixelsPerPowerOf10);
					}
					else
					{
						// Set the text based on if it's KM or M. We divide by 2 to ensure it's not too large
						this.lblScale.setText(willUseKM ? Long.toString(scale / 2000) + " km" : Long.toString(scale / 2) + " m");
						// Force the HBox width
						this.hbxScale.setMinWidth(pixelsPerPowerOf10 / 2);
						this.hbxScale.setMaxWidth(pixelsPerPowerOf10 / 2);
					}
					return;
				}
				// Increment pixels by a power of 10
				pixelsPerPowerOf10 = pixelsPerPowerOf10 * 10;
			}
		});
	}

	/**
	 * Allocates a new pin node and stores the required references into the lists
	 *
	 * @return Returns a newly allocated map node with its controller and reference stored into parallel lists
	 */
	private MapNode createCircle()
	{
		// Create a new circle object, call our custom class that ensures the node remains centered on the geo-location
		MapNode circle = new AlignedMapNode();
		// Load the FXML document representing this circle
		FXMLLoader fxmlLoader = FXMLLoaderUtils.loadFXML("mapView/MapCircle.fxml");
		// Add FXML document to this circle
		circle.getChildren().add(fxmlLoader.getRoot());
		// Store the circle and its controller into the parallel lists
		this.currentCircles.add(circle);
		MapCircleController circleController = fxmlLoader.getController();
		this.currentCircleControllers.add(circleController);
		// When we click the circle attempt to retrieve details about that circle
		circle.setOnMouseClicked(event -> selectedCircle.setValue(circleController));
		// Return the circle
		return circle;
	}

	/**
	 * Function that returns a depth value to be used by the ES geo aggregator. This value can be between 1-12, where
	 * 1 means aggregate locations together within a few hundred miles and 12 means locations together within a meter
	 *
	 * @return A value between 1 and 12 based on the map's current zoom
 	 */
	private Integer depthForCurrentZoom()
	{
		// Get the map's current zoom level
		Double zoom = this.map.getZoomLevel();
		// Based on that zoom level, return an appropriate amount of aggregation
		if (zoom <= 5)
			return 1;
		else if (zoom <= 8)
			return 2;
		else if (zoom <= 10)
			return 3;
		else if (zoom <= 12)
			return 4;
		else if (zoom <= 14)
			return 5;
		else if (zoom <= 16)
			return 6;
		else if (zoom <= 18)
			return 7;
		else if (zoom <= 19)
			return 8;
		else return 9;
	}

	/**
	 * Adds a node into the map using the binding we created earlier. First we add
	 * to our z-order map and then insert into our sorted nodes list which ensures that
	 * the z-order is properly applied
	 *
	 * @param node The node to add to the list
	 * @param zOrder The z-order to assign to the node
	 */
	private void addNodeToMap(Node node, MapLayers zOrder)
	{
		this.zOrder.put(node, zOrder.zLayer);
		this.sortedNodes.add(node);
	}

	/**
	 * Removes the node from the map as well as the z-order hashmap
	 *
	 * @param node The node to remove
	 */
	private void removeNodeFromMap(Node node)
	{
		this.sortedNodes.remove(node);
		this.zOrder.remove(node);
	}

	/**
	 * Called to expand or retract the query filters box on the bottom
	 *
	 * @param actionEvent consumed
	 */
	public void expandOrRetractFilters(ActionEvent actionEvent)
	{
		if (!expandedQuery)
		{
			this.fadeQueryIn.play();
			this.btnExpander.setText("");
			this.queryPane.setVisible(true);
		}
		else
		{
			this.fadeQueryOut.play();
		}
		this.expandedQuery = !expandedQuery;

		actionEvent.consume();
	}

	/**
	 * Called when the refresh button is pressed
	 *
	 * @param actionEvent consumed
	 */
	public void query(ActionEvent actionEvent)
	{
		// Create a query
		ElasticSearchQuery query = new ElasticSearchQuery();
		// For each condition listed in the listview, apply that to the overall query
		for (QueryCondition queryCondition : CalliopeData.getInstance().getQueryEngine().getQueryConditions())
			if (queryCondition.isEnabled())
				queryCondition.appendConditionToQuery(query);

		this.currentQuery.setValue(query.build());

		actionEvent.consume();
	}

	/**
	 * Clicked to download the current query's images as a tar file
	 *
	 * @param actionEvent consumed
	 */
	public void downloadQuery(ActionEvent actionEvent)
	{
		// Make sure that popups are enabled
		if (!CalliopeData.getInstance().getSettings().getDisablePopups())
		{
			// Create a directory chooser to pick which directory to download to
			DirectoryChooser directoryChooser = new DirectoryChooser();
			// Set the title of the window
			directoryChooser.setTitle("Pick a directory to download to");
			// Set the initial directory to just be documents folder
			directoryChooser.setInitialDirectory(FileSystemView.getFileSystemView().getDefaultDirectory());
			// Grab the directory to save to
			File dirToSaveTo = directoryChooser.showDialog(this.map.getScene().getWindow());

			// Make sure we got a directory to save to
			if (dirToSaveTo != null)
			{
				// Make sure the directory is a directory, exists, and can be written to
				if (dirToSaveTo.exists() && dirToSaveTo.isDirectory() && dirToSaveTo.canWrite())
				{
					// Grab the current query
					QueryBuilder currentQuery = this.currentQuery.getValue();
					// Make sure it's valid
					if (currentQuery != null)
					{
						this.btnDownloadQuery.setDisable(true);
						// Create a new task to perform the computation
						ErrorTask<Void> errorTask = new ErrorTask<Void>()
						{
							@Override
							protected Void call()
							{
								// Update the users on what the query is doing
								this.updateMessage("Performing query to figure out which images to download...");
								// Perform the query
								List<String> absoluteImagePaths = CalliopeData.getInstance().getEsConnectionManager().getImagePathsMatching(currentQuery);
								// Update the users again
								this.updateMessage("Downloading images into '" + dirToSaveTo.getAbsolutePath() + "'...");
								// Create a callback so we can easily update our task progress
								DoubleProperty progressCallback = new SimpleDoubleProperty(0);
								progressCallback.addListener((observable, oldValue, newValue) -> this.updateProgress(newValue.doubleValue(), 1.0));
								// Call the final function to download data to disk
								CalliopeData.getInstance().getCyConnectionManager().downloadImages(absoluteImagePaths, dirToSaveTo, progressCallback);
								return null;
							}
						};
						errorTask.setOnSucceeded(event -> this.btnDownloadQuery.setDisable(false));
						// Execute the task
						CalliopeData.getInstance().getExecutor().getImmediateExecutor().addTask(errorTask, true);
					}
				}
				else
				{
					// If the directory is invalid, show an error
					CalliopeData.getInstance().getErrorDisplay().notify("The directory chosen must exist and be writable!");
				}
			}
		}
		else
		{
			// If popups are disabled show an error
			CalliopeData.getInstance().getErrorDisplay().notify("Popups must be enabled to download a query!");
		}

		actionEvent.consume();
	}

	/**
	 * Called to add the current filter to the analysis
	 *
	 * @param mouseEvent consumed
	 */
	public void clickedAdd(MouseEvent mouseEvent)
	{
		// If a filter was clicked, we instantiate it and append it to the end of the list (-1 so that the + is at the end)
		ObservableList<QueryCondition> queryConditions = CalliopeData.getInstance().getQueryEngine().getQueryConditions();
		if (this.lvwFilters.getSelectionModel().selectedItemProperty().getValue() != null)
			queryConditions.add(this.lvwFilters.getSelectionModel().selectedItemProperty().getValue().createInstance());
		mouseEvent.consume();
	}

	/**
	 * Called when the mouse enters the arrow image
	 *
	 * @param mouseEvent consumed
	 */
	public void mouseEnteredArrow(MouseEvent mouseEvent)
	{
		imgArrow.setImage(HIGHLIGHTED_ARROW);
		mouseEvent.consume();
	}

	/**
	 * Called when the mouse exits the arrow image
	 *
	 * @param mouseEvent consumed
	 */
	public void mouseExitedArrow(MouseEvent mouseEvent)
	{
		imgArrow.setImage(STANDARD_ARROW);
		mouseEvent.consume();
	}

	/**
	 * Called whenever a filter is clicked on the filters list view
	 *
	 * @param mouseEvent consumed
	 */
	public void clickedFilters(MouseEvent mouseEvent)
	{
		if (mouseEvent.getClickCount() == 2)
			this.clickedAdd(mouseEvent);
		mouseEvent.consume();
	}

	/**
	 * If we drag over the map we test if the drag came from a polygon point list entry which means we are dropping a point on the map
	 *
	 * @param dragEvent accepted if the drag came from the polygon point list entry
	 */
	public void dragOver(DragEvent dragEvent)
	{
		Dragboard dragboard = dragEvent.getDragboard();
		// We can test if the polygon point list entry was the origin if it has the right content
		if (dragboard.hasContent(CalliopeDataFormats.AWAITING_POLYGON))
		{
			// Accept this drag
			dragEvent.acceptTransferModes(TransferMode.COPY);
			dragEvent.consume();
		}
	}

	/**
	 * If we drop the location icon on the map, figure out where it was placed and add a new clipboard content to the dragboard to return
	 *
	 * @param dragEvent Updated with a new dragboard content to contain the latitude and longitude of the drop point
	 */
	public void dragDropped(DragEvent dragEvent)
	{
		Dragboard dragboard = dragEvent.getDragboard();
		// Test if the dragboard came from the right place (ie, it's awaiting lat/long coords)
		if (dragboard.hasContent(CalliopeDataFormats.AWAITING_POLYGON))
		{
			ClipboardContent newContent = new ClipboardContent();

			// Grab the drop location's mouse x and mouse y
			double mouseX = dragEvent.getX();
			double mouseY = dragEvent.getY();
			// Compute the latitude and longitude of the mouse x/y position
			Location location = this.map.getProjection().viewportPointToLocation(new Point2D(mouseX, mouseY));
			// Store the latitude and longitude into the dragboard content
			newContent.put(CalliopeDataFormats.POLYGON_LATITUDE_FORMAT, location.getLatitude());
			newContent.put(CalliopeDataFormats.POLYGON_LONGITUDE_FORMAT, location.getLongitude());
			dragboard.setContent(newContent);

			// Drop is done
			dragEvent.setDropCompleted(true);
		}
		else
		{
			// Drop is not done because we got the incorrect content
			dragEvent.setDropCompleted(false);
		}
		// Consume the drag
		dragEvent.consume();
	}
}
