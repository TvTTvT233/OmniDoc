package com.javaee.aiservice.taskrun;

import com.javaee.aiservice.taskrun.recovery.AgentRecoverySettingsGuard;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class AgentRecoverySettingsGuardTest {
    @Test void duplicateOrMisspelledSwitchCannotSilentlyDisableRecovery() {
        assertThatThrownBy(()->new AgentRecoverySettingsGuard(new MockEnvironment().withProperty("ai.agent.recovery.enabled","true,true")))
                .hasMessageContaining("INVALID_AGENT_RECOVERY_SWITCH");
        assertThatThrownBy(()->new AgentRecoverySettingsGuard(new MockEnvironment().withProperty("ai.agent.recovery.enabled","treu")))
                .hasMessageContaining("INVALID_AGENT_RECOVERY_SWITCH");
    }
    @Test void explicitOffAndDefaultAreCompatibleAndExplicitOnIsValid() {
        assertThatCode(()->new AgentRecoverySettingsGuard(new MockEnvironment())).doesNotThrowAnyException();
        assertThatCode(()->new AgentRecoverySettingsGuard(new MockEnvironment().withProperty("ai.agent.recovery.enabled","false"))).doesNotThrowAnyException();
        assertThatCode(()->new AgentRecoverySettingsGuard(new MockEnvironment().withProperty("ai.agent.recovery.enabled","true"))).doesNotThrowAnyException();
    }
}
