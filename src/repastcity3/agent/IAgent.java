
package repastcity3.agent;

import java.util.List;

import repastcity3.environment.Residential;

public interface IAgent {
		
	void step() throws Exception;

	boolean isThreadable();
	
	public void setDirection();
	public double getDirection();
	public void setSpeed();
	public double getSpeed();
	/**
	 * Set where the agent lives.
	 */
	void setHome(Residential home);
	
	/**
	 * Get the agent's home.
	 */
	Residential getHome();
	
	/**
	 * (Optional). Add objects to the agents memory. Used to keep a record of all the
	 * buildings that they have passed.
	 * @param <T>
	 * @param objects The objects to add to the memory.
	 * @param clazz The type of object.
	 */
	<T> void addToMemory(List<T> objects, Class<T> clazz);
	
	/**
	 * (Optional). Get the transport options available to this agent. E.g.
	 * an agent with a car who also could use public transport would return
	 * <code>{"bus", "car"}</code>. If null then it is assumed that the agent
	 * walks (the slowest of all transport methods). 
	 */
	List<String> getTransportAvailable();
	
	double returnDis(String id);
	
	int getIncome();
	
	int isDes(String id);
	
	void calculateDis();
	
	void calDisDes2Can();
	
}
