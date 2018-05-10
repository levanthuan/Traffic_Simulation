package repastcity3.agent;

import java.util.Iterator;
import java.util.logging.Logger;

import com.vividsolutions.jts.geom.Geometry;

import repast.simphony.context.Context;
import repastcity3.environment.GISFunctions;
import repastcity3.environment.Residential;
import repastcity3.environment.SpatialIndexManager;
import repastcity3.exceptions.AgentCreationException;
import repastcity3.main.ContextManager;
import repastcity3.main.GlobalVars;

public class AgentFactory {

	private static Logger LOGGER = Logger.getLogger(AgentFactory.class.getName());

	private AGENT_FACTORY_METHODS methodToUse;

	private String definition;
	public AgentFactory(String agentDefinition) throws AgentCreationException {

		String[] split = agentDefinition.split(":");
		if (split.length != 2) {
			throw new AgentCreationException("Problem parsin the definition string '" + agentDefinition
					+ "': it split into " + split.length + " parts but should split into 2.");
		}
		String method = split[0]; // The method to create agents
		String defn = split[1]; // Information about the agents themselves

		if (method.equals(AGENT_FACTORY_METHODS.RANDOM.toString())) {
			this.methodToUse = AGENT_FACTORY_METHODS.RANDOM;

		} else if (method.equals(AGENT_FACTORY_METHODS.POINT_FILE.toString())) {
			this.methodToUse = AGENT_FACTORY_METHODS.POINT_FILE;
		}

		else if (method.equals(AGENT_FACTORY_METHODS.AREA_FILE.toString())) {
			this.methodToUse = AGENT_FACTORY_METHODS.AREA_FILE;
		}

		else {
			throw new AgentCreationException("Unrecognised method of creating agents: '" + method
					+ "'. Method must be one of " + AGENT_FACTORY_METHODS.RANDOM.toString() + ", "
					+ AGENT_FACTORY_METHODS.POINT_FILE.toString() + " or " + AGENT_FACTORY_METHODS.AREA_FILE.toString());
		}

		this.definition = defn;
		this.methodToUse.createAgMeth().createagents(false, this);
	}

	public void createAgents(Context<? extends IAgent> context) throws AgentCreationException {
		this.methodToUse.createAgMeth().createagents(true, this);
	}


	private void createRandomAgents(boolean dummy) throws AgentCreationException {
		int numAgents = 300;

		LOGGER.info("Creating " + numAgents + " agents using " + this.methodToUse + " method.");
		int agentsCreated = 0;
		while (agentsCreated < numAgents/2) {
			Iterator<Residential> i = ContextManager.residentialContext.getRandomObjects(Residential.class, numAgents)
					.iterator();
			while (i.hasNext() && agentsCreated < numAgents/2) {
				Residential b = i.next();
				IAgent a = new DefaultAgent();
				a.setHome(b);
				b.addAgent(a);
				ContextManager.addAgentToContext(a);
				ContextManager.moveAgent(a, ContextManager.residentialProjection.getGeometry(b).getCentroid());
				agentsCreated++;
			}
		}
	}

	@SuppressWarnings("unchecked")
	private void createPointAgents(boolean dummy) throws AgentCreationException {

		// See if there is a single type of agent to create or should read a colum in shapefile
		boolean singleType = this.definition.contains("$");

		String fileName;
		String className;
		Class<IAgent> clazz;
		if (singleType) {
			// Agent class provided, can use the Simphony Shapefile loader to load agents of the given class

			// Work out the file and class names from the agent definition
			String[] split = this.definition.split("\\$");
			if (split.length != 2) {
				throw new AgentCreationException("There is a problem with the agent definition, I should be "
						+ "able to split the definition into two parts on '$', but only split it into " + split.length
						+ ". The definition is: '" + this.definition + "'");
			}
			 // (Need to append root data directory to the filename).
			fileName = ContextManager.getProperty(GlobalVars.GISDataDirectory)+split[0];
			className = split[1];
			// Try to create a class from the given name.
			try {
				clazz = (Class<IAgent>) Class.forName(className);
				GISFunctions.readAgentShapefile(clazz, fileName, ContextManager.getAgentGeography(), ContextManager.getAgentContext());
			} catch (Exception e) {
				throw new AgentCreationException(e);
			}
		} else {
			// TODO Implement agent creation from shapefile value;
			throw new AgentCreationException("Have not implemented the method of reading agent classes from a "
					+ "shapefile yet.");
		}

		int numAgents = 0;
		for (IAgent a : ContextManager.getAllAgents()) {
			numAgents++;
			Geometry g = ContextManager.getAgentGeometry(a);
			for (Residential b : SpatialIndexManager.search(ContextManager.residentialProjection, g)) {
				if (ContextManager.residentialProjection.getGeometry(b).contains(g)) {
					b.addAgent(a);
					a.setHome(b);
				}
			}
		}

		if (singleType) {
			LOGGER.info("Have created " + numAgents + " of type " + clazz.getName().toString() + " from file "
					+ fileName);
		} else {
			// (NOTE: at the moment this will never happen because not implemented yet.)
			LOGGER.info("Have created " + numAgents + " of different types from file " + fileName);
		}

	}

	private void createAreaAgents(boolean dummy) throws AgentCreationException {
		throw new AgentCreationException("Have not implemented the createAreaAgents method yet.");
	}

	private enum AGENT_FACTORY_METHODS {
		RANDOM("random", new CreateAgentMethod() {
			@Override
			public void createagents(boolean b, AgentFactory af) throws AgentCreationException {
				af.createRandomAgents(b);
			}
		}),
		POINT_FILE("point", new CreateAgentMethod() {
			@Override
			public void createagents(boolean b, AgentFactory af) throws AgentCreationException {
				af.createPointAgents(b);
			}
		}),
		AREA_FILE("area", new CreateAgentMethod() {
			@Override
			public void createagents(boolean b, AgentFactory af) throws AgentCreationException {
				af.createAreaAgents(b);
			}
		});

		String stringVal;
		CreateAgentMethod meth;

		AGENT_FACTORY_METHODS(String val, CreateAgentMethod f) {
			this.stringVal = val;
			this.meth = f;
		}

		public String toString() {
			return this.stringVal;
		}

		public CreateAgentMethod createAgMeth() {
			return this.meth;
		}

		interface CreateAgentMethod {
			void createagents(boolean dummy, AgentFactory af) throws AgentCreationException;
		}
	}

}
