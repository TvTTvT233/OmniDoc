package com.javaee.aiservice.taskrun.recovery;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Invalid/duplicated switches must fail startup rather than silently selecting the legacy executor. */
@Component
public class AgentRecoverySettingsGuard {
    public AgentRecoverySettingsGuard(Environment environment) {
        for(String key : new String[]{"ai.agent.recovery.enabled","ai.agent.recovery.schema-auto-migration"}) {
            String value=environment.getProperty(key);
            if(value!=null && !"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value))
                throw new IllegalStateException("INVALID_AGENT_RECOVERY_SWITCH: "+key);
        }
    }
}
