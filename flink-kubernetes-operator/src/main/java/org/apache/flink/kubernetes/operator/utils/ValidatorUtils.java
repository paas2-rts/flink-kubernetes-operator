/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.kubernetes.operator.utils;

import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.plugin.PluginUtils;
import org.apache.flink.kubernetes.operator.validation.DefaultValidator;
import org.apache.flink.kubernetes.operator.validation.FlinkResourceValidator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/** Validator utilities. */
public final class ValidatorUtils {

    private static final Logger LOG = LoggerFactory.getLogger(FlinkUtils.class);

    public static Set<FlinkResourceValidator> discoverValidators(Configuration configuration) {
        Set<FlinkResourceValidator> resourceValidators = new HashSet<>();
        resourceValidators.add(new DefaultValidator());
        DefaultValidator defaultValidator = new DefaultValidator();
        defaultValidator.configure(configuration);
        resourceValidators.add(defaultValidator);
        PluginUtils.createPluginManagerFromRootFolder(configuration)
                .load(FlinkResourceValidator.class)
                .forEachRemaining(
                        validator -> {
                            LOG.info(
                                    "Discovered resource validator from plugin directory[{}]: {}.",
                                    System.getenv()
                                            .getOrDefault(
                                                    ConfigConstants.ENV_FLINK_PLUGINS_DIR,
                                                    ConfigConstants.DEFAULT_FLINK_PLUGINS_DIRS),
                                    validator.getClass().getName());
                            validator.configure(configuration);
                            resourceValidators.add(validator);
                        });
        return resourceValidators;
    }
}
