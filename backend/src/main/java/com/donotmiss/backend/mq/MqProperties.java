package com.donotmiss.backend.mq;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.mq")
public class MqProperties {
    private String exchange = "do-not-miss.events";
    private Queues queues = new Queues();
    private RoutingKeys routingKeys = new RoutingKeys();

    public String getExchange() {
        return exchange;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
    }

    public Queues getQueues() {
        return queues;
    }

    public void setQueues(Queues queues) {
        this.queues = queues;
    }

    public RoutingKeys getRoutingKeys() {
        return routingKeys;
    }

    public void setRoutingKeys(RoutingKeys routingKeys) {
        this.routingKeys = routingKeys;
    }

    public static class Queues {
        private String eventIndex = "do-not-miss.event-index";
        private String growthTag = "do-not-miss.growth-tag";
        private String userProfile = "do-not-miss.user-profile";

        public String getEventIndex() {
            return eventIndex;
        }

        public void setEventIndex(String eventIndex) {
            this.eventIndex = eventIndex;
        }

        public String getGrowthTag() {
            return growthTag;
        }

        public void setGrowthTag(String growthTag) {
            this.growthTag = growthTag;
        }

        public String getUserProfile() {
            return userProfile;
        }

        public void setUserProfile(String userProfile) {
            this.userProfile = userProfile;
        }
    }

    public static class RoutingKeys {
        private String eventIndex = "event.index";
        private String growthTag = "growth-tag.extract";
        private String userProfile = "user-profile.refresh";

        public String getEventIndex() {
            return eventIndex;
        }

        public void setEventIndex(String eventIndex) {
            this.eventIndex = eventIndex;
        }

        public String getGrowthTag() {
            return growthTag;
        }

        public void setGrowthTag(String growthTag) {
            this.growthTag = growthTag;
        }

        public String getUserProfile() {
            return userProfile;
        }

        public void setUserProfile(String userProfile) {
            this.userProfile = userProfile;
        }
    }
}
