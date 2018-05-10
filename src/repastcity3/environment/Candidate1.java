package repastcity3.environment;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;

import repastcity3.environment.Restaurant;
import repast.simphony.query.space.gis.GeographyWithin;
import repastcity3.exceptions.NoIdentifierException;
import repastcity3.main.ContextManager;

public class Candidate1 implements FixedGeography{
	
/** A identifier for this charging station */
	
	public String identifier;
	
	public double subToCanDis;
	
	private Coordinate coords;
	
	private double dis = 2000;
	
	public Candidate1() {
		//this.agents = new ArrayList<IAgent>();
	}
	
	@Override
	public Coordinate getCoords() {
		return this.coords;
	}

	@Override
	public void setCoords(Coordinate c) {
		this.coords = c;

	}
	
	public String getIdentifier() throws NoIdentifierException {
		if (this.identifier == null) {
			throw new NoIdentifierException("This charging station has no identifier. This can happen "
					+ "when roads are not initialised correctly (e.g. there is no attribute "
					+ "called 'identifier' present in the shapefile used to create this Road)");
		} else {
			return identifier;
		}
	}
	
	public void setIdentifier(String id) {
		this.identifier = id;
	}
	
	@Override
	public String toString() {
		return "charging station (Level 1): " + this.identifier;
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof Candidate1))
			return false;
		Candidate1 b = (Candidate1) obj;
		return this.identifier.equals(b.identifier);
	}
	
//	@Override
//	public int hashCode() {
//		return this.identifier.hashCode();
//
//	}
	
	
	@SuppressWarnings("rawtypes")
	public int isRes() {
		
		
		Geometry location = ContextManager.candidate1Projection.getGeometry(this);
		GeographyWithin gquery = new GeographyWithin(ContextManager.restaurantProjection, this.dis, location);
		@SuppressWarnings("unchecked")
		Iterable<Restaurant> it = gquery.query();
		if (it != null && it.iterator().hasNext()) {
			
			return 1;
			
		} else {
			
			return 0;
		}
		
		
		
		
	}
	
	@SuppressWarnings("rawtypes")
	public int isShop() {
		
		Geometry location = ContextManager.candidate1Projection.getGeometry(this);
		GeographyWithin gquery = new GeographyWithin(ContextManager.shoppingcenterProjection, this.dis, location);
		@SuppressWarnings("unchecked")
		Iterable<Shoppingcenter> it = gquery.query();
		if (it != null && it.iterator().hasNext()) {
			return 1;
		} else {
			return 0;
		}
		
	}
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public int isSuper() {
		
		Geometry location = ContextManager.candidate1Projection.getGeometry(this);
		GeographyWithin gquery = new GeographyWithin(ContextManager.supermarketProjection, this.dis, location);
		Iterable<Supermarket> it = gquery.query();
		if (it != null && it.iterator().hasNext()) {
			return 1;
		} else {
			return 0;
		}
		
	}
	
}
	
