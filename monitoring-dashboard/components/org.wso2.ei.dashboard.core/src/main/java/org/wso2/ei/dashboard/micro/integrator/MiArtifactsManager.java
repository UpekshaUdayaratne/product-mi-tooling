/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 *
 */

package org.wso2.ei.dashboard.micro.integrator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.wso2.ei.dashboard.core.commons.utils.HttpUtils;
import org.wso2.ei.dashboard.core.db.manager.DatabaseManager;
import org.wso2.ei.dashboard.core.db.manager.DatabaseManagerFactory;
import org.wso2.ei.dashboard.core.exception.DashboardServerException;
import org.wso2.ei.dashboard.core.exception.ManagementApiException;
import org.wso2.ei.dashboard.core.rest.delegates.ArtifactsManager;
import org.wso2.ei.dashboard.core.rest.delegates.UpdateArtifactObject;
import org.wso2.ei.dashboard.core.rest.delegates.heartbeat.HeartbeatObject;
import org.wso2.ei.dashboard.core.rest.model.UpdatedArtifact;
import org.wso2.ei.dashboard.micro.integrator.commons.Utils;
import org.wso2.micro.integrator.dashboard.utils.ExecutorServiceHolder;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.wso2.ei.dashboard.core.commons.Constants.APIS;
import static org.wso2.ei.dashboard.core.commons.Constants.CARBON_APPLICATIONS;
import static org.wso2.ei.dashboard.core.commons.Constants.CONNECTORS;
import static org.wso2.ei.dashboard.core.commons.Constants.DATA_SERVICES;
import static org.wso2.ei.dashboard.core.commons.Constants.DATA_SOURCES;
import static org.wso2.ei.dashboard.core.commons.Constants.ENDPOINTS;
import static org.wso2.ei.dashboard.core.commons.Constants.INBOUND_ENDPOINTS;
import static org.wso2.ei.dashboard.core.commons.Constants.LIST_ATTRIBUTE;
import static org.wso2.ei.dashboard.core.commons.Constants.LOCAL_ENTRIES;
import static org.wso2.ei.dashboard.core.commons.Constants.MESSAGE_PROCESSORS;
import static org.wso2.ei.dashboard.core.commons.Constants.MESSAGE_STORES;
import static org.wso2.ei.dashboard.core.commons.Constants.PROXY_SERVICES;
import static org.wso2.ei.dashboard.core.commons.Constants.SEQUENCES;
import static org.wso2.ei.dashboard.core.commons.Constants.TASKS;
import static org.wso2.ei.dashboard.core.commons.Constants.TEMPLATES;

/**
 * Fetch, store, update and delete artifact information of registered micro integrator nodes.
 */
public class MiArtifactsManager implements ArtifactsManager {
    private static final Logger logger = LogManager.getLogger(MiArtifactsManager.class);
    private static final String SERVER = "server";
    private static final Set<String> ALL_ARTIFACTS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(PROXY_SERVICES, ENDPOINTS, INBOUND_ENDPOINTS, MESSAGE_PROCESSORS,
                                        MESSAGE_STORES, APIS, TEMPLATES, SEQUENCES, TASKS, LOCAL_ENTRIES, CONNECTORS,
                                        CARBON_APPLICATIONS, DATA_SERVICES, DATA_SOURCES)));
    private final DatabaseManager databaseManager = DatabaseManagerFactory.getDbManager();
    private HeartbeatObject heartbeat = null;
    private UpdateArtifactObject updateArtifactObject = null;

    public MiArtifactsManager(UpdateArtifactObject updateArtifactObject) {
        this.updateArtifactObject = updateArtifactObject;
    }

    public MiArtifactsManager(HeartbeatObject heartbeat) {
        this.heartbeat = heartbeat;
    }

    @Override
    public void runFetchAllExecutorService() {
        Runnable runnable = () -> {
            String nodeId = heartbeat.getNodeId();
            String groupId = heartbeat.getGroupId();
            logger.info("Fetching artifacts from node " + nodeId + " in group " + groupId);
            String accessToken = databaseManager.getAccessToken(groupId, nodeId);
            CloseableHttpResponse response = null;
            try {
                for (String artifactType : ALL_ARTIFACTS) {
                    final String url = heartbeat.getMgtApiUrl().concat(artifactType);
                    response = Utils.doGet(groupId, nodeId, accessToken, url);
                    JsonObject artifacts = HttpUtils.getJsonResponse(response);
                    switch (artifactType) {
                        case TEMPLATES:
                            processTemplates(artifactType, artifacts);
                            break;
                        default:
                            processArtifacts(accessToken, artifactType, artifacts);
                            break;
                    }
                }
                fetchAndStoreServers(accessToken);
            } catch (ManagementApiException e) {
                logger.error("Unable to fetch artifacts/details from node: {} of group: {} due to {} ", nodeId,
                        groupId, e.getMessage(), e);
            }
        };
        ExecutorServiceHolder.getMiArtifactsManagerExecutorService().execute(runnable);
    }

    @Override
    public void runUpdateExecutorService() {
        Runnable runnable = () -> {
           List<UpdatedArtifact> undeployedArtifacts = heartbeat.getUndeployedArtifacts();
           for (UpdatedArtifact artifact : undeployedArtifacts) {
               deleteArtifact(artifact.getType(), artifact.getName());
           }

           List<UpdatedArtifact> deployedArtifacts = heartbeat.getDeployedArtifacts();
            try {
                for (UpdatedArtifact info : deployedArtifacts) {
                    fetchAndStoreArtifact(info);
                }
            } catch (ManagementApiException e) {
                logger.error("Error while fetching updated artifacts", e);
            }
        };
        ExecutorServiceHolder.getMiArtifactsManagerExecutorService().execute(runnable);
    }

    @Override
    public void runDeleteAllExecutorService() {
        Runnable runnable = this::deleteAllArtifacts;
        ExecutorServiceHolder.getMiArtifactsManagerExecutorService().execute(runnable);
    }

    private void processArtifacts(String accessToken, String artifactType, JsonObject artifacts)
            throws ManagementApiException {
        JsonArray list;
        if (artifactType.equals(CARBON_APPLICATIONS)) {
            list = artifacts.get("activeList").getAsJsonArray();
        } else {
            list = artifacts.get(LIST_ATTRIBUTE).getAsJsonArray();
        }
        for (JsonElement element : list) {
            final String artifactName = element.getAsJsonObject().get("name").getAsString();
            JsonObject artifactDetails = new JsonObject();
            if (artifactType.equals(CARBON_APPLICATIONS)) {
                populateCAppDetails(artifactDetails, artifactName,
                        element.getAsJsonObject().get("version").getAsString());
            } else if (artifactType.equals(MESSAGE_STORES)) {
                artifactDetails.addProperty("name", artifactName);
                artifactDetails.addProperty("type", element.getAsJsonObject().get("type").getAsString());
                artifactDetails.addProperty("size", element.getAsJsonObject().get("size").getAsString());
            } else {
                artifactDetails = getArtifactDetails(artifactType, artifactName, accessToken);
            }
            insertArtifact(artifactType, artifactName, artifactDetails);
        }
    }

    private void processTemplates(String artifactType, JsonObject artifacts) {
        JsonArray sequences = artifacts.get("sequenceTemplateList").getAsJsonArray();
        JsonArray endpoints = artifacts.get("endpointTemplateList").getAsJsonArray();

        processTemplates(artifactType, sequences, "Sequence Template");
        processTemplates(artifactType, endpoints, "Endpoint Template");
    }

    private void processTemplates(String artifactType, JsonArray templates, String templateType) {
        for (JsonElement template : templates) {
            final String artifactName = template.getAsJsonObject().get("name").getAsString();
            JsonObject artifactDetails = new JsonObject();
            artifactDetails.addProperty("name", artifactName);
            artifactDetails.addProperty("type", templateType);
            insertArtifact(artifactType, artifactName, artifactDetails);
        }
    }

    private void insertArtifact(String artifactType, String artifactName, JsonObject artifactDetails) {
        boolean isSuccess = databaseManager.insertArtifact(heartbeat.getGroupId(), heartbeat.getNodeId(),
                                                           artifactType, artifactName,
                                                           artifactDetails.toString());
        if (!isSuccess) {
            logger.error("Error occurred while adding " + artifactName);
            addToDelayedQueue();
        }
    }

    private void fetchAndStoreServers(String accessToken) throws ManagementApiException {
        String url = heartbeat.getMgtApiUrl() + SERVER;
        CloseableHttpResponse response = Utils.doGet(heartbeat.getGroupId(), heartbeat.getNodeId(), accessToken, url);
        String stringResponse = HttpUtils.getStringResponse(response);
        storeServerInfo(stringResponse, heartbeat);
    }

    private void storeServerInfo(String stringResponse, HeartbeatObject heartbeat) {
        boolean isSuccess = databaseManager.insertServerInformation(heartbeat, stringResponse);
        if (!isSuccess) {
            logger.error("Error occurred while adding server details of node: " + heartbeat.getNodeId() + " in group "
                      + heartbeat.getGroupId());
            addToDelayedQueue();
        }
    }

    public boolean updateArtifactDetails() throws ManagementApiException {
        if (updateArtifactObject != null) {
            String groupId = updateArtifactObject.getGroupId();
            String nodeId = updateArtifactObject.getNodeId();
            String mgtApiUrl = updateArtifactObject.getMgtApiUrl();
            String accessToken = databaseManager.getAccessToken(groupId, nodeId);
            String artifactType = updateArtifactObject.getType();
            String artifactName = updateArtifactObject.getName();
            JsonObject details = getArtifactDetails(groupId, nodeId, mgtApiUrl, artifactType, artifactName,
                                                    accessToken);
            return databaseManager.updateDetails(artifactType, artifactName, groupId, nodeId, details.toString());
        } else {
            throw new DashboardServerException("Artifact details are invalid");
        }
    }

    private void fetchAndStoreArtifact(UpdatedArtifact info) throws ManagementApiException {
        String artifactType = info.getType();
        if (artifactType.equals(TEMPLATES)) {
            updateTemplates(info);
        } else {
            JsonObject artifactDetails = new JsonObject();
            String artifactName = info.getName();
            String groupId = heartbeat.getGroupId();
            String nodeId = heartbeat.getNodeId();
            if (artifactType.equals(CARBON_APPLICATIONS)) {
                populateCAppDetails(artifactDetails, artifactName, info.getVersion());
            } else {
                String accessToken = databaseManager.getAccessToken(groupId, nodeId);
                artifactDetails = getArtifactDetails(artifactType, artifactName, accessToken);
            }
            databaseManager.insertArtifact(groupId, nodeId, artifactType, artifactName,
                                           artifactDetails.toString());
        }
    }

    private void populateCAppDetails(JsonObject artifactDetails, String artifactName, String artifactVersion) {

        artifactDetails.addProperty("name", artifactName);
        artifactDetails.addProperty("version", artifactVersion);
    }

    private void updateTemplates(UpdatedArtifact info) {
        String artifactName = info.getName();
        String[] splitArray = artifactName.split("_", 2);
        String templateType = splitArray[0];
        artifactName = splitArray[1];
        if (templateType.equals("endpoint")) {
            templateType = "Endpoint Template";
        } else if (templateType.equals("sequence")) {
            templateType = "Sequence Template";
        }
        JsonObject artifactDetails = new JsonObject();
        artifactDetails.addProperty("name", artifactName);
        artifactDetails.addProperty("type", templateType);
        databaseManager.insertArtifact(heartbeat.getGroupId(), heartbeat.getNodeId(), TEMPLATES, artifactName,
                                       artifactDetails.toString());
    }

    private JsonObject getArtifactDetails(String artifactType, String artifactName, String accessToken)
            throws ManagementApiException {
        return getArtifactDetails(heartbeat.getGroupId(), heartbeat.getNodeId(), heartbeat.getMgtApiUrl(), artifactType,
                                  artifactName, accessToken);
    }

    private JsonObject getArtifactDetails(String groupId, String nodeId, String mgtApiUrl, String artifactType,
                                          String artifactName, String accessToken) throws ManagementApiException {
        String getArtifactDetailsUrl = getArtifactDetailsUrl(mgtApiUrl, artifactType, artifactName);
        CloseableHttpResponse artifactDetails = Utils.doGet(groupId, nodeId, accessToken,
                                                            getArtifactDetailsUrl);
        JsonObject jsonResponse = HttpUtils.getJsonResponse(artifactDetails);
        return removeValueAndConfiguration(artifactType, jsonResponse);
    }

    private JsonObject removeValueAndConfiguration(String artifactType, JsonObject jsonResponse) {
        if (artifactType.equals(CONNECTORS) || artifactType.equals(CARBON_APPLICATIONS)) {
            return jsonResponse;
        } else if (artifactType.equals(LOCAL_ENTRIES)) {
            return removeValueFromResponse(jsonResponse);
        } else {
            return removeConfigurationFromResponse(jsonResponse);
        }
    }

    private String getArtifactDetailsUrl(String mgtApiUrl, String artifactType, String artifactName) {

        String getArtifactDetailsUrl;
        String getArtifactsUrl = mgtApiUrl.concat(artifactType);
        switch (artifactType) {
            case PROXY_SERVICES:
                getArtifactDetailsUrl = getArtifactsUrl.concat("?proxyServiceName=").concat(artifactName);
                break;
            case ENDPOINTS:
                getArtifactDetailsUrl = getArtifactsUrl.concat("?endpointName=").concat(artifactName);
                break;
            case INBOUND_ENDPOINTS:
                getArtifactDetailsUrl = getArtifactsUrl.concat("?inboundEndpointName=").concat(artifactName);
                break;
            case MESSAGE_STORES:
            case MESSAGE_PROCESSORS:
            case LOCAL_ENTRIES:
            case DATA_SOURCES:
                getArtifactDetailsUrl = getArtifactsUrl.concat("?name=").concat(artifactName);
                break;
            case APIS:
                getArtifactDetailsUrl = getArtifactsUrl.concat("?apiName=").concat(artifactName);
                break;
            case SEQUENCES:
                getArtifactDetailsUrl = getArtifactsUrl.concat("?sequenceName=").concat(artifactName);
                break;
            case TASKS:
                getArtifactDetailsUrl = getArtifactsUrl.concat("?taskName=").concat(artifactName);
                break;
            case CONNECTORS:
                getArtifactDetailsUrl = getArtifactsUrl.concat("?connectorName=").concat(artifactName);
                break;
            case CARBON_APPLICATIONS:
                getArtifactDetailsUrl = getArtifactsUrl.concat("?carbonAppName=").concat(artifactName);
                break;
            case DATA_SERVICES:
                getArtifactDetailsUrl = mgtApiUrl.concat(DATA_SERVICES).concat("?dataServiceName=")
                                                 .concat(artifactName);
                break;
            default:
                throw new DashboardServerException("Artifact type " + artifactType + " is invalid.");
        }
        return getArtifactDetailsUrl;
    }

    private JsonObject removeConfigurationFromResponse(JsonObject artifact) {
        artifact.remove("configuration");
        return artifact;
    }

    private JsonObject removeValueFromResponse(JsonObject artifact) {
        artifact.remove("value");
        return artifact;
    }

    private void deleteArtifact(String artifactType, String name) {
        String nodeId = heartbeat.getNodeId();
        String groupId = heartbeat.getGroupId();
        logger.info("Deleting artifact " + name + " in node " + heartbeat.getNodeId() + " in group " + groupId);
        databaseManager.deleteArtifact(artifactType, name, groupId, nodeId);
    }

    private void deleteAllArtifacts() {
        String groupId = heartbeat.getGroupId();
        String nodeId = heartbeat.getNodeId();
        databaseManager.deleteServerInformation(groupId, nodeId);
        for (String artifact : ALL_ARTIFACTS) {
            databaseManager.deleteAllArtifacts(artifact, groupId, nodeId);
        }
    }

    private void addToDelayedQueue() {
        // todo
    }
}
