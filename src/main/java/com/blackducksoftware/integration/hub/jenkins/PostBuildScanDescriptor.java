/**
 * blackduck-hub
 *
 * Copyright (C) 2018 Black Duck Software, Inc.
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
 */
package com.blackducksoftware.integration.hub.jenkins;

import java.io.IOException;
import java.io.Serializable;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Collections;
import java.util.List;

import javax.servlet.ServletException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.WebMethod;
import org.kohsuke.stapler.verb.POST;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.blackducksoftware.integration.hub.configuration.HubServerConfigValidator;
import com.blackducksoftware.integration.hub.exception.HubIntegrationException;
import com.blackducksoftware.integration.hub.jenkins.exceptions.BDJenkinsHubPluginException;
import com.blackducksoftware.integration.hub.jenkins.helper.BuildHelper;
import com.blackducksoftware.integration.hub.jenkins.helper.JenkinsProxyHelper;
import com.blackducksoftware.integration.hub.jenkins.helper.PluginHelper;
import com.blackducksoftware.integration.hub.jenkins.scan.BDCommonDescriptorUtil;
import com.blackducksoftware.integration.hub.rest.RestConnection;
import com.blackducksoftware.integration.log.IntLogger;
import com.blackducksoftware.integration.log.LogLevel;
import com.blackducksoftware.integration.log.PrintStreamIntLogger;
import com.blackducksoftware.integration.validator.ValidationResults;
import com.cloudbees.plugins.credentials.CredentialsMatcher;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.cloudbees.plugins.credentials.matchers.IdMatcher;

import hudson.Extension;
import hudson.Functions;
import hudson.ProxyConfiguration;
import hudson.model.AbstractProject;
import hudson.model.AutoCompletionCandidates;
import hudson.model.Descriptor;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import hudson.util.IOUtils;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

// This indicates to Jenkins that this is an implementation of an extension
// point. The ordinal implies an order to the UI element. The Post-Build Actions add new actions in descending order
// so have this ordinal as a higher value than the failure condition Post-Build Action
@Extension(ordinal = 2)
public class PostBuildScanDescriptor extends BuildStepDescriptor<Publisher> implements Serializable {
    private static final long serialVersionUID = -3532946484740537334L;

    private static final String FORM_SERVER_URL = "hubServerUrl";

    private static final String FORM_TIMEOUT = "hubTimeout";

    private static final String FORM_TRUST_CERTS = "trustSSLCertificates";

    private static final String FORM_WORKSPACE_CHECK = "hubWorkspaceCheck";

    private static final String FORM_CREDENTIALSID = "hubCredentialsId";

    private HubServerInfo hubServerInfo;

    /**
     * In order to load the persisted global configuration, you have to call load() in the constructor.
     */
    public PostBuildScanDescriptor() {
        super(PostBuildHubScan.class);
        load();
        HubServerInfoSingleton.getInstance().setServerInfo(hubServerInfo);
    }

    /**
     * @return the hubServerInfo
     */
    public HubServerInfo getHubServerInfo() {
        return HubServerInfoSingleton.getInstance().getServerInfo();
    }

    public String getPluginVersion() {
        return PluginHelper.getPluginVersion();
    }

    public String getDefaultProjectName() {
        return "${JOB_NAME}";
    }

    public String getDefaultProjectVersion() {
        return "unnamed";
    }

    public ListBoxModel doFillHubVersionPhaseItems() {
        return BDCommonDescriptorUtil.doFillHubVersionPhaseItems();
    }

    public ListBoxModel doFillHubVersionDistItems() {
        return BDCommonDescriptorUtil.doFillHubVersionDistItems();
    }

    public String getHubServerUrl() {
        return (getHubServerInfo() == null ? "" : (getHubServerInfo().getServerUrl() == null ? "" : getHubServerInfo().getServerUrl()));
    }

    /**
     * We return a String here instead of an int or Integer because the UI needs a String to display correctly
     */
    public String getDefaultTimeout() {
        return String.valueOf(HubServerInfo.getDefaultTimeout());
    }

    /**
     * We return a String here instead of an int or Integer because the UI needs a String to display correctly
     */
    public String getHubTimeout() {
        return getHubServerInfo() == null ? getDefaultTimeout() : String.valueOf(getHubServerInfo().getTimeout());
    }

    public String getHubCredentialsId() {
        return (getHubServerInfo() == null ? "" : (getHubServerInfo().getCredentialsId() == null ? "" : getHubServerInfo().getCredentialsId()));
    }

    public boolean getTrustSSLCertificates() {
        return (getHubServerInfo() == null ? true : (getHubServerInfo().shouldTrustSSLCerts()));
    }

    public boolean getHubWorkspaceCheck() {
        return (getHubServerInfo() == null ? true : (getHubServerInfo().isPerformWorkspaceCheck()));
    }

    /**
     * Code from https://github.com/jenkinsci/jenkins/blob/master/core/src/main/java/ hudson/model/AbstractItem.java#L602
     */
    // This global configuration can now be accessed at {jenkinsUrl}/descriptorByName/{package}.{ClassName}/config.xml
    // EX: http://localhost:8080/descriptorByName/com.blackducksoftware.integration.hub.jenkins.PostBuildHubScan/config.xml
    @WebMethod(name = "config.xml")
    public void doConfigDotXml(final StaplerRequest req, final StaplerResponse rsp) throws IOException, ServletException, ParserConfigurationException {
        final ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        boolean changed = false;
        try {
            if (PostBuildScanDescriptor.class.getClassLoader() != originalClassLoader) {
                changed = true;
                Thread.currentThread().setContextClassLoader(PostBuildScanDescriptor.class.getClassLoader());
            }
            Functions.checkPermission(Jenkins.ADMINISTER);
            if (req.getMethod().equals("GET")) {
                // read
                rsp.setContentType("application/xml");
                IOUtils.copy(getConfigFile().getFile(), rsp.getOutputStream());
                return;
            }
            Functions.checkPermission(Jenkins.ADMINISTER);
            if (req.getMethod().equals("POST")) {
                // submission
                updateByXml(new StreamSource(req.getReader()));
                return;
            }
            // huh?
            rsp.sendError(javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST);
        } finally {
            if (changed) {
                Thread.currentThread().setContextClassLoader(originalClassLoader);
            }
        }
    }

    public void updateByXml(final Source source) throws IOException, ParserConfigurationException {
        final Document doc;
        try (final StringWriter out = new StringWriter()) {
            // this allows us to use UTF-8 for storing data,
            // plus it checks any well-formedness issue in the submitted
            // data
            jenkins.util.xml.XMLUtils.safeTransform(source, new StreamResult(out));

            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            final DocumentBuilder builder = factory.newDocumentBuilder();
            final InputSource is = new InputSource(new StringReader(out.toString()));

            doc = builder.parse(is);
        } catch (TransformerException | SAXException e) {
            throw new IOException("Failed to persist configuration.xml", e);
        }

        final HubServerInfo serverInfo = new HubServerInfo();

        if (doc.getElementsByTagName("hubServerInfo").getLength() > 0) {
            final Node hubServerInfoNode = doc.getElementsByTagName("hubServerInfo").item(0);
            if (hubServerInfoNode != null && hubServerInfoNode.getNodeType() == Node.ELEMENT_NODE) {
                final Element hubServerInfoElement = (Element) hubServerInfoNode;

                final Node credentialsNode = hubServerInfoElement.getElementsByTagName("credentialsId").item(0);
                String credentialId = "";
                if (credentialsNode != null && credentialsNode.getChildNodes() != null && credentialsNode.getChildNodes().item(0) != null) {
                    credentialId = credentialsNode.getChildNodes().item(0).getNodeValue();
                    if (credentialId != null) {
                        credentialId = credentialId.trim();
                    }
                }

                final Node serverUrlNode = hubServerInfoElement.getElementsByTagName("serverUrl").item(0);
                String serverUrl = "";
                if (serverUrlNode != null && serverUrlNode.getChildNodes() != null && serverUrlNode.getChildNodes().item(0) != null) {
                    serverUrl = serverUrlNode.getChildNodes().item(0).getNodeValue();
                    if (serverUrl != null) {
                        serverUrl = serverUrl.trim();
                    }
                }
                final Node timeoutNode = hubServerInfoElement.getElementsByTagName("hubTimeout").item(0);
                String hubTimeout = String.valueOf(HubServerInfo.getDefaultTimeout()); // default
                // timeout
                if (timeoutNode != null && timeoutNode.getChildNodes() != null && timeoutNode.getChildNodes().item(0) != null) {
                    hubTimeout = timeoutNode.getChildNodes().item(0).getNodeValue();
                    if (hubTimeout != null) {
                        hubTimeout = hubTimeout.trim();
                    }
                }

                final Node trustSSLCertsNode = hubServerInfoElement.getElementsByTagName("trustSSLCertificates").item(0);
                String trustSSLCertificates = "";
                // timeout
                if (trustSSLCertsNode != null && trustSSLCertsNode.getChildNodes() != null && trustSSLCertsNode.getChildNodes().item(0) != null) {
                    trustSSLCertificates = trustSSLCertsNode.getChildNodes().item(0).getNodeValue();
                    if (trustSSLCertificates != null) {
                        trustSSLCertificates = trustSSLCertificates.trim();
                    }
                }

                final Node workspaceCheckNode = hubServerInfoElement.getElementsByTagName("hubWorkspaceCheck").item(0);
                String hubWorkspaceCheck = "";
                // timeout
                if (workspaceCheckNode != null && workspaceCheckNode.getChildNodes() != null && workspaceCheckNode.getChildNodes().item(0) != null) {
                    hubWorkspaceCheck = workspaceCheckNode.getChildNodes().item(0).getNodeValue();
                    if (hubWorkspaceCheck != null) {
                        hubWorkspaceCheck = hubWorkspaceCheck.trim();
                    }
                }

                serverInfo.setCredentialsId(credentialId);
                serverInfo.setServerUrl(serverUrl);

                int serverTimeout = 300;
                try {
                    serverTimeout = Integer.valueOf(hubTimeout);
                } catch (final NumberFormatException e) {
                    System.err.println("Could not convert the provided timeout : " + hubTimeout + ", to an int value.");
                    e.printStackTrace(System.err);
                }
                serverInfo.setTimeout(serverTimeout);
                serverInfo.setTrustSSLCertificates(Boolean.valueOf(trustSSLCertificates));
                serverInfo.setPerformWorkspaceCheck(Boolean.valueOf(hubWorkspaceCheck));
            }
        }
        hubServerInfo = serverInfo;

        save();
        HubServerInfoSingleton.getInstance().setServerInfo(hubServerInfo);
    }

    @Override
    public boolean isApplicable(final Class<? extends AbstractProject> aClass) {
        return true;
    }

    /**
     * This human readable name is used in the configuration screen.
     */
    @Override
    public String getDisplayName() {
        return Messages.HubBuildScan_getDisplayName();
    }

    @Override
    public boolean configure(final StaplerRequest req, final JSONObject formData) throws Descriptor.FormException {
        // To persist global configuration information,
        // set that to properties and call save().
        final Integer timeout = NumberUtils.toInt(formData.getString(FORM_TIMEOUT), 120);

        hubServerInfo = new HubServerInfo(formData.getString(FORM_SERVER_URL), formData.getString(FORM_CREDENTIALSID), timeout, formData.getBoolean(FORM_TRUST_CERTS), formData.getBoolean(FORM_WORKSPACE_CHECK));
        save();
        HubServerInfoSingleton.getInstance().setServerInfo(hubServerInfo);

        return super.configure(req, formData);
    }

    public FormValidation doCheckScanMemory(@QueryParameter("scanMemory") final String scanMemory) throws IOException, ServletException {
        return BDCommonDescriptorUtil.doCheckScanMemory(scanMemory);
    }

    public FormValidation doCheckBomUpdateMaximumWaitTime(@QueryParameter("bomUpdateMaximumWaitTime") final String bomUpdateMaximumWaitTime) throws IOException, ServletException {
        return BDCommonDescriptorUtil.doCheckBomUpdateMaximumWaitTime(bomUpdateMaximumWaitTime);
    }

    public AutoCompletionCandidates doAutoCompleteHubProjectName(@QueryParameter("value") final String hubProjectName) throws IOException, ServletException {
        return BDCommonDescriptorUtil.doAutoCompleteHubProjectName(getHubServerInfo(), hubProjectName);
    }

    /**
     * Performs on-the-fly validation of the form field 'hubProjectName'. Checks to see if there is already a project in the Hub with this name.
     */
    public FormValidation doCheckHubProjectName(@QueryParameter("hubProjectName") final String hubProjectName, @QueryParameter("hubProjectVersion") final String hubProjectVersion, @QueryParameter("dryRun") final boolean dryRun)
            throws IOException, ServletException {
        return BDCommonDescriptorUtil.doCheckHubProjectName(getHubServerInfo(), hubProjectName, hubProjectVersion, dryRun);
    }

    /**
     * Performs on-the-fly validation of the form field 'hubProjectVersion'. Checks to see if there is already a project in the Hub with this name.
     */
    public FormValidation doCheckHubProjectVersion(@QueryParameter("hubProjectVersion") final String hubProjectVersion, @QueryParameter("hubProjectName") final String hubProjectName, @QueryParameter("dryRun") final boolean dryRun)
            throws IOException, ServletException {
        return BDCommonDescriptorUtil.doCheckHubProjectVersion(getHubServerInfo(), hubProjectVersion, hubProjectName, dryRun);
    }

    ///////////////// Global configuration methods /////////////////

    /**
     * Fills the Credential drop down list in the global config
     * @return
     */
    public ListBoxModel doFillHubCredentialsIdItems() {
        Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);
        ListBoxModel boxModel = null;
        final ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        boolean changed = false;
        try {
            if (PostBuildScanDescriptor.class.getClassLoader() != originalClassLoader) {
                changed = true;
                Thread.currentThread().setContextClassLoader(PostBuildScanDescriptor.class.getClassLoader());
            }
            // Code copied from https://github.com/jenkinsci/git-plugin/blob/f6d42c4e7edb102d3330af5ca66a7f5809d1a48e/src/main/java/hudson/plugins/git/UserRemoteConfig.java
            final CredentialsMatcher credentialsMatcher = CredentialsMatchers.anyOf(CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class));
            boxModel = new StandardListBoxModel().withEmptySelection()
                    .withMatching(credentialsMatcher, CredentialsProvider.lookupCredentials(StandardCredentials.class, Jenkins.getInstance(), ACL.SYSTEM, Collections.<DomainRequirement>emptyList()));
        } finally {
            if (changed) {
                Thread.currentThread().setContextClassLoader(originalClassLoader);
            }
        }
        return boxModel;
    }

    @POST
    public FormValidation doCheckHubTimeout(@QueryParameter("hubTimeout") final String hubTimeout) throws IOException, ServletException {
        Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);
        if (StringUtils.isBlank(hubTimeout)) {
            return FormValidation.error(Messages.HubBuildScan_getPleaseSetTimeout());
        }
        final HubServerConfigValidator validator = new HubServerConfigValidator();
        validator.setTimeout(hubTimeout);

        final ValidationResults results = new ValidationResults();
        validator.validateTimeout(results);

        if (!results.isSuccess()) {
            return FormValidation.error(results.getAllResultString());
        }
        return FormValidation.ok();
    }

    /**
     * Performs on-the-fly validation of the form field 'serverUrl'.
     */
    @POST
    public FormValidation doCheckHubServerUrl(@QueryParameter("hubServerUrl") final String hubServerUrl) throws IOException, ServletException {
        Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);
        ProxyConfiguration proxyConfig = null;
        final Jenkins jenkins = Jenkins.getInstance();
        if (jenkins != null) {
            proxyConfig = jenkins.proxy;
        }
        final HubServerConfigValidator validator = new HubServerConfigValidator();
        validator.setHubUrl(hubServerUrl);
        validator.setAlwaysTrustServerCertificate(getTrustSSLCertificates());
        if (proxyConfig != null) {
            if (JenkinsProxyHelper.shouldUseProxy(hubServerUrl, proxyConfig.noProxyHost)) {
                validator.setProxyHost(proxyConfig.name);
                validator.setProxyPort(proxyConfig.port);
                validator.setProxyUsername(proxyConfig.getUserName());
                validator.setProxyPassword(proxyConfig.getPassword());
            }
        }
        final ValidationResults results = new ValidationResults();
        validator.validateHubUrl(results);

        if (!results.isSuccess()) {
            return FormValidation.error(results.getAllResultString());
        }
        return FormValidation.ok();
    }

    /**
     * Validates that the URL, Username, and Password are correct for connecting to the Hub Server.
     */
    @POST
    public FormValidation doTestConnection(@QueryParameter("hubServerUrl") final String serverUrl, @QueryParameter("hubCredentialsId") final String hubCredentialsId, @QueryParameter("hubTimeout") final String hubTimeout,
            @QueryParameter("trustSSLCertificates") final boolean trustSSLCertificates) {
        Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);
        final ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        boolean changed = false;
        try {
            if (PostBuildScanDescriptor.class.getClassLoader() != originalClassLoader) {
                changed = true;
                Thread.currentThread().setContextClassLoader(PostBuildScanDescriptor.class.getClassLoader());
            }
            if (StringUtils.isBlank(serverUrl)) {
                return FormValidation.error(Messages.HubBuildScan_getPleaseSetServerUrl());
            }
            if (StringUtils.isBlank(hubCredentialsId)) {
                return FormValidation.error(Messages.HubBuildScan_getCredentialsNotFound());
            }

            String credentialUserName = null;
            String credentialPassword = null;

            UsernamePasswordCredentialsImpl credential = null;
            final List<StandardUsernamePasswordCredentials> credentials = CredentialsProvider.lookupCredentials(StandardUsernamePasswordCredentials.class, Jenkins.getInstance(), ACL.SYSTEM, Collections.<DomainRequirement>emptyList());
            final IdMatcher matcher = new IdMatcher(hubCredentialsId);
            for (final StandardCredentials c : credentials) {
                if (matcher.matches(c) && c instanceof UsernamePasswordCredentialsImpl) {
                    credential = (UsernamePasswordCredentialsImpl) c;
                }
            }
            if (credential == null) {
                return FormValidation.error(Messages.HubBuildScan_getCredentialsNotFound());
            }
            credentialUserName = credential.getUsername();
            credentialPassword = credential.getPassword().getPlainText();

            IntLogger logger = new PrintStreamIntLogger(System.out, LogLevel.INFO);
            final RestConnection connection = BuildHelper.getRestConnection(logger, serverUrl, credentialUserName, credentialPassword, hubTimeout, trustSSLCertificates);
            connection.connect();
            return FormValidation.ok(Messages.HubBuildScan_getCredentialsValidFor_0_(serverUrl));

        } catch (final IllegalStateException e) {
            return FormValidation.error(e.getMessage());
        } catch (final HubIntegrationException e) {
            final String message;
            if (e.getCause() != null) {
                message = e.getCause().toString();
                if (message.contains("(407)")) {
                    return FormValidation.error(e, message);
                }
            }
            return FormValidation.error(e, e.getMessage());
        } catch (final Exception e) {
            String message = null;
            if (e instanceof BDJenkinsHubPluginException) {
                message = e.getMessage();
            } else if (e.getCause() != null && e.getCause().getCause() != null) {
                message = e.getCause().getCause().toString();
            } else if (e.getCause() != null) {
                message = e.getCause().toString();
            } else {
                message = e.toString();
            }
            if (message.toLowerCase().contains("service unavailable")) {
                message = Messages.HubBuildScan_getCanNotReachThisServer_0_(serverUrl);
            } else if (message.toLowerCase().contains("precondition failed")) {
                message = message + ", Check your configuration.";
            }
            return FormValidation.error(e, message);
        } finally {
            if (changed) {
                Thread.currentThread().setContextClassLoader(originalClassLoader);
            }
        }
    }

    ///////////////// End of global configuration methods /////////////////

}
