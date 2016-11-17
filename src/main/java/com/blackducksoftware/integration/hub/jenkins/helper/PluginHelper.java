/*******************************************************************************
 * Copyright (C) 2016 Black Duck Software, Inc.
 * http://www.blackducksoftware.com/
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package com.blackducksoftware.integration.hub.jenkins.helper;

import java.net.URL;

import hudson.Plugin;
import hudson.PluginWrapper;
import jenkins.model.Jenkins;

public class PluginHelper {

    public static final String UNKNOWN_VERSION = "<unknown>";

    public static String getPluginVersion() {
        String pluginVersion = UNKNOWN_VERSION;
        Jenkins jenkins = Jenkins.getInstance();
        if (jenkins != null) {
            // Jenkins still active
            Plugin p = jenkins.getPlugin("blackduck-hub");
            if (p != null) {
                // plugin found
                PluginWrapper pw = p.getWrapper();
                if (pw != null) {
                    pluginVersion = pw.getVersion();
                }
            }
        }
        return pluginVersion;
    }

    public static URL getPluginRootPathURL() {
        URL rootPath = null;
        Jenkins jenkins = Jenkins.getInstance();
        if (jenkins != null) {
            // Jenkins still active
            Plugin p = jenkins.getPlugin("blackduck-hub");

            if (p != null) {
                // plugin found
                PluginWrapper pw = p.getWrapper();
                if (pw != null) {
                    URL url = pw.baseResourceURL;
                    rootPath = url;
                }
            }
        }
        return rootPath;
    }
}
