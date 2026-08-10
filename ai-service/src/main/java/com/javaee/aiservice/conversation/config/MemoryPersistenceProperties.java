package com.javaee.aiservice.conversation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ai.memory")
public class MemoryPersistenceProperties {

    private boolean enabled;
    private final Schema schema = new Schema();
    private final Legacy legacy = new Legacy();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Schema getSchema() {
        return schema;
    }

    public Legacy getLegacy() {
        return legacy;
    }

    public static class Schema {
        private final AutoMigration autoMigration = new AutoMigration();

        public AutoMigration getAutoMigration() {
            return autoMigration;
        }
    }

    public static class AutoMigration {
        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Legacy {
        private final ImportSettings importSettings = new ImportSettings();

        public ImportSettings getImport() {
            return importSettings;
        }
    }

    public static class ImportSettings {
        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
