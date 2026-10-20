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

package org.wso2.carbon.identity.organization.management.organization.user.sharing.workflow;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.CarbonConstants;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.organization.management.organization.user.sharing.UserSharingPolicyHandlerServiceImplV2;
import org.wso2.carbon.identity.organization.management.organization.user.sharing.constant.UserSharePatchOperation;
import org.wso2.carbon.identity.organization.management.organization.user.sharing.exception.UserSharingMgtClientException;
import org.wso2.carbon.identity.organization.management.organization.user.sharing.exception.UserSharingMgtException;
import org.wso2.carbon.identity.organization.management.organization.user.sharing.exception.UserSharingMgtServerException;
import org.wso2.carbon.identity.organization.management.organization.user.sharing.internal.OrganizationUserSharingDataHolder;
import org.wso2.carbon.identity.organization.management.organization.user.sharing.models.dos.PatchOperationDO;
import org.wso2.carbon.identity.organization.management.organization.user.sharing.models.dos.RoleWithAudienceDO;
import org.wso2.carbon.identity.organization.management.organization.user.sharing.util.SharingInitiatorContext;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementException;
import org.wso2.carbon.identity.workflow.mgt.WorkflowExecutorResult;
import org.wso2.carbon.identity.workflow.mgt.WorkflowManagementService;
import org.wso2.carbon.identity.workflow.mgt.bean.Entity;
import org.wso2.carbon.identity.workflow.mgt.exception.WorkflowException;
import org.wso2.carbon.identity.workflow.mgt.extension.AbstractWorkflowRequestHandler;
import org.wso2.carbon.identity.workflow.mgt.util.WorkflowDataType;
import org.wso2.carbon.identity.workflow.mgt.util.WorkflowRequestStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.wso2.carbon.identity.organization.management.organization.user.sharing.constant.UserSharingConstants.ErrorMessage.ERROR_CODE_SHARED_USER_ROLES_APPROVAL_PENDING;
import static org.wso2.carbon.identity.organization.management.organization.user.sharing.constant.UserSharingConstants.ErrorMessage.ERROR_CODE_USER_SHARE_WORKFLOW;
import static org.wso2.carbon.identity.organization.management.organization.user.sharing.constant.UserSharingConstants.PATCH_PATH_PREFIX;
import static org.wso2.carbon.identity.organization.management.organization.user.sharing.constant.UserSharingConstants.PATCH_PATH_SUFFIX_ROLES;
import static org.wso2.carbon.identity.organization.management.organization.user.sharing.workflow.ShareUserWFRequestHandler.fromRoleMaps;
import static org.wso2.carbon.identity.organization.management.organization.user.sharing.workflow.ShareUserWFRequestHandler.getWorkflowManagementService;
import static org.wso2.carbon.identity.organization.management.organization.user.sharing.workflow.ShareUserWFRequestHandler.isOrganizationCreationPending;
import static org.wso2.carbon.identity.organization.management.organization.user.sharing.workflow.ShareUserWFRequestHandler.resolveOrganizationName;
import static org.wso2.carbon.identity.organization.management.organization.user.sharing.workflow.ShareUserWFRequestHandler.resolveUsername;
import static org.wso2.carbon.identity.organization.management.organization.user.sharing.workflow.ShareUserWFRequestHandler.toRoleMaps;

/**
 * Workflow request handler that puts adding roles to a user already shared with an organization on hold until the
 * role request is approved.
 * <p>
 * While the request is pending the user keeps the roles they already hold; the pending workflow request (and its
 * entity lock on the user + organization pair) is the pending record. On approval the (optionally edited) roles
 * returned by the approver are assigned. Role removals are not gated.
 */
public class AddSharedUserRolesWFRequestHandler extends AbstractWorkflowRequestHandler {

    public static final String EVENT_ID = "ADD_SHARED_USER_ROLES";
    private static final String ENTITY_TYPE = "SHARED_USER_ROLES";
    private static final Log LOG = LogFactory.getLog(AddSharedUserRolesWFRequestHandler.class);
    private static final Log AUDIT_LOG = CarbonConstants.AUDIT_LOG;

    public static final String USER_ID = ShareUserWFRequestHandler.USER_ID;
    public static final String USERNAME = ShareUserWFRequestHandler.USERNAME;
    public static final String RESIDENT_ORG_ID = ShareUserWFRequestHandler.RESIDENT_ORG_ID;
    public static final String RESIDENT_ORG_NAME = ShareUserWFRequestHandler.RESIDENT_ORG_NAME;
    public static final String TARGET_ORG_ID = ShareUserWFRequestHandler.TARGET_ORG_ID;
    public static final String TARGET_ORG_NAME = ShareUserWFRequestHandler.TARGET_ORG_NAME;
    public static final String ROLES = ShareUserWFRequestHandler.ROLES;
    public static final String CURRENT_ROLES = "currentRoles";
    public static final String INITIATOR_USER_ID = ShareUserWFRequestHandler.INITIATOR_USER_ID;
    public static final String INITIATOR_USERNAME = ShareUserWFRequestHandler.INITIATOR_USERNAME;

    // Keys accepted in the callback response.
    public static final String RESPONSE_ROLES = ShareUserWFRequestHandler.RESPONSE_ROLES;
    public static final String RESPONSE_REASON = ShareUserWFRequestHandler.RESPONSE_REASON;
    public static final String RESPONSE_DECIDED_BY = ShareUserWFRequestHandler.RESPONSE_DECIDED_BY;

    private static final Map<String, String> PARAM_DEFINITIONS = new LinkedHashMap<>();

    static {
        PARAM_DEFINITIONS.put(USER_ID, WorkflowDataType.STRING_TYPE);
        PARAM_DEFINITIONS.put(USERNAME, WorkflowDataType.STRING_TYPE);
        PARAM_DEFINITIONS.put(RESIDENT_ORG_ID, WorkflowDataType.STRING_TYPE);
        PARAM_DEFINITIONS.put(RESIDENT_ORG_NAME, WorkflowDataType.STRING_TYPE);
        PARAM_DEFINITIONS.put(TARGET_ORG_ID, WorkflowDataType.STRING_TYPE);
        PARAM_DEFINITIONS.put(TARGET_ORG_NAME, WorkflowDataType.STRING_TYPE);
        PARAM_DEFINITIONS.put(INITIATOR_USER_ID, WorkflowDataType.STRING_TYPE);
        PARAM_DEFINITIONS.put(INITIATOR_USERNAME, WorkflowDataType.STRING_TYPE);
    }

    /**
     * Start approval workflows for the role additions that need approval.
     *
     * @param userIds         Users whose shared roles are updated.
     * @param patchOperations Requested patch operations.
     * @param initiator       Context of the request initiator.
     * @return {@code null} if no approval workflow applies (apply everything immediately), otherwise the patch
     * operations to apply immediately, keyed by user id.
     * @throws UserSharingMgtException If a role request is already pending or the workflow could not be engaged.
     */
    public Map<String, List<PatchOperationDO>> engageWorkflows(List<String> userIds,
                                                               List<PatchOperationDO> patchOperations,
                                                               SharingInitiatorContext initiator)
            throws UserSharingMgtException {

        if (Boolean.TRUE.equals(getWorkFlowCompleted()) || userIds == null || userIds.isEmpty() ||
                patchOperations == null || patchOperations.isEmpty()) {
            return null;
        }
        try {
            if (!isAssociated()) {
                return null;
            }
        } catch (WorkflowException e) {
            throw new UserSharingMgtServerException(ERROR_CODE_USER_SHARE_WORKFLOW.getCode(),
                    ERROR_CODE_USER_SHARE_WORKFLOW.getMessage(), e.getMessage(), e);
        }

        Map<String, List<PatchOperationDO>> immediateOperations = new LinkedHashMap<>();
        for (String userId : userIds) {
            List<PatchOperationDO> operationsToRunNow = new ArrayList<>();
            for (PatchOperationDO operation : patchOperations) {
                /* Only role additions to an existing share need approval. Additions in an organization whose own
                   creation is still pending approval (e.g. the creator picking roles in the organization they just
                   onboarded) are covered by that approval. */
                String orgId = getRolesPathOrgId(operation);
                List<RoleWithAudienceDO> roles = getRoles(operation);
                if (orgId == null || roles.isEmpty() || !isShared(userId, orgId) ||
                        isOrganizationCreationPending(orgId) ||
                        !startRoleRequestWorkflow(userId, orgId, roles, initiator)) {
                    operationsToRunNow.add(operation);
                }
            }
            immediateOperations.put(userId, operationsToRunNow);
        }
        return immediateOperations;
    }

    private boolean startRoleRequestWorkflow(String userId, String orgId, List<RoleWithAudienceDO> roles,
                                             SharingInitiatorContext initiator) throws UserSharingMgtException {

        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
        Entity entity = new Entity(userId + ":" + orgId, ENTITY_TYPE, tenantId);
        try {
            WorkflowManagementService workflowService = getWorkflowManagementService();
            if (workflowService.entityHasPendingWorkflowsOfType(entity, EVENT_ID)) {
                throw new UserSharingMgtClientException(ERROR_CODE_SHARED_USER_ROLES_APPROVAL_PENDING.getCode(),
                        ERROR_CODE_SHARED_USER_ROLES_APPROVAL_PENDING.getMessage(),
                        String.format(ERROR_CODE_SHARED_USER_ROLES_APPROVAL_PENDING.getDescription(), userId, orgId));
            }

            Map<String, Object> wfParams = new HashMap<>();
            wfParams.put(USER_ID, userId);
            wfParams.put(USERNAME, StringUtils.defaultString(resolveUsername(userId, tenantId)));
            wfParams.put(RESIDENT_ORG_ID, initiator.getSharingInitiatedOrgId());
            wfParams.put(RESIDENT_ORG_NAME, StringUtils.defaultString(
                    resolveOrganizationName(initiator.getSharingInitiatedOrgId())));
            wfParams.put(TARGET_ORG_ID, orgId);
            wfParams.put(TARGET_ORG_NAME, StringUtils.defaultString(resolveOrganizationName(orgId)));
            wfParams.put(ROLES, toRoleMaps(roles));
            wfParams.put(CURRENT_ROLES, toRoleMaps(resolveCurrentRoles(userId, orgId)));
            wfParams.put(INITIATOR_USER_ID, StringUtils.defaultString(initiator.getSharingInitiatedUserId()));
            wfParams.put(INITIATOR_USERNAME, StringUtils.defaultString(initiator.getSharingInitiatedUsername()));

            String requestId = UUID.randomUUID().toString();
            WorkflowExecutorResult result = startWorkFlow(wfParams, new HashMap<>(), requestId);
            if (result.getExecutorResultState().state()) {
                // No workflow engaged (e.g. the association condition did not match): assign immediately.
                return false;
            }
            workflowService.addRequestEntityRelationships(requestId, new Entity[]{entity});
            AUDIT_LOG.info(String.format("Initiator : %s | Action : Shared User Roles Approval Requested | " +
                            "Target : %s | Data : { \"Organization\" : \"%s\", \"Roles\" : \"%s\", " +
                            "\"RequestId\" : \"%s\" } | Result : Success", initiator.getSharingInitiatedUsername(),
                    userId, orgId, toRoleMaps(roles), requestId));
            return true;
        } catch (WorkflowException e) {
            throw new UserSharingMgtServerException(ERROR_CODE_USER_SHARE_WORKFLOW.getCode(),
                    ERROR_CODE_USER_SHARE_WORKFLOW.getMessage(),
                    String.format(ERROR_CODE_USER_SHARE_WORKFLOW.getDescription(), userId, orgId), e);
        }
    }

    @Override
    public void onWorkflowCompletion(String status, Map<String, Object> requestParams,
                                     Map<String, Object> responseParams, int tenantId) throws WorkflowException {

        String userId = (String) requestParams.get(USER_ID);
        String targetOrgId = (String) requestParams.get(TARGET_ORG_ID);
        Map<String, Object> response = responseParams != null ? responseParams : Collections.emptyMap();

        if (!WorkflowRequestStatus.APPROVED.toString().equals(status) &&
                !WorkflowRequestStatus.SKIPPED.toString().equals(status)) {
            AUDIT_LOG.info(String.format("Initiator : %s | Action : Shared User Roles Rejected | Target : %s | " +
                            "Data : { \"Organization\" : \"%s\", \"Status\" : \"%s\", \"Reason\" : \"%s\" } | " +
                            "Result : Success", response.get(RESPONSE_DECIDED_BY), userId, targetOrgId, status,
                    response.get(RESPONSE_REASON)));
            return;
        }

        // An approver may narrow down (or change) the requested roles.
        List<RoleWithAudienceDO> roles = response.containsKey(RESPONSE_ROLES) ?
                fromRoleMaps(response.get(RESPONSE_ROLES)) : fromRoleMaps(requestParams.get(ROLES));
        if (!roles.isEmpty()) {
            // The callback runs in a tenant flow without a user. Act as the user who made the request.
            String initiatorUsername = (String) requestParams.get(INITIATOR_USERNAME);
            if (StringUtils.isNotBlank(initiatorUsername)) {
                PrivilegedCarbonContext.getThreadLocalCarbonContext().setUsername(initiatorUsername);
            }
            try {
                new UserSharingPolicyHandlerServiceImplV2().addApprovedSharedUserRoles(userId, targetOrgId, roles,
                        (String) requestParams.get(RESIDENT_ORG_ID), (String) requestParams.get(INITIATOR_USER_ID));
            } catch (UserSharingMgtException e) {
                throw new WorkflowException("Error while assigning roles to user: " + userId + " in organization: " +
                        targetOrgId + " after approval.", e);
            }
        }
        AUDIT_LOG.info(String.format("Initiator : %s | Action : Shared User Roles Approved | Target : %s | " +
                        "Data : { \"Organization\" : \"%s\", \"Roles\" : \"%s\" } | Result : Success",
                response.get(RESPONSE_DECIDED_BY), userId, targetOrgId, toRoleMaps(roles)));
    }

    @Override
    public boolean retryNeedAtCallback() {

        return true;
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

        return "Add Roles to Shared User";
    }

    @Override
    public String getDescription() {

        return "Triggered when roles are added to a user shared with an organization.";
    }

    @Override
    public String getCategory() {

        return "Organization Operations";
    }

    private static String getRolesPathOrgId(PatchOperationDO operation) {

        String path = operation.getPath() == null ? "" : operation.getPath().trim();
        if (operation.getOperation() != UserSharePatchOperation.ADD || !path.startsWith(PATCH_PATH_PREFIX) ||
                !path.endsWith(PATCH_PATH_SUFFIX_ROLES)) {
            return null;
        }
        return path.substring(PATCH_PATH_PREFIX.length(), path.length() - PATCH_PATH_SUFFIX_ROLES.length()).trim();
    }

    private static List<RoleWithAudienceDO> getRoles(PatchOperationDO operation) {

        List<RoleWithAudienceDO> roles = new ArrayList<>();
        if (operation.getValues() instanceof Collection) {
            for (Object value : (Collection<?>) operation.getValues()) {
                if (value instanceof RoleWithAudienceDO) {
                    roles.add((RoleWithAudienceDO) value);
                }
            }
        }
        return roles;
    }

    private static boolean isShared(String userId, String orgId) throws UserSharingMgtServerException {

        try {
            return OrganizationUserSharingDataHolder.getInstance().getOrganizationUserSharingService()
                    .getUserAssociationOfAssociatedUserByOrgId(userId, orgId) != null;
        } catch (OrganizationManagementException e) {
            throw new UserSharingMgtServerException(ERROR_CODE_USER_SHARE_WORKFLOW.getCode(),
                    ERROR_CODE_USER_SHARE_WORKFLOW.getMessage(),
                    String.format(ERROR_CODE_USER_SHARE_WORKFLOW.getDescription(), userId, orgId), e);
        }
    }

    private static List<RoleWithAudienceDO> resolveCurrentRoles(String userId, String orgId) {

        try {
            return new UserSharingPolicyHandlerServiceImplV2().getSharedRolesOfUserInOrganization(userId, orgId);
        } catch (UserSharingMgtException e) {
            LOG.debug("Unable to resolve the current roles of user: " + userId + " in organization: " + orgId, e);
            return Collections.emptyList();
        }
    }
}
