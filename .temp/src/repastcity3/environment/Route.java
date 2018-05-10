package repastcity3.environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Map;
import java.util.Vector;

import org.apache.commons.lang3.ArrayUtils;
import org.geotools.referencing.GeodeticCalculator;
import org.geotools.referencing.datum.DefaultEllipsoid;

import cern.colt.Arrays;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.Point;
import com.vividsolutions.jts.operation.distance.DistanceOp;

import repast.simphony.space.gis.Geography;
import repast.simphony.space.graph.RepastEdge;
import repast.simphony.space.graph.ShortestPath;
import repastcity3.agent.IAgent;
import repastcity3.exceptions.RoutingException;
import repastcity3.main.ContextManager;
import repastcity3.main.GlobalVars;

public class Route implements Cacheable {

	private static Logger LOGGER = Logger.getLogger(Route.class.getName());

	private IAgent agent;
	private Coordinate destination;
	private Residential destinationBuilding;
	private double shortestPathLength;

	private int currentPosition; 				// Đếm bước nhảy
	private List<Coordinate> routeX;
	
	private List<Road> roadsX;
	private List<String> routeDescriptionX;
	private static volatile Map<Coordinate, List<Road>> coordCache;
	private static volatile NearestRoadCoordCache nearestRoadCoordCache;
	
	private static Object buildingsOnRoadCacheLock = new Object();

	public Route(IAgent agent, Coordinate destination, Residential destinationBuilding) {
		this.destination = destination;
		this.agent = agent;
		this.destinationBuilding = destinationBuilding;
	}
	
	public Route(IAgent agent, Coordinate destination) {
		this.destination = destination;
		this.agent = agent;
	}

	protected void setRoute() throws Exception {
		long time = System.nanoTime();
		this.routeX = new Vector<Coordinate>();
		this.roadsX = new Vector<Road>();
		this.routeDescriptionX = new Vector<String>();

		if (atDestination()) {
			LOGGER.log(Level.WARNING, "Already at destination, cannot create a route for " + this.agent.toString());
			return;
		}

		Coordinate currentCoord = ContextManager.getAgentGeometry(this.agent).getCoordinate();
		Coordinate destCoord = this.destination;

		try {
			boolean destinationOnRoad = true;
			Coordinate finalDestination = null;
			if (!coordOnRoad(currentCoord)) {
				currentCoord = getNearestRoadCoord(currentCoord);
				addToRoute(currentCoord, Road.nullRoad, "setRoute() initial");
			}
			if (!coordOnRoad(destCoord)) {
				destinationOnRoad = false;
				finalDestination = destCoord;
				destCoord = getNearestRoadCoord(destCoord);
			}

			Road currentRoad = Route.findNearestObject(currentCoord, ContextManager.roadProjection, null, GlobalVars.GEOGRAPHY_PARAMS.BUFFER_DISTANCE.LARGE);
			List<Junction> currentJunctions = currentRoad.getJunctions();
			Road destRoad = Route.findNearestObject(destCoord, ContextManager.roadProjection, null, GlobalVars.GEOGRAPHY_PARAMS.BUFFER_DISTANCE.LARGE);
			List<Junction> destJunctions = destRoad.getJunctions();
			
			Junction[] routeEndpoints = new Junction[2];
			List<RepastEdge<Junction>> shortestPath = getShortestRoute(currentJunctions, destJunctions, routeEndpoints);
			
			Junction currentJunction = routeEndpoints[0];					//Điểm đầu (trong tuyến đường ngắn nhất)
			Junction destJunction = routeEndpoints[1];						//Điểm cuối (trong tuyến đường ngắn nhất)

			List<Coordinate> tempCoordList = new Vector<Coordinate>();
			this.getCoordsAlongRoad(currentCoord, currentJunction.getCoords(), currentRoad, true, tempCoordList);
			addToRoute(tempCoordList, currentRoad, "getCoordsAlongRoad (toJunction)");

			this.getRouteBetweenJunctions(shortestPath, currentJunction);

			tempCoordList.clear();
			this.getCoordsAlongRoad(ContextManager.junctionGeography.getGeometry(destJunction).getCoordinate(),
																								destCoord, destRoad, false, tempCoordList);
			addToRoute(tempCoordList, destRoad, "getCoordsAlongRoad (fromJunction)");

			if (!destinationOnRoad) {
				addToRoute(finalDestination, Road.nullRoad, "setRoute final");
			}

			checkListSizes();
			checkListSizes();

		} catch (RoutingException e) {
			throw e;
		}

		LOGGER.log(Level.FINER, "Route Finished planning route for " + this.agent.toString() + "with "
				+ this.routeX.size() + " coords in " + (0.000001 * (System.nanoTime() - time)) + "ms.");

		// Finished, just check that the route arrays are all in sync
		assert this.roadsX.size() == this.routeX.size() && this.roadsX.size() == this.routeDescriptionX.size();
	}

	private void checkListSizes() {
		assert this.roadsX.size() > 0 && this.roadsX.size() == this.routeX.size()
				&& this.roadsX.size() == this.routeDescriptionX.size() : this.routeX.size() + "," + this.roadsX.size()
				+ "," + this.routeDescriptionX.size();

	}

	private void addToRoute(Coordinate coord, Road road, String description) {
		this.routeX.add(coord);
		this.roadsX.add(road);
		this.routeDescriptionX.add(description);
	}

	private void addToRoute(List<Coordinate> coords, Road road, String description) {
		for (Coordinate c : coords) {
			this.routeX.add(c);
			this.roadsX.add(road);
			this.routeDescriptionX.add(description);
		}
	}

	public void travel() throws Exception {
		if (this.routeX == null) {
			this.setRoute();
		}
		try {
			if (this.atDestination()) {
				return;
			}
			double distTravelled = 0;
			Coordinate currentCoord = null;
			Coordinate target = null;
			boolean travelledMaxDist = false;
			double speed;
			GeometryFactory geomFac = new GeometryFactory();
			currentCoord = ContextManager.getAgentGeometry(this.agent).getCoordinate();
			
			while (!travelledMaxDist && !this.atDestination()) {
				target = this.routeX.get(this.currentPosition);
				speed = this.agent.getSpeed();
				
				double[] distAndAngle = new double[2];
				Route.distance(currentCoord, target, distAndAngle);
				double distToTarget = distAndAngle[0] / speed;
				
				
					for (IAgent r : ContextManager.agentContext.getObjects(IAgent.class)) {
						Coordinate otherCoord = ContextManager.getAgentGeometry(r).getCoordinate();
//						DefaultEllipsoid ellip = DefaultEllipsoid.WGS84;
//						double distance = ellip.orthodromicDistance(target.x, target.y, otherCoord.x, otherCoord.y);
//						double[] distanceTwoAgent = new double[2];
//						Route.distance(target, ContextManager.getAgentGeometry(r).getCoordinate(), distanceTwoAgent);
						if (target == otherCoord) {
							speed = 0;
							break;
						}
//						Road otherRoad = Route.findNearestObject(ContextManager.getAgentGeometry(r).getCoordinate(), 
//																ContextManager.roadProjection, null, GlobalVars.GEOGRAPHY_PARAMS.BUFFER_DISTANCE.LARGE);
//						if (thisRoad.toString().equals(otherRoad.toString())) {
//							double[] distanceTwoAgent = new double[2]; 
//							Route.distance(target, ContextManager.getAgentGeometry(r).getCoordinate(), distanceTwoAgent);
//							if(distanceTwoAgent[1] <= 1) {
//								speed = 0;
//							}
//						}
						
					}
				
				
				
				if (distTravelled + distToTarget < GlobalVars.GEOGRAPHY_PARAMS.TRAVEL_PER_TURN) {
					distTravelled += distToTarget;
					currentCoord = target;

					if (this.currentPosition == (this.routeX.size() - 1)) {
						ContextManager.moveAgent(this.agent, geomFac.createPoint(currentCoord));
						break;
					}
					this.currentPosition++;
					
				} else {
					double distToTravel = (GlobalVars.GEOGRAPHY_PARAMS.TRAVEL_PER_TURN - distTravelled) * speed;
					ContextManager.moveAgentByVector(this.agent, distToTravel, distAndAngle[1]);
					travelledMaxDist = true;
				}
			}
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, "Route.trave(): Caught error travelling for " + this.agent.toString()
					+ " going to " + "destination "
					+ (this.destinationBuilding == null ? "" : this.destinationBuilding.toString() + ")"));
			throw e;
		}
	}
	
	
	public double getShortestPathLength() {		
		return this.shortestPathLength;
	}

	/**
	 * Find the nearest coordinate which is part of a Road. Returns the coordinate which is actually the closest to the
	 * given coord, not just the corner of the segment which is closest. Uses the DistanceOp class which finds the
	 * closest points between two geometries.
	 * <p>
	 * When first called, the function will populate the 'nearestRoadCoordCache' which calculates where the closest road
	 * coordinate is to each building. The agents will commonly start journeys from within buildings so this will
	 * improve efficiency.
	 * </p>
	 * 
	 * @param inCoord
	 *            The coordinate from which to find the nearest road coordinate
	 * @return the nearest road coordinate
	 * @throws Exception
	 */
	private synchronized Coordinate getNearestRoadCoord(Coordinate inCoord) throws Exception {

		synchronized (buildingsOnRoadCacheLock) {
			if (nearestRoadCoordCache == null) {
				LOGGER.log(Level.FINE, "Route.getNearestRoadCoord called for first time, "
						+ "creating cache of all roads and the buildings which are on them ...");
				String gisDir = ContextManager.getProperty(GlobalVars.GISDataDirectory);
				File buildingsFile = new File(gisDir + ContextManager.getProperty(GlobalVars.ResidentialShapefile));
				File roadsFile = new File(gisDir + ContextManager.getProperty(GlobalVars.RoadShapefile));
				File serialisedLoc = new File(gisDir + ContextManager.getProperty(GlobalVars.BuildingsRoadsCoordsCache));

				nearestRoadCoordCache = NearestRoadCoordCache.getInstance(ContextManager.residentialProjection,
						buildingsFile, ContextManager.roadProjection, roadsFile, serialisedLoc, new GeometryFactory());
			}
		}
		return nearestRoadCoordCache.get(inCoord);
	}

	/**
	 * Tìm tuyến đường ngắn nhất giữa điểm đầu và điểm đến. Trả về đường đi ngắn nhất và 2 điểm mút tạo nên tuyến đường ngắn nhất 
	 */
	private List<RepastEdge<Junction>> getShortestRoute(List<Junction> currentJunctions, List<Junction> destJunctions,
															Junction[] routeEndpoints) throws Exception {
		double time = System.nanoTime();
		synchronized (GlobalVars.TRANSPORT_PARAMS.currentBurglarLock) {
			
			GlobalVars.TRANSPORT_PARAMS.currentAgent = this.agent;
			this.shortestPathLength = Double.MAX_VALUE;
			double pathLength = 0;
			ShortestPath<Junction> p;
			List<RepastEdge<Junction>> shortestPath = null;
			
			for (Junction o : currentJunctions) {
				for (Junction d : destJunctions) {
					if (o == null || d == null) {
						LOGGER.log(Level.WARNING, "Route.getShortestRoute() error: either the destination or origin "
											+ "junction is null. This can be caused by disconnected roads. It's probably OK"
											+ "to ignore this as a route should still be created anyway.");
					} else {
						p = new ShortestPath<Junction>(ContextManager.roadNetwork);
						pathLength = p.getPathLength(o,d);		//Lấy chiều dài từ o -> d
						if (pathLength < this.shortestPathLength) {
							this.shortestPathLength = pathLength;
							shortestPath = p.getPath(o,d);
							routeEndpoints[0] = o;
							routeEndpoints[1] = d;
						}
						p.finalize();
						p = null;
					}
				}
			}
			
			if (shortestPath == null) {
				String debugString = "Route.getShortestRoute() could not find a route. Looking for the shortest route between :\n";
				for (Junction j : currentJunctions)
					debugString += "\t" + j.toString() + ", roads: " + j.getRoads().toString() + "\n";
				for (Junction j : destJunctions)
					debugString += "\t" + j.toString() + ", roads: " + j.getRoads().toString() + "\n";
				throw new RoutingException(debugString);
			}
			LOGGER.log(Level.FINER, "Route.getShortestRoute (" + (0.000001 * (System.nanoTime() - time))
					+ "ms) found shortest path " + "(length: " + this.shortestPathLength + ") from "
					+ routeEndpoints[0].toString() + " to " + routeEndpoints[1].toString());
			return shortestPath;
		}
	}

	/**
	 * Tính toán các tọa độ cần thiết để di chuyển một tác nhân từ vị trí hiện tại của chúng đến điểm đến dọc theo một con đường cụ thể.
	 * <ol>
	 * <li>Starting from the destination coordinate, record each vertex and check inside the booundary of each line
	 * segment until the destination point is found.</li>
	 * <li>Return all but the last vertex, this is the route to the destination.</li>
	 * </ol>
	 * A boolean allows for two cases: heading towards a junction (the endpoint of the line) or heading away from the
	 * endpoint of the line (this function can't be used to go to two midpoints on a line)
	 * 
	 */
	private void getCoordsAlongRoad(Coordinate currentCoord, Coordinate destinationCoord, Road road,
														boolean toJunction, List<Coordinate> coordList) throws RoutingException {

		Route.checkNotNull(currentCoord, destinationCoord, road, coordList);

		double time = System.nanoTime();
		Coordinate[] roadCoords = ContextManager.roadProjection.getGeometry(road).getCoordinates();
		
		boolean currentCorrect = false, 
				destinationCorrect = false;						//Kiểm tra tọa độ hiện tại / đích đến có thuộc 1 tuyến đường ko 
		for (int i = 0; i < roadCoords.length; i++) {
			if (toJunction && destinationCoord.equals(roadCoords[i])) {
				destinationCorrect = true;
				break;
			} else if (!toJunction && currentCoord.equals(roadCoords[i])) {
				currentCorrect = true;
				break;
			}
		}

		if (!(destinationCorrect || currentCorrect)) {
			throw new RoutingException("currentCoord và destinationCoord đều nằm trên 1 tuyến đường");
		}

		if (toJunction && !destinationCoord.equals(roadCoords[roadCoords.length - 1])) {
			// If heading towards a junction, destination coordinate must be at end of road segment
			ArrayUtils.reverse(roadCoords);
		} else if (!toJunction && !currentCoord.equals(roadCoords[0])) {
			// If heading away form junction current coord must be at beginning of road segment
			ArrayUtils.reverse(roadCoords);
		}
		
		GeometryFactory geomFac = new GeometryFactory();
		Point destinationPointGeom = geomFac.createPoint(destinationCoord);
		Point currentPointGeom = geomFac.createPoint(currentCoord);
		
		boolean foundAllCoords = false; 				// Kiểm tra thuật toán có hoạt động ok ko? 
		search: for (int i = 0; i < roadCoords.length - 1; i++) {
			Coordinate[] segmentCoords = new Coordinate[] { roadCoords[i], roadCoords[i + 1] };
			Geometry buffer = geomFac.createLineString(segmentCoords).buffer(GlobalVars.GEOGRAPHY_PARAMS.BUFFER_DISTANCE.SMALL.dist);
			if (!toJunction) {
				/* Nếu đi ra khỏi đường giao nhau, tiếp tục thêm các tuyến đường cho đến tìm thấy điểm đến */
				coordList.add(roadCoords[i]);
				if (destinationPointGeom.within(buffer)) {
					coordList.add(destinationCoord);
					foundAllCoords = true;
					break search;
				}
			} else if (toJunction) {
				if (currentPointGeom.within(buffer)) {
					for (int j = i + 1; j < roadCoords.length; j++) {
						coordList.add(roadCoords[j]);
					}
					coordList.add(destinationCoord);
					foundAllCoords = true;
					break search;
				}
			}
		}
		if (foundAllCoords) { // Thuật toán hoạt động ok 
			LOGGER.log(Level.FINER, "getCoordsAlongRoad (" + (0.000001 * (System.nanoTime() - time)) + "ms)");
			return;
		} else {
			String error = "Route: getCoordsAlongRoad: could not find destination coordinates "
					+ "along the road.\n\tHeading *" + (toJunction ? "towards" : "away from")
					+ "* a junction.\n\t Person: " + this.agent.toString() + ")\n\tDestination building: "
					/**+ destinationBuilding.toString()*/ + "\n\tRoad causing problems: " + road.toString()
					+ "\n\tRoad vertex coordinates: " + Arrays.toString(roadCoords);
			throw new RoutingException(error);
		}
	}

	private static void checkNotNull(Object... args) throws RoutingException {
		for (Object o : args) {
			if (o == null) {
				throw new RoutingException("An input argument is null");
			}
		}
		return;
	}

	/**
	 * Trả về tất cả các tọa độ mô tả cách di chuyển dọc theo một tuyến đường ngắn nhất
	 */
	private void getRouteBetweenJunctions(List<RepastEdge<Junction>> shortestPath, Junction startingJunction) throws RoutingException {		
		double time = System.nanoTime();
		if (shortestPath.size() < 1) {
			return;
		}
		synchronized (GlobalVars.TRANSPORT_PARAMS.currentBurglarLock) {
			
			GlobalVars.TRANSPORT_PARAMS.currentAgent = this.agent;
			NetworkEdge<Junction> e;
			Road r;
			boolean sourceFirst;
			
			for (int i = 0; i < shortestPath.size(); i++) {
				e = (NetworkEdge<Junction>) shortestPath.get(i);
				
				if (i == 0) {
					sourceFirst = (e.getSource().equals(startingJunction)) ? true : false;
				} else {
					sourceFirst = (e.getSource().getCoords().equals(this.routeX.get(this.routeX.size() - 1))) ? true : false;
				}
				
				r = e.getRoad();

				if (r == null) {
					if (sourceFirst) {
						this.addToRoute(e.getSource().getCoords(), r, "getRouteBetweenJunctions - no road");
						this.addToRoute(e.getTarget().getCoords(), r, "getRouteBetweenJunctions - no road");
					} else {
						this.addToRoute(e.getTarget().getCoords(), r, "getRouteBetweenJunctions - no road");
						this.addToRoute(e.getSource().getCoords(), r, "getRouteBetweenJunctions - no road");
					}
				} else {
					Coordinate[] roadCoords = ContextManager.roadProjection.getGeometry(r).getCoordinates();
					
					if (roadCoords.length < 2)
						throw new RoutingException("Route.getRouteBetweenJunctions: for some reason road " + "'"
								+ r.toString() + "' doesn't have at least two coords as part of its geometry ("
								+ roadCoords.length + ")");
					
					if (!sourceFirst) { // Đảm bảo các tọa độ của đường được thêm theo đúng thứ tự
						ArrayUtils.reverse(roadCoords);
					}
					
					for (int j = 0; j < roadCoords.length; j++) {
						this.addToRoute(roadCoords[j], r, "getRouteBetweenJuctions - on road");
					}
				}
			}
			// Check all lists are still the same size.
			assert this.roadsX.size() == this.routeX.size() && this.roadsX.size() == this.routeDescriptionX.size();

			// Check all lists are still the same size.
			assert this.roadsX.size() == this.routeX.size() && this.roadsX.size() == this.routeDescriptionX.size();

			// Finished!
			LOGGER.log(Level.FINER, "getRouteBetweenJunctions (" + (0.000001 * (System.nanoTime() - time)) + "ms");
			return;
		}
	}

	public boolean atDestination() {
		return ContextManager.getAgentGeometry(this.agent).getCoordinate().equals(this.destination);
	}
	
	/**
	 * Tìm đường gần điểm nhất
	 */
	public static synchronized <T> T findNearestObject(Coordinate x, Geography<T> geography,
			List<Coordinate> closestPoints, GlobalVars.GEOGRAPHY_PARAMS.BUFFER_DISTANCE searchDist)
			throws RoutingException {
		if (x == null) {
			throw new RoutingException("The input coordinate is null, cannot find the nearest object");
		}

		T nearestObject = SpatialIndexManager.findNearestObject(geography, x, closestPoints, searchDist);

		if (nearestObject == null) {
			throw new RoutingException("Couldn't find an object close to these coordinates:\n\t" + x.toString());
		} else {
			return nearestObject;
		}
	}

	/**
	 * Trả về góc của vector p0 -> p1
	 */
	public static synchronized double angle(Coordinate p0, Coordinate p1) {
		double dx = p1.x - p0.x;
		double dy = p1.y - p0.y;

		return Math.atan2(dy, dx);
	}

	/**
	 * The coordinate the route is targeting
	 */
	public Coordinate getDestination() {
		return this.destination;
	}

	/**
	 * Kiểm tra xem tọa độ có nằm trên 1 con đường nào đó hay không.ent
	 */
	private boolean coordOnRoad(Coordinate coord) {
		populateCoordCache(); // check the cache has been populated
		return coordCache.containsKey(coord);
	}

	private synchronized static void populateCoordCache() {

		double time = System.nanoTime();
		if (coordCache == null) { // Fist check cache has been created
			coordCache = new HashMap<Coordinate, List<Road>>();
			LOGGER.log(Level.FINER,
					"Route.populateCoordCache called for first time, creating new cache of all Road coordinates.");
		}
		if (coordCache.size() == 0) { // Now popualte it if it hasn't already been populated
			LOGGER.log(Level.FINER, "Route.populateCoordCache: is empty, creating new cache of all Road coordinates.");

			for (Road r : ContextManager.roadContext.getObjects(Road.class)) {
				for (Coordinate c : ContextManager.roadProjection.getGeometry(r).getCoordinates()) {
					if (coordCache.containsKey(c)) {
						coordCache.get(c).add(r);
					} else {
						List<Road> l = new ArrayList<Road>();
						l.add(r);
						// TODO Need to put *new* coordinate here? Not use existing one in memory?
						coordCache.put(new Coordinate(c), l);
					}
				}
			}

			LOGGER.log(Level.FINER, "... finished caching all road coordinates (in " + 0.000001
					* (System.nanoTime() - time) + "ms)");
		}
	}


	public static synchronized double distance(Coordinate c1, Coordinate c2, double[] returnVals) {
		DefaultEllipsoid e = DefaultEllipsoid.WGS84;
		double distance = e.orthodromicDistance(c1.x, c1.y, c2.x, c2.y);
		GeodeticCalculator calculator = new GeodeticCalculator(ContextManager.roadProjection.getCRS());
		calculator.setStartingGeographicPoint(c1.x, c1.y);
		calculator.setDestinationGeographicPoint(c2.x, c2.y);
		
		if (returnVals != null && returnVals.length == 2) {
			returnVals[0] = distance;
			double angle = Math.toRadians(calculator.getAzimuth()); // Tính số đo góc, đơn vị = PI
			if (angle > 0 && angle < 0.5 * Math.PI) { 			// NE : Bắc - Đông
				angle = 0.5 * Math.PI - angle;
			} else if (angle >= 0.5 * Math.PI) {				// SE : Nam - Đông
				angle = (-angle) + 2.5 * Math.PI;
			} else if (angle < 0 && angle > -0.5 * Math.PI) { 	// NW : Bắc - Tây
				angle = (-1 * angle) + 0.5 * Math.PI;
			} else { 											// SW : Nam - Tây
				angle = -angle + 0.5 * Math.PI;
			}
			returnVals[1] = angle;
		}
		return distance;
	}

	/**
	 * Converts a distance lat/long distance (e.g. returned by DistanceOp) to meters. The calculation isn't very
	 * accurate because (probably) it assumes that the distance is between two points that lie exactly on a line of
	 * longitude (i.e. one is exactly due north of the other). For this reason the value shouldn't be used in any
	 * calculations which is why it's returned as a String.
	 * 
	 * @param dist
	 *            The distance (as returned by DistanceOp) to convert to meters
	 * @return The approximate distance in meters as a String (to discourage using this approximate value in
	 *         calculations).
	 * @throws Exception
	 * @see com.vividsolutions.jts.operation.distance.DistanceOp
	 */
	public static synchronized String distanceToMeters(double dist) throws Exception {
		// Works by creating two coords (close to a randomly chosen object) which are a certain distance apart
		// then using similar method as other distance() function
		GeodeticCalculator calculator = new GeodeticCalculator(ContextManager.roadProjection.getCRS());
		Coordinate c1 = ContextManager.residentialContext.getRandomObject().getCoords();
		calculator.setStartingGeographicPoint(c1.x, c1.y);
		calculator.setDestinationGeographicPoint(c1.x, c1.y + dist);
		return String.valueOf(calculator.getOrthodromicDistance());
	}

	public void clearCaches() {
		if (coordCache != null)
			coordCache.clear();
		if (nearestRoadCoordCache != null) {
			nearestRoadCoordCache.clear();
			nearestRoadCoordCache = null;
		}
	}

	protected <T> void passedObject(T object, Class<T> clazz) {
		List<T> list = new ArrayList<T>(1);
		list.add(object);
		this.agent.addToMemory(list, clazz);
	}

}

/* ************************************************************************ */

/**
 * Caches the nearest road Coordinate to every building for efficiency 
 * (agents usually/always need to get from the centroids of houses to/from the nearest road).
 * <p>
 * This class can be serialised so that if the GIS data doesn't change it doesn't have to be re-calculated each time.
 * 
 */
class NearestRoadCoordCache implements Serializable {

	private static Logger LOGGER = Logger.getLogger(NearestRoadCoordCache.class.getName());

	private static final long serialVersionUID = 1L;
	private Hashtable<Coordinate, Coordinate> theCache; // The actual cache
	// Check that the road/building data hasn't been changed since the cache was last created
	private File buildingsFile;
	private File roadsFile;
	// The location that the serialised object might be found.
	private File serialisedLoc;
	// The time that this cache was created, can be used to check data hasn't changed since
	private long createdTime;

	private GeometryFactory geomFac;

	private NearestRoadCoordCache(Geography<Residential> buildingEnvironment, File buildingsFile,
			Geography<Road> roadEnvironment, File roadsFile, File serialisedLoc, GeometryFactory geomFac)
			throws Exception {

		this.buildingsFile = buildingsFile;
		this.roadsFile = roadsFile;
		this.serialisedLoc = serialisedLoc;
		this.theCache = new Hashtable<Coordinate, Coordinate>();
		this.geomFac = geomFac;

		LOGGER.log(Level.FINE, "NearestRoadCoordCache() creating new cache with data (and modification date):\n\t"
				+ this.buildingsFile.getAbsolutePath() + " (" + new Date(this.buildingsFile.lastModified()) + ") \n\t"
				+ this.roadsFile.getAbsolutePath() + " (" + new Date(this.roadsFile.lastModified()) + "):\n\t"
				+ this.serialisedLoc.getAbsolutePath());

		populateCache(buildingEnvironment, roadEnvironment);
		this.createdTime = new Date().getTime();
		serialise();
	}

	public void clear() {
		this.theCache.clear();
	}

	private void populateCache(Geography<Residential> buildingEnvironment, Geography<Road> roadEnvironment)
			throws Exception {
		double time = System.nanoTime();
		theCache = new Hashtable<Coordinate, Coordinate>();
		// Iterate over every building and find the nearest road point
		for (Residential b : buildingEnvironment.getAllObjects()) {
			List<Coordinate> nearestCoords = new ArrayList<Coordinate>();
			Route.findNearestObject(b.getCoords(), roadEnvironment, nearestCoords,
					GlobalVars.GEOGRAPHY_PARAMS.BUFFER_DISTANCE.LARGE);
			// Two coordinates returned by closestPoints(), need to find the one which isn't the building coord
			Coordinate nearestPoint = null;
			for (Coordinate c : nearestCoords) {
				if (!c.equals(b.getCoords())) {
					nearestPoint = c;
					break;
				}
			}
			if (nearestPoint == null) {
				throw new Exception("Route.getNearestRoadCoord() error: couldn't find a road coordinate which "
						+ "is close to building " + b.toString());
			}
			theCache.put(b.getCoords(), nearestPoint);
		}
		LOGGER.log(Level.FINER, "Finished caching nearest roads (" + (0.000001 * (System.nanoTime() - time)) + "ms)");
	}

	/**
	 * 
	 * @param c
	 * @return
	 * @throws Exception
	 */
	public Coordinate get(Coordinate c) throws Exception {
		if (c == null) {
			throw new Exception("Route.NearestRoadCoordCache.get() error: the given coordinate is null.");
		}
		double time = System.nanoTime();
		Coordinate nearestCoord = this.theCache.get(c);
		if (nearestCoord != null) {
			LOGGER.log(Level.FINER, "NearestRoadCoordCache.get() (using cache) - ("
					+ (0.000001 * (System.nanoTime() - time)) + "ms)");
			return nearestCoord;
		}
		// If get here then the coord is not in the cache, agent not starting their journey from a house, search for
		// it manually. Search all roads in the vicinity, looking for the point which is nearest the person
		double minDist = Double.MAX_VALUE;
		Coordinate nearestPoint = null;
		Point coordGeom = this.geomFac.createPoint(c);

		// Note: could use an expanding envelope that starts small and gets bigger
		double bufferDist = GlobalVars.GEOGRAPHY_PARAMS.BUFFER_DISTANCE.LARGE.dist;
		double bufferMultiplier = 1.0;
		Envelope searchEnvelope = coordGeom.buffer(bufferDist * bufferMultiplier).getEnvelopeInternal();
		StringBuilder debug = new StringBuilder(); // incase the operation fails

		for (Road r : ContextManager.roadProjection.getObjectsWithin(searchEnvelope)) {

			DistanceOp distOp = new DistanceOp(coordGeom, ContextManager.roadProjection.getGeometry(r));
			double thisDist = distOp.distance();
			// BUG?: if an agent is on a really long road, the long road will not be found by getObjectsWithin because it is not within the buffer
			debug.append("\troad ").append(r.toString()).append(" is ").append(thisDist).append(
					" distance away (at closest point). ");

			if (thisDist < minDist) {
				minDist = thisDist;
				@SuppressWarnings("deprecation")
				Coordinate[] closestPoints = distOp.closestPoints();
				// Two coordinates returned by closestPoints(), need to find the one which isn''t the coord parameter
				debug.append("Closest points (").append(closestPoints.length).append(") are: ").append(
						Arrays.toString(closestPoints));
				nearestPoint = (c.equals(closestPoints[0])) ? closestPoints[1] : closestPoints[0];
				debug.append("Nearest point is ").append(nearestPoint.toString());
				nearestPoint = (c.equals(closestPoints[0])) ? closestPoints[1] : closestPoints[0];
			}
			debug.append("\n");
		}

		if (nearestPoint != null) {
			LOGGER.log(Level.FINER, "NearestRoadCoordCache.get() (not using cache) - ("+ (0.000001 * (System.nanoTime() - time)) + "ms)");
			return nearestPoint;
		}
		/* IF HERE THEN ERROR, PRINT DEBUGGING INFO */
		StringBuilder debugIntro = new StringBuilder(); // Some extra info for debugging
		debugIntro.append("Route.NearestRoadCoordCache.get() error: couldn't find a coordinate to return.\n");
		Iterable<Road> roads = ContextManager.roadProjection.getObjectsWithin(searchEnvelope);
		debugIntro.append("Looking for nearest road coordinate around ").append(c.toString()).append(".\n");
		debugIntro.append("RoadEnvironment.getObjectsWithin() returned ").append(
							   ContextManager.sizeOfIterable(roads) + " roads, printing debugging info:\n");
		debugIntro.append(debug);
		throw new Exception(debugIntro.toString());

	}

	private void serialise() throws IOException {
		double time = System.nanoTime();
		FileOutputStream fos = null;
		ObjectOutputStream out = null;
		try {
			if (!this.serialisedLoc.exists())
				this.serialisedLoc.createNewFile();
			fos = new FileOutputStream(this.serialisedLoc);
			out = new ObjectOutputStream(fos);
			out.writeObject(this);
			out.close();
		} catch (IOException ex) {
			if (serialisedLoc.exists()) {
				// delete to stop problems loading incomplete file next time
				serialisedLoc.delete();
			}
			throw ex;
		}
		LOGGER.log(Level.FINE, "... serialised NearestRoadCoordCache to " + this.serialisedLoc.getAbsolutePath()
				+ " in (" + 0.000001 * (System.nanoTime() - time) + "ms)");
	}

	/**
	 * Used to create a new BuildingsOnRoadCache object. This function is used instead of the constructor directly so
	 * that the class can check if there is a serialised version on disk already. If not then a new one is created and
	 * returned.
	 */
	@SuppressWarnings("resource")
	public synchronized static NearestRoadCoordCache getInstance(Geography<Residential> buildingEnv, File buildingsFile,
			Geography<Road> roadEnv, File roadsFile, File serialisedLoc, GeometryFactory geomFac) throws Exception {
		double time = System.nanoTime();
		// See if there is a cache object on disk.
		if (serialisedLoc.exists()) {
			FileInputStream fis = null;
			ObjectInputStream in = null;
			NearestRoadCoordCache ncc = null;
			try {

				fis = new FileInputStream(serialisedLoc);
				in = new ObjectInputStream(fis);
				ncc = (NearestRoadCoordCache) in.readObject();
				in.close();

				// Check that the cache is representing the correct data and the modification dates are ok
				if (!buildingsFile.getAbsolutePath().equals(ncc.buildingsFile.getAbsolutePath())
						|| !roadsFile.getAbsolutePath().equals(ncc.roadsFile.getAbsolutePath())
						|| buildingsFile.lastModified() > ncc.createdTime || roadsFile.lastModified() > ncc.createdTime) {
					LOGGER.log(Level.FINE, "BuildingsOnRoadCache, found serialised object but it doesn't match the "
							+ "data (or could have different modification dates), will create a new cache.");
				} else {
					LOGGER.log(Level.FINER, "NearestRoadCoordCache, found serialised cache, returning it (in "
							+ 0.000001 * (System.nanoTime() - time) + "ms)");
					return ncc;
				}
			} catch (IOException ex) {
				if (serialisedLoc.exists())
					serialisedLoc.delete(); // delete to stop problems loading incomplete file next tinme
				throw ex;
			} catch (ClassNotFoundException ex) {
				if (serialisedLoc.exists())
					serialisedLoc.delete();
				throw ex;
			}
		}
		// No serialised object, or got an error when opening it, just create a new one
		return new NearestRoadCoordCache(buildingEnv, buildingsFile, roadEnv, roadsFile, serialisedLoc, geomFac);
	}
}
