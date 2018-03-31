/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.management.service.impl;

import io.gravitee.management.service.HealthCheckService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.PeriodicTrigger;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @author Guillaume GILLON
 */

@Component
public class AvailabilityService implements Runnable {

    private static final String AVAILABILITY_PERIOD = "1M";
    private Map<String, Double> map;

    /**
     * Logger.
     */
    private final Logger logger = LoggerFactory.getLogger(AvailabilityService.class);

    @Autowired
    private TaskScheduler scheduler;

    @Autowired
    private HealthCheckService healthCheckService;

    @Value("${availability.listapis.enabled:false}")
    private boolean enabled;

    public void initialize() {
        logger.info("Health Check Scheduled service has been initialized for every day");
        scheduler.schedule(this, new PeriodicTrigger(5, TimeUnit.MINUTES));
    }

    @Override
    public void run() {
        if(enabled) {
            logger.info("Sync healthcheck scheduled");
            map = Collections.synchronizedMap(healthCheckService.getAllApiAvailability("API", "1M" ));
        }
    }

    public Double getApiAvailability(String apiId) {
        Double toReturn = null;
        if(apiId != null && map != null && map.get(apiId) != null) {
            toReturn = map.get(apiId);
        }

        if(map == null && enabled) {
            this.initialize();
        }

        return toReturn;
    }
}
