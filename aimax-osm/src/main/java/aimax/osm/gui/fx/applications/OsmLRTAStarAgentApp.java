package aimax.osm.gui.fx.applications;

import aima.core.agent.Action;
import aima.core.agent.Agent;
import aima.core.agent.Environment;
import aima.core.agent.EnvironmentView;
import aima.core.environment.map.*;
import aima.core.search.framework.Node;
import aima.core.search.framework.NodeExpander;
import aima.core.search.framework.SearchForActions;
import aima.core.search.framework.problem.Problem;
import aima.core.search.online.LRTAStarAgent;
import aima.core.search.online.OnlineSearchProblem;
import aima.core.util.CancelableThread;
import aima.core.util.math.geom.shapes.Point2D;
import aima.gui.fx.framework.IntegrableApplication;
import aima.gui.fx.framework.Parameter;
import aima.gui.fx.framework.SimulationPaneBuilder;
import aima.gui.fx.framework.SimulationPaneCtrl;
import aima.gui.fx.views.SimpleEnvironmentViewCtrl;
import aima.gui.util.SearchFactory;
import aimax.osm.data.DataResource;
import aimax.osm.data.EntityClassifier;
import aimax.osm.data.MapWayAttFilter;
import aimax.osm.data.Position;
import aimax.osm.data.entities.EntityViewInfo;
import aimax.osm.data.entities.MapNode;
import aimax.osm.data.entities.MapWay;
import aimax.osm.gui.fx.viewer.MapPaneCtrl;
import aimax.osm.gui.swing.applications.SearchDemoOsmAgentApp;
import aimax.osm.routing.MapAdapter;
import aimax.osm.viewer.DefaultEntityRenderer;
import aimax.osm.viewer.DefaultEntityViewInfo;
import aimax.osm.viewer.MapStyleFactory;
import aimax.osm.viewer.UColor;
import javafx.application.Platform;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;

import java.text.DecimalFormat;
import java.util.HashSet;
import java.util.List;

/**
 * Integrable application which demonstrates how different kinds of search
 * algorithms perform an a route finding scenario based on a real OSM map.
 * Map locations corresponding to expanded nodes are highlighted in green.
 *
 * @author Ruediger Lunde
 *
 */
public class OsmLRTAStarAgentApp extends IntegrableApplication {

	public static void main(String[] args) {
		launch(args);
	}

	public static String PARAM_WAY_SELECTION = "WaySelection";
	public static String PARAM_Q_SEARCH_IMPL = "QSearch";
	public static String PARAM_HEURISTIC = "Heuristic";
    public static String TRACK_NAME = "Track";

	private MapPaneCtrl mapPaneCtrl;
	private SimulationPaneCtrl simPaneCtrl;

	private MapAdapter map;
	private MapEnvironment env;
	/** Heuristic function to be used when performing informed search. */
	protected AdaptableHeuristicFunction heuristic;

    /**
     * Stores those states (Strings with map node ids), whose corresponding
     * search nodes have been expanded during the last search. Quick and dirty
     * solution...
     */
    static final HashSet<Object> visitedStates = new HashSet<Object>();


	@Override
	public String getTitle() { return "OSM LRTA* Agent App"; }

	/**
	 * Defines state view, parameters, and call-back functions and calls the
	 * simulation pane builder to create layout and controller objects.
	 */
	@Override
	public Pane createRootPane() {
		BorderPane root = new BorderPane();

		Parameter[] params = createParameters();

		StackPane mapPane = new StackPane();
		mapPaneCtrl = new MapPaneCtrl(mapPane);
		mapPaneCtrl.loadMap(DataResource.getULMFileResource());

		SimulationPaneBuilder builder = new SimulationPaneBuilder();
		builder.defineParameters(params);
		builder.defineStateView(mapPane);
		builder.defineInitMethod(this::initialize);
		builder.defineSimMethod(this::simulate);
		simPaneCtrl = builder.getResultFor(root);
		simPaneCtrl.setParam(SimulationPaneCtrl.PARAM_SIM_SPEED, 0);

		return root;
	}

	protected Parameter[] createParameters() {
		Parameter p1 = new Parameter(PARAM_WAY_SELECTION, "Use any way", "Travel by car", "Travel by bicycle");
		Parameter p2 = new Parameter(PARAM_HEURISTIC, "0", "SLD");
		p2.setDefaultValueIndex(1);
		return new Parameter[] { p1, p2 };
	}

	/** Is called after each parameter selection change. */
	@Override
	public void initialize() {
		map = new MapAdapter(mapPaneCtrl.getMap());
		switch (simPaneCtrl.getParamValueIndex(PARAM_WAY_SELECTION)) {
			case 0:
				map.setMapWayFilter(MapWayAttFilter.createAnyWayFilter());
				map.ignoreOneways(true);
				break;
			case 1:
				map.setMapWayFilter(MapWayAttFilter.createCarWayFilter());
				map.ignoreOneways(false);
				break;
			case 2:
				map.setMapWayFilter(MapWayAttFilter.createBicycleWayFilter());
				map.ignoreOneways(false);
				break;
		}

		switch (simPaneCtrl.getParamValueIndex(PARAM_HEURISTIC)) {
		case 0:
			heuristic = new H1();
			break;
		default:
			heuristic = new H2();
		}

        map.getOsmMap().clearTrack(TRACK_NAME);
	}

	/** Creates a new agent and adds them to the current environment. */
	protected void initAgent(List<MapNode> markers) {
		String[] locs = new String[markers.size()];
		for (int i = 0; i < markers.size(); i++) {
			MapNode node = markers.get(i);
			Point2D pt = new Point2D(node.getLon(), node.getLat());
			locs[i] = map.getNearestLocation(pt);
		}
		heuristic.adaptToGoal(locs[1], map);
        env = new MapEnvironment(map);

		Problem p = new BidirectionalMapProblem(map, null, locs[1]);
		OnlineSearchProblem osp = new OnlineSearchProblem(
				p.getActionsFunction(), p.getGoalTest(),
				p.getStepCostFunction());
		Agent agent = new LRTAStarAgent(osp,
				MapFunctionFactory.getPerceptToStateFunction(), heuristic);

        env.addEnvironmentView(new TrackUpdater());
		env.addAgent(agent, locs[0]);
	}


	/** Starts the experiment. */
	public void simulate() {
        List<MapNode> markers = map.getOsmMap().getMarkers();
        if (markers.size() < 2) {
            simPaneCtrl.setStatus("Error: Please set two markers with mouse-left.");
        } else {
		    initAgent(markers);
			while (!env.isDone() && !CancelableThread.currIsCanceled()) {
				env.step();
				simPaneCtrl.waitAfterStep();
			}
			Double travelDistance = env.getAgentTravelDistance(env.getAgents().get(0));
			if (travelDistance != null) {
				DecimalFormat f = new DecimalFormat("#0.0");
				simPaneCtrl.setStatus("Travel distance: "
						+ f.format(travelDistance) + "km");
			}
		}
	}

	@Override
	public void finalize() {
		simPaneCtrl.cancelSimulation();
	}


	/** Visualizes agent positions. Call from simulation thread. */
    private void updateTrack(Agent agent) {
        MapAdapter map = (MapAdapter) env.getMap();
        MapNode node = map.getWayNode(env.getAgentLocation(agent));
        if (node != null) {
            Platform.runLater(() -> map.getOsmMap().addToTrack(TRACK_NAME,
                    new Position(node.getLat(), node.getLon()))
            );
        }
    }

	// helper classes...

	/**
	 * Returns always the heuristic value 0.
	 */
	static class H1 extends AdaptableHeuristicFunction {

		public double h(Object state) {
			return 0.0;
		}
	}

	/**
	 * A simple heuristic which interprets <code>state</code> and {@link #goal}
	 * as location names and uses the straight-line distance between them as
	 * heuristic value.
	 */
	static class H2 extends AdaptableHeuristicFunction {

		public double h(Object state) {
			double result = 0.0;
			Point2D pt1 = map.getPosition((String) state);
			Point2D pt2 = map.getPosition((String) goal);
			if (pt1 != null && pt2 != null)
				result = pt1.distance(pt2);
			return result;
		}
	}

	class TrackUpdater implements EnvironmentView {

        @Override
        public void notify(String msg) { }

        @Override
        public void agentAdded(Agent agent, Environment source) {
			updateTrack(agent);
		}

        /**
         * Reacts on environment changes and updates the tracks.
         */
        @Override
        public void agentActed(Agent agent, Action command, Environment source) {
            if (command instanceof MoveToAction) {
                updateTrack(agent);
            }
        }
    }
}
