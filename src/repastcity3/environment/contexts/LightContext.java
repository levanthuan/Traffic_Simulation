package repastcity3.environment.contexts;

import repast.simphony.context.DefaultContext;
import repastcity3.environment.Light;
import repastcity3.main.GlobalVars;

public class LightContext extends DefaultContext<Light>{
	
	public LightContext() {
		super(GlobalVars.CONTEXT_NAMES.LIGHT_CONTEXT);
	}

}
