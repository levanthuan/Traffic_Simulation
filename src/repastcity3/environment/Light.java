package repastcity3.environment;

import java.util.ArrayList;
import java.util.List;

import repastcity3.agent.IAgent;
import repastcity3.exceptions.NoIdentifierException;

import com.vividsolutions.jts.geom.Coordinate;

public class Light implements FixedGeography{
	
	private List<IAgent> agents;
	
	private String identifier;

	private Coordinate coords;
	
	public Light() {
		this.agents = new ArrayList<IAgent>();
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
			throw new NoIdentifierException("This light has no identifier. This can happen "
					+ "when roads are not initialised correctly (e.g. there is no attribute "
					+ "called 'identifier' present in the shapefile used to create this Road)");
		} else {
			return identifier;
		}
	}
	
	
	public void setIdentifier(String id) {
		this.identifier = id;
	}

	public void addAgent(IAgent a) {
		this.agents.add(a);
	}

	public List<IAgent> getAgents() {
		return this.agents;
	}

	@Override
	public String toString() {
		return "light: " + this.identifier;
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof Light))
			return false;
		Light b = (Light) obj;
		return this.identifier.equals(b.identifier);
	}
	
	
	/**
	 * Return this light unique id number.
	 */
//	@Override
//	public int hashCode() {
//		return this.identifier.hashCode();
//	}	


}
