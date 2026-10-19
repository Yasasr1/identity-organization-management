/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.organization.management.handler.workflow;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.CarbonConstants;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.organization.management.handler.internal.OrganizationManagementHandlerDataHolder;
import org.wso2.carbon.identity.organization.management.service.OrganizationManager;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementException;
import org.wso2.carbon.identity.organization.management.service.model.Organization;
import org.wso2.carbon.identity.organization.management.service.model.OrganizationAttribute;
import org.wso2.carbon.identity.organization.management.service.model.PatchOperation;
import org.wso2.carbon.identity.workflow.mgt.WorkflowExecutorResult;
import org.wso2.carbon.identity.workflow.mgt.WorkflowManagementService;
import org.wso2.carbon.identity.workflow.mgt.bean.Entity;
import org.wso2.carbon.identity.workflow.mgt.exception.WorkflowException;
import org.wso2.carbon.identity.workflow.mgt.extension.AbstractWorkflowRequestHandler;
import org.wso2.carbon.identity.workflow.mgt.util.WorkflowDataType;
import org.wso2.carbon.identity.workflow.mgt.util.WorkflowRequestStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.OrganizationStatus.ACTIVE;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.PATCH_OP_ADD;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.PATCH_OP_REPLACE;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.PATCH_PATH_ORG_ATTRIBUTES;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.PATCH_PATH_ORG_DESCRIPTION;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.PATCH_PATH_ORG_NAME;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.PATCH_PATH_ORG_STATUS;

/**
 * Workflow request handler that keeps a newly created organization disabled until its creation is approved.
 * <p>
 * The organization is created with the {@code DISABLED} status (tokens are not issued for a disabled organization).
 * On approval it is activated, after applying any name, description or attribute edits returned by the approver.
 * On rejection it is deleted.
 */
public class AddOrganizationWFRequestHandler extends AbstractWorkflowRequestHandler {

    public static final String EVENT_ID = "ADD_ORGANIZATION";
    private static final String ENTITY_TYPE = "ORGANIZATION";
    private static final Log LOG = LogFactory.getLog(AddOrganizationWFRequestHandler.class);
    private static final Log AUDIT_LOG = CarbonConstants.AUDIT_LOG;

    public static final String ORG_ID = "organizationId";
    public static final String ORG_NAME = "organizationName";
    public static final String ORG_HANDLE = "organizationHandle";
    public static final String ORG_DESCRIPTION = "organizationDescription";
    public static final String ORG_ATTRIBUTES = "organizationAttributes";
    public static final String PARENT_ORG_ID = "parentOrganizationId";
    public static final String PARENT_ORG_NAME = "parentOrganizationName";
    public static final String CREATOR_ID = "creatorId";
    public static final String CREATOR_USERNAME = "creatorUsername";
    public static final String CREATOR_EMAIL = "creatorEmail";

    // Keys accepted in the callback response.
    public static final String RESPONSE_NAME = "name";
    public static final String RESPONSE_DESCRIPTION = "description";
    public static final String RESPONSE_ATTRIBUTES = "attributes";
    public static final String RESPONSE_REASON = "reason";
    public static final String RESPONSE_DECIDED_BY = "decidedBy";

    private static final Map<String, String> PARAM_DEFINITIONS = new LinkedHashMap<>();

    static {
        PARAM_DEFINITIONS.put(ORG_ID, WorkflowDataType.STRING_TYPE);
        PARAM_DEFINITIONS.put(ORG_NAME, WorkflowDataType.STRING_TYPE);
        PARAM_DEFINITIONS.put(ORG_HANDLE, WorkflowDataType.STRING_TYPE);
        PARAM_DEFINITIONS.put(ORG_DESCRIPTION, WorkflowDataType.STRING_TYPE);
        PARAM_DEFINITIONS.put(ORG_ATTRIBUTES, WorkflowDataType.STRING_STRING_MAP_TYPE);
        PARAM_DEFINITIONS.put(PARENT_ORG_ID, WorkflowDataType.STRING_TYPE);
        PARAM_DEFINITIONS.put(PARENT_ORG_NAME, WorkflowDataType.STRING_TYPE);
        PARAM_DEFINITIONS.put(CREATOR_ID, WorkflowDataType.STRING_TYPE);
        PARAM_DEFINITIONS.put(CREATOR_USERNAME, WorkflowDataType.STRING_TYPE);
        PARAM_DEFINITIONS.put(CREATOR_EMAIL, WorkflowDataType.STRING_TYPE);
    }

    /**
     * Start the approval workflow for a newly created organization.
     *
     * @param organization The created organization.
     * @return {@code true} if the organization is now pending approval, {@code false} if no workflow was engaged.
     * @throws WorkflowException If the workflow could not be started.
     */
    public boolean startApproval(Organization organization) throws WorkflowException {

        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
        Entity entity = new Entity(organization.getId(), ENTITY_TYPE, tenantId);

        HashMap<String, String> attributes = new HashMap<>();
        if (organization.getAttributes() != null) {
            for (OrganizationAttribute attribute : organization.getAttributes()) {
                attributes.put(attribute.getKey(), StringUtils.defaultString(attribute.getValue()));
            }
        }
        String parentId = organization.getParent() != null ? organization.getParent().getId() : null;

        Map<String, Object> wfParams = new HashMap<>();
        wfParams.put(ORG_ID, organization.getId());
        wfParams.put(ORG_NAME, StringUtils.defaultString(organization.getName()));
        wfParams.put(ORG_HANDLE, StringUtils.defaultString(organization.getOrganizationHandle()));
        wfParams.put(ORG_DESCRIPTION, StringUtils.defaultString(organization.getDescription()));
        wfParams.put(ORG_ATTRIBUTES, attributes);
        wfParams.put(PARENT_ORG_ID, StringUtils.defaultString(parentId));
        wfParams.put(PARENT_ORG_NAME, StringUtils.defaultString(resolveOrganizationName(parentId)));
        wfParams.put(CREATOR_ID, StringUtils.defaultString(organization.getCreatorId()));
        wfParams.put(CREATOR_USERNAME, StringUtils.defaultString(organization.getCreatorUsername()));
        wfParams.put(CREATOR_EMAIL, StringUtils.defaultString(organization.getCreatorEmail()));

        String requestId = UUID.randomUUID().toString();
        WorkflowExecutorResult result = startWorkFlow(wfParams, new HashMap<>(), requestId);
        if (result.getExecutorResultState().state()) {
            return false;
        }
        getWorkflowManagementService().addRequestEntityRelationships(requestId, new Entity[]{entity});
        AUDIT_LOG.info(String.format("Initiator : %s | Action : Organization Creation Approval Requested | " +
                        "Target : %s | Data : { \"Name\" : \"%s\", \"Parent\" : \"%s\", \"RequestId\" : \"%s\" } | " +
                        "Result : Success", organization.getCreatorUsername(), organization.getId(),
                organization.getName(), parentId, requestId));
        return true;
    }

    @Override
    public void onWorkflowCompletion(String status, Map<String, Object> requestParams,
                                     Map<String, Object> responseParams, int tenantId) throws WorkflowException {

        String orgId = (String) requestParams.get(ORG_ID);
        Map<String, Object> response = responseParams != null ? responseParams : Collections.emptyMap();
        OrganizationManager organizationManager = getOrganizationManager();
        // The callback runs in a tenant flow without a user. Act as the user who created the organization.
        String creatorUsername = (String) requestParams.get(CREATOR_USERNAME);
        if (StringUtils.isNotBlank(creatorUsername)) {
            PrivilegedCarbonContext.getThreadLocalCarbonContext().setUsername(creatorUsername);
        }
        try {
            if (!organizationManager.isOrganizationExistById(orgId)) {
                LOG.warn("Organization: " + orgId + " no longer exists. Ignoring the approval decision: " + status);
                return;
            }
            if (WorkflowRequestStatus.APPROVED.toString().equals(status) ||
                    WorkflowRequestStatus.SKIPPED.toString().equals(status)) {
                organizationManager.patchOrganization(orgId, buildApprovalPatch(orgId, response));
                AUDIT_LOG.info(String.format("Initiator : %s | Action : Organization Creation Approved | " +
                                "Target : %s | Data : { \"Edits\" : \"%s\" } | Result : Success",
                        response.get(RESPONSE_DECIDED_BY), orgId, response.keySet()));
            } else {
                organizationManager.deleteOrganization(orgId);
                AUDIT_LOG.info(String.format("Initiator : %s | Action : Organization Creation Rejected | " +
                                "Target : %s | Data : { \"Status\" : \"%s\", \"Reason\" : \"%s\" } | " +
                                "Result : Success", response.get(RESPONSE_DECIDED_BY), orgId, status,
                        response.get(RESPONSE_REASON)));
            }
        } catch (OrganizationManagementException e) {
            throw new WorkflowException("Error while applying the decision: " + status + " to organization: " +
                    orgId, e);
        }
    }

    /**
     * Activate the organization without waiting for an approval.
     *
     * @param orgId Organization id.
     * @throws OrganizationManagementException If the organization could not be activated.
     */
    public void activate(String orgId) throws OrganizationManagementException {

        getOrganizationManager().patchOrganization(orgId, Collections.singletonList(
                new PatchOperation(PATCH_OP_REPLACE, PATCH_PATH_ORG_STATUS, ACTIVE.toString())));
    }

    private List<PatchOperation> buildApprovalPatch(String orgId, Map<String, Object> response)
            throws OrganizationManagementException {

        List<PatchOperation> patchOperations = new ArrayList<>();
        if (response.get(RESPONSE_NAME) instanceof String && StringUtils.isNotBlank((String) response.get(
                RESPONSE_NAME))) {
            patchOperations.add(new PatchOperation(PATCH_OP_REPLACE, PATCH_PATH_ORG_NAME,
                    (String) response.get(RESPONSE_NAME)));
        }
        if (response.get(RESPONSE_DESCRIPTION) instanceof String) {
            patchOperations.add(new PatchOperation(PATCH_OP_REPLACE, PATCH_PATH_ORG_DESCRIPTION,
                    (String) response.get(RESPONSE_DESCRIPTION)));
        }
        if (response.get(RESPONSE_ATTRIBUTES) instanceof Map) {
            List<String> existingKeys = new ArrayList<>();
            Organization organization = getOrganizationManager().getOrganization(orgId, false, false);
            if (organization.getAttributes() != null) {
                organization.getAttributes().forEach(attribute -> existingKeys.add(attribute.getKey()));
            }
            for (Map.Entry<?, ?> attribute : ((Map<?, ?>) response.get(RESPONSE_ATTRIBUTES)).entrySet()) {
                String key = String.valueOf(attribute.getKey());
                String value = attribute.getValue() == null ? "" : String.valueOf(attribute.getValue());
                patchOperations.add(new PatchOperation(existingKeys.contains(key) ? PATCH_OP_REPLACE : PATCH_OP_ADD,
                        PATCH_PATH_ORG_ATTRIBUTES + key, value));
            }
        }
        patchOperations.add(new PatchOperation(PATCH_OP_REPLACE, PATCH_PATH_ORG_STATUS, ACTIVE.toString()));
        return patchOperations;
    }

    @Override
    public boolean retryNeedAtCallback() {

        return false;
    }

    @Override
    public String getEventId() {

        return EVENT_ID;
    }

    @Override
    public Map<String, String> getParamDefinitions() {

        return PARAM_DEFINITIONS;
    }

    @Override
    public String getFriendlyName() {

        return "Add Organization";
    }

    @Override
    public String getDescription() {

        return "Triggered when an organization is created.";
    }

    @Override
    public String getCategory() {

        return "Organization Operations";
    }

    private String resolveOrganizationName(String orgId) {

        if (StringUtils.isBlank(orgId)) {
            return null;
        }
        try {
            return getOrganizationManager().getOrganizationNameById(orgId);
        } catch (OrganizationManagementException e) {
            LOG.debug("Unable to resolve the name of organization: " + orgId, e);
            return null;
        }
    }

    private static OrganizationManager getOrganizationManager() {

        return OrganizationManagementHandlerDataHolder.getInstance().getOrganizationManager();
    }

    private static WorkflowManagementService getWorkflowManagementService() throws WorkflowException {

        WorkflowManagementService service = (WorkflowManagementService) PrivilegedCarbonContext
                .getThreadLocalCarbonContext().getOSGiService(WorkflowManagementService.class, null);
        if (service == null) {
            throw new WorkflowException("Workflow management service is not available.");
        }
        return service;
    }
}
