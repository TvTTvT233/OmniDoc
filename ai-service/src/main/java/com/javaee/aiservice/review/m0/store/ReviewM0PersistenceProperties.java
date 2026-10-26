package com.javaee.aiservice.review.m0.store;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ai.review-m0.persistence")
public class ReviewM0PersistenceProperties {
    private boolean enabled;
    private final Schema schema = new Schema();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Schema getSchema() {
        return schema;
    }

    public static final class Schema {
        private final AutoMigration autoMigration = new AutoMigration();

        public AutoMigration getAutoMigration() {
            return autoMigration;
        }
    }

    public static final class AutoMigration {
        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
