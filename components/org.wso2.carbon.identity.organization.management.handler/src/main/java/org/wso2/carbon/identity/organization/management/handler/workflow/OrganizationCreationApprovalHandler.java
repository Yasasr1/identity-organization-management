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
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.event.IdentityEventException;
import org.wso2.carbon.identity.event.event.Event;
import org.wso2.carbon.identity.event.handler.AbstractEventHandler;
import org.wso2.carbon.identity.organization.management.ext.Constants;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementException;
import org.wso2.carbon.identity.organization.management.service.model.Organization;
import org.wso2.carbon.identity.workflow.mgt.exception.WorkflowException;

import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.OrganizationStatus.ACTIVE;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.OrganizationStatus.DISABLED;

/**
 * Puts organization creation on hold for approval when an {@code ADD_ORGANIZATION} workflow is associated.
 * <p>
 * Subscribe it to {@code PRE_ADD_ORGANIZATION} and {@code POST_ADD_ORGANIZATION}:
 * <pre>
 * [[event_handler]]
 * name = "organizationCreationApprovalHandler"
 * subscriptions = ["PRE_ADD_ORGANIZATION", "POST_ADD_ORGANIZATION"]
 * </pre>
 */
public class OrganizationCreationApprovalHandler extends AbstractEventHandler {

    private static final Log LOG = LogFactory.getLog(OrganizationCreationApprovalHandler.class);
    private static final String APPROVAL_PENDING_ORG = "ORGANIZATION_CREATION_APPROVAL_PENDING";

    private final AddOrganizationWFRequestHandler workflowRequestHandler = new AddOrganizationWFRequestHandler();

    @Override
    public String getName() {

        return "organizationCreationApprovalHandler";
    }

    @Override
    public void handleEvent(Event event) throws IdentityEventException {

        Organization organization = (Organization) event.getEventProperties().get(Constants.EVENT_PROP_ORGANIZATION);
        if (organization == null) {
            return;
        }
        if (Constants.EVENT_PRE_ADD_ORGANIZATION.equals(event.getEventName())) {
            handlePreAddOrganization(organization);
        } else if (Constants.EVENT_POST_ADD_ORGANIZATION.equals(event.getEventName())) {
            handlePostAddOrganization(organization);
        }
    }

    private void handlePreAddOrganization(Organization organization) throws IdentityEventException {

        // An organization explicitly created as disabled needs no approval to stay disabled.
        if (StringUtils.isNotBlank(organization.getStatus()) &&
                !ACTIVE.toString().equals(organization.getStatus())) {
            return;
        }
        try {
            if (!workflowRequestHandler.isAssociated()) {
                return;
            }
        } catch (WorkflowException e) {
            throw new IdentityEventException("Error while checking the organization creation approval workflow.", e);
        }
        organization.setStatus(DISABLED.toString());
        IdentityUtil.threadLocalProperties.get().put(APPROVAL_PENDING_ORG, organization);
        if (LOG.isDebugEnabled()) {
            LOG.debug("Organization: " + organization.getName() + " is created as disabled pending approval.");
        }
    }

    private void handlePostAddOrganization(Organization organization) throws IdentityEventException {

        if (IdentityUtil.threadLocalProperties.get().get(APPROVAL_PENDING_ORG) != organization) {
            return;
        }
        IdentityUtil.threadLocalProperties.get().remove(APPROVAL_PENDING_ORG);
        try {
            if (!workflowRequestHandler.startApproval(organization)) {
                // No workflow was engaged after all (e.g. its condition did not match).
                workflowRequestHandler.activate(organization.getId());
            }
        } catch (WorkflowException | OrganizationManagementException e) {
            throw new IdentityEventException("Error while starting the approval workflow for organization: " +
                    organization.getId(), e);
        }
    }
}
