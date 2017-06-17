/*
 * Copyright (c) 2017 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package com.vmware.admiral.auth.project;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.management.ServiceNotFoundException;

import com.vmware.admiral.auth.idm.AuthRole;
import com.vmware.admiral.auth.project.ProjectRolesHandler.ProjectRoles;
import com.vmware.admiral.auth.util.AuthUtil;
import com.vmware.admiral.auth.util.ProjectUtil;
import com.vmware.admiral.auth.util.UserGroupsUpdater;
import com.vmware.admiral.common.util.AssertUtil;
import com.vmware.admiral.common.util.AuthUtils;
import com.vmware.admiral.common.util.PropertyUtils;
import com.vmware.photon.controller.model.ServiceUtils;
import com.vmware.photon.controller.model.adapters.util.Pair;
import com.vmware.photon.controller.model.resources.ResourceState;
import com.vmware.xenon.common.DeferredResult;
import com.vmware.xenon.common.LocalizableValidationException;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.Service;
import com.vmware.xenon.common.ServiceDocument;
import com.vmware.xenon.common.ServiceDocumentDescription;
import com.vmware.xenon.common.ServiceDocumentDescription.PropertyUsageOption;
import com.vmware.xenon.common.ServiceDocumentQueryResult;
import com.vmware.xenon.common.StatefulService;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;
import com.vmware.xenon.services.common.QueryTask;
import com.vmware.xenon.services.common.ResourceGroupService;
import com.vmware.xenon.services.common.ResourceGroupService.ResourceGroupState;
import com.vmware.xenon.services.common.RoleService;
import com.vmware.xenon.services.common.RoleService.RoleState;
import com.vmware.xenon.services.common.ServiceUriPaths;
import com.vmware.xenon.services.common.UserGroupService;
import com.vmware.xenon.services.common.UserGroupService.UserGroupState;
import com.vmware.xenon.services.common.UserService.UserState;

/**
 * Project is a group sharing same resources.
 */
public class ProjectService extends StatefulService {

    public static final String FIELD_NAME_CUSTOM_PROPERTIES = "customProperties";

    public static final String CUSTOM_PROPERTY_HARBOR_ID = "__harborId";

    public static final String DEFAULT_PROJECT_ID = "default-project";
    public static final String DEFAULT_HARBOR_PROJECT_ID = "1";
    public static final String DEFAULT_PROJECT_LINK = UriUtils
            .buildUriPath(ProjectFactoryService.SELF_LINK, DEFAULT_PROJECT_ID);

    public static ProjectState buildDefaultProjectInstance() {
        ProjectState project = new ProjectState();
        project.documentSelfLink = DEFAULT_PROJECT_LINK;
        project.name = DEFAULT_PROJECT_ID;
        project.id = project.name;
        project.customProperties = new HashMap<>();
        project.customProperties.put(CUSTOM_PROPERTY_HARBOR_ID, DEFAULT_HARBOR_PROJECT_ID);

        return project;
    }

    /**
     * This class represents the document state associated with a
     * {@link com.vmware.admiral.auth.project.ProjectService}.
     */
    public static class ProjectState extends ResourceState {
        public static final String FIELD_NAME_PUBLIC = "isPublic";
        public static final String FIELD_NAME_DESCRIPTION = "description";
        public static final String FIELD_NAME_ADMINISTRATORS_USER_GROUP_LINKS = "administratorsUserGroupLinks";
        public static final String FIELD_NAME_MEMBERS_USER_GROUP_LINKS = "membersUserGroupLinks";

        @Documentation(description = "Used for define a public project")
        @PropertyOptions(usage = { PropertyUsageOption.OPTIONAL,
                PropertyUsageOption.AUTO_MERGE_IF_NOT_NULL })
        public boolean isPublic;

        @Documentation(description = "Used for descripe the purpose of the project")
        @PropertyOptions(usage = { PropertyUsageOption.OPTIONAL,
                PropertyUsageOption.AUTO_MERGE_IF_NOT_NULL })
        public String description;

        /**
         * Links to the groups of administrators for this project.
         */
        @Documentation(description = "Links to the groups of administrators for this project.")
        @PropertyOptions(usage = { PropertyUsageOption.OPTIONAL,
                PropertyUsageOption.AUTO_MERGE_IF_NOT_NULL })
        public List<String> administratorsUserGroupLinks;

        /**
         * Links to the groups of members for this project.
         */
        @Documentation(description = "Links to the groups of members for this project.")
        @PropertyOptions(usage = { PropertyUsageOption.OPTIONAL,
                PropertyUsageOption.AUTO_MERGE_IF_NOT_NULL })
        public List<String> membersUserGroupLinks;

        public void copyTo(ProjectState destination) {
            super.copyTo(destination);
            destination.isPublic = this.isPublic;
            destination.description = this.description;

            if (this.administratorsUserGroupLinks != null
                    && !this.administratorsUserGroupLinks.isEmpty()) {
                destination.administratorsUserGroupLinks = new ArrayList<>(
                        this.administratorsUserGroupLinks.size());
                destination.administratorsUserGroupLinks.addAll(this.administratorsUserGroupLinks);
            }

            if (this.membersUserGroupLinks != null
                    && !this.membersUserGroupLinks.isEmpty()) {
                destination.membersUserGroupLinks = new ArrayList<>(
                        this.membersUserGroupLinks.size());
                destination.membersUserGroupLinks.addAll(this.membersUserGroupLinks);
            }
        }

        public static ProjectState copyOf(ProjectState source) {
            if (source == null) {
                return null;
            }

            ProjectState result = new ProjectState();
            source.copyTo(result);
            return result;
        }
    }

    /**
     * This class represents the expanded document state associated with a
     * {@link com.vmware.admiral.auth.project.ProjectService}.
     */
    public static class ExpandedProjectState extends ProjectState {

        /**
         * List of administrators for this project.
         */
        @Documentation(description = "List of administrators for this project.")
        public List<UserState> administrators;

        /**
         * List of members for this project.
         */
        @Documentation(description = "List of members for this project.")
        public List<UserState> members;

        /**
         * List of cluster links for this project.
         */
        @Documentation(description = "List of cluster links for this project.")
        public List<String> clusterLinks;

        /**
         * List of repositories in this project.
         */
        @Documentation(description = "List of repositories in this project.")
        public List<String> repositories;

        public void copyTo(ExpandedProjectState destination) {
            super.copyTo(destination);
            if (administrators != null) {
                destination.administrators = new ArrayList<>(administrators);
            }
            if (members != null) {
                destination.members = new ArrayList<>(members);
            }
        }
    }

    public ProjectService() {
        super(ProjectState.class);
        super.toggleOption(ServiceOption.PERSISTENCE, true);
        super.toggleOption(ServiceOption.REPLICATION, true);
        super.toggleOption(ServiceOption.OWNER_SELECTION, true);
        super.toggleOption(ServiceOption.IDEMPOTENT_POST, true);
    }

    @Override
    public void handleCreate(Operation post) {
        if (!checkForBody(post)) {
            return;
        }

        ProjectState createBody = post.getBody(ProjectState.class);
        validateState(createBody);

        createAdminAndMemberGroups(createBody)
                .thenAccept(post::setBody)
                .whenCompleteNotify(post);

    }

    @Override
    public void handleGet(Operation get) {
        if (UriUtils.hasODataExpandParamValue(get.getUri())) {
            retrieveExpandedState(getState(get), get);
        } else {
            super.handleGet(get);
        }
    }

    @Override
    public void handlePut(Operation put) {
        if (!checkForBody(put)) {
            return;
        }

        if (ProjectRolesHandler.isProjectRolesUpdate(put)) {
            if (AuthUtils.isDevOpsAdmin(put)) {
                ProjectRoles rolesPut = put.getBody(ProjectRoles.class);
                // this is an update of the roles
                new ProjectRolesHandler(getHost(), getSelfLink()).handleRolesUpdate(rolesPut)
                        .whenComplete((ignore, ex) -> {
                            if (ex != null) {
                                if (ex.getCause() instanceof ServiceNotFoundException) {
                                    put.fail(Operation.STATUS_CODE_BAD_REQUEST, ex.getCause(),
                                            ex.getCause());
                                    return;
                                }
                                put.fail(ex);
                                return;
                            }
                            put.complete();
                        });
            } else {
                put.fail(Operation.STATUS_CODE_FORBIDDEN);
            }
        } else {
            // this is an update of the state
            ProjectState projectPut = put.getBody(ProjectState.class);
            validateState(projectPut);
            this.setState(put, projectPut);
            put.setBody(projectPut).complete();
            return;
        }
    }

    @Override
    public void handlePatch(Operation patch) {
        if (!patch.hasBody()) {
            patch.setStatusCode(Operation.STATUS_CODE_NOT_MODIFIED);
            patch.complete();
            return;
        }

        ProjectState projectPatch = patch.getBody(ProjectState.class);
        handleProjectPatch(getState(patch), projectPatch);

        // Patch roles if DevOps admin
        if (ProjectRolesHandler.isProjectRolesUpdate(patch)) {
            if (AuthUtils.isDevOpsAdmin(patch)) {
                new ProjectRolesHandler(getHost(), getSelfLink())
                        .handleRolesUpdate(patch.getBody(ProjectRoles.class))
                        .whenComplete((ignore, ex) -> {
                            if (ex != null) {
                                if (ex.getCause() instanceof ServiceNotFoundException) {
                                    patch.fail(Operation.STATUS_CODE_BAD_REQUEST, ex.getCause(),
                                            ex.getCause());
                                    return;
                                }
                                patch.fail(ex);
                                return;
                            }
                            patch.complete();
                        });
            } else {
                patch.fail(Operation.STATUS_CODE_FORBIDDEN);
            }
        } else {
            patch.complete();
        }

    }

    /**
     * Returns whether the projects state signature was changed after the patch.
     */
    private boolean handleProjectPatch(ProjectState currentState, ProjectState patchState) {
        ServiceDocumentDescription docDesc = getDocumentTemplate().documentDescription;
        String currentSignature = Utils.computeSignature(currentState, docDesc);

        Map<String, String> mergedProperties = PropertyUtils
                .mergeCustomProperties(currentState.customProperties, patchState.customProperties);
        PropertyUtils.mergeServiceDocuments(currentState, patchState);
        currentState.customProperties = mergedProperties;

        String newSignature = Utils.computeSignature(currentState, docDesc);
        return !currentSignature.equals(newSignature);
    }

    @Override
    public void handleDelete(Operation delete) {
        ProjectState state = getState(delete);
        if (state == null || state.documentSelfLink == null) {
            delete.complete();
            return;
        }

        QueryTask queryTask = ProjectUtil.createQueryTaskForProjectAssociatedWithPlacement(state,
                null);

        Operation getPlacementsWithProject = Operation
                .createPost(getHost(), ServiceUriPaths.CORE_QUERY_TASKS)
                .setBody(queryTask);

        sendWithDeferredResult(getPlacementsWithProject, ServiceDocumentQueryResult.class)
                .thenApply(result -> new Pair<>(result, (Throwable) null))
                .exceptionally(ex -> new Pair<>(null, ex))
                .thenCompose(pair -> {
                    if (pair.right != null) {
                        logSevere("Failed to retrieve placements associated with project: %s",
                                state.documentSelfLink);
                        return DeferredResult.failed(pair.right);
                    } else {
                        Long documentCount = pair.left.documentCount;
                        if (documentCount != null && documentCount != 0) {
                            return DeferredResult.failed(new LocalizableValidationException(
                                    ProjectUtil.PROJECT_IN_USE_MESSAGE,
                                    ProjectUtil.PROJECT_IN_USE_MESSAGE_CODE,
                                    documentCount, documentCount > 1 ? "s" : ""));
                        }
                        String projectId = Service.getId(getState(delete).documentSelfLink);
                        return deleteDefaultProjectGroups(projectId, delete);
                    }
                })
                .whenComplete((ignore, ex) -> {
                    if (ex != null) {
                        delete.fail(ex);
                        return;
                    }
                    super.handleDelete(delete);
                });

    }

    private DeferredResult<Operation> deleteDefaultProjectGroups(String projectId,
            Operation delete) {

        String adminsUserGroupUri = UriUtils.buildUriPath(UserGroupService.FACTORY_LINK,
                AuthRole.PROJECT_ADMINS.buildRoleWithSuffix(projectId));

        String membersUserGroupsUri = UriUtils.buildUriPath(UserGroupService.FACTORY_LINK,
                AuthRole.PROJECT_MEMBERS.buildRoleWithSuffix(projectId));

        String resourceGroupUri = UriUtils.buildUriPath(ResourceGroupService.FACTORY_LINK,
                projectId);

        String adminsRoleUri = UriUtils.buildUriPath(RoleService.FACTORY_LINK, AuthRole
                .PROJECT_ADMINS.buildRoleWithSuffix(projectId, Service.getId(adminsUserGroupUri)));

        String membersRoleUri = UriUtils.buildUriPath(RoleService.FACTORY_LINK, AuthRole
                .PROJECT_MEMBERS.buildRoleWithSuffix(projectId, Service.getId(membersUserGroupsUri)));

        Operation deleteMembersGroup = Operation.createDelete(this, membersUserGroupsUri)
                .setReferer(delete.getUri());

        Operation deleteAdminsGroup = Operation.createDelete(this, adminsUserGroupUri)
                .setReferer(delete.getUri());

        Operation deleteResourceGroup = Operation.createDelete(this, resourceGroupUri)
                .setReferer(delete.getUri());

        Operation deleteAdminsRole = Operation.createDelete(this, adminsRoleUri)
                .setReferer(delete.getUri());

        Operation deleteMembersRole = Operation.createDelete(this, membersRoleUri)
                .setReferer(delete.getUri());

        return removeDefaultProjectGroupsFromUserStates(adminsUserGroupUri, membersUserGroupsUri,
                delete)
                .thenCompose(ignore -> sendWithDeferredResult(deleteMembersGroup, UserGroupState.class))
                .exceptionally(ex -> {
                    logWarning("Couldn't delete members user group: %s", Utils.toString(ex));
                    return null;
                })
                .thenCompose(ignore -> sendWithDeferredResult(deleteAdminsGroup, UserGroupState.class))
                .exceptionally(ex -> {
                    logWarning("Couldn't delete admins user group: %s", Utils.toString(ex));
                    return null;
                })
                .thenCompose(ignore -> sendWithDeferredResult(deleteResourceGroup))
                .exceptionally(ex -> {
                    logWarning("Couldn't delete project resource group: %s", Utils.toString(ex));
                    return null;
                })
                .thenCompose(ignore -> sendWithDeferredResult(deleteAdminsRole))
                .exceptionally(ex -> {
                    logWarning("Couldn't delete admins role: %s", Utils.toString(ex));
                    return null;
                })
                .thenCompose(ignore -> sendWithDeferredResult(deleteMembersRole))
                .exceptionally(ex -> {
                    logWarning("Couldn't delete members role: %s", Utils.toString(ex));
                    return null;
                });
    }

    private DeferredResult<Void> removeDefaultProjectGroupsFromUserStates(String adminsGroup,
            String membersGroup, Operation delete) {

        Operation getAdminsGroup = Operation.createGet(this, adminsGroup)
                .setReferer(delete.getUri());

        Operation getMembersGroup = Operation.createGet(this, membersGroup)
                .setReferer(delete.getUri());

        return sendWithDeferredResult(getMembersGroup, UserGroupState.class)
                .exceptionally(ex ->  {
                    logWarning("Couldn't get members group: %s", Utils.toString(ex));
                    return null;
                })
                .thenCompose(membersGroupState -> patchUserStates(membersGroupState))
                .exceptionally(ex -> {
                    logWarning("Couldn't patch members user states: %s", Utils.toString(ex));
                    return null;
                })
                .thenCompose(ignore -> sendWithDeferredResult(getAdminsGroup, UserGroupState.class))
                .exceptionally(ex ->  {
                    logWarning("Couldn't get admins group: %s", Utils.toString(ex));
                    return null;
                })
                .thenCompose(adminsGroupState -> patchUserStates(adminsGroupState))
                .exceptionally(ex -> {
                    logWarning("Couldn't patch admins user states: %s", Utils.toString(ex));
                    return null;
                });
    }

    private DeferredResult<Void> patchUserStates(UserGroupState groupState) {
        if (groupState == null) {
            return DeferredResult.completed(null);
        }
        return ProjectUtil.retrieveUserStatesForGroup(getHost(), groupState)
                .thenCompose(userStates -> {
                    List<String> userLinks = userStates.stream()
                            .map(us -> Service.getId(us.documentSelfLink))
                            .collect(Collectors.toList());
                    return UserGroupsUpdater.create()
                            .setGroupLink(groupState.documentSelfLink)
                            .setHost(getHost())
                            .setUsersToRemove(userLinks)
                            .update();
                });
    }

    @Override
    public ServiceDocument getDocumentTemplate() {
        ProjectState template = (ProjectState) super.getDocumentTemplate();
        ServiceUtils.setRetentionLimit(template);

        template.name = "resource-group-1";
        template.id = "project-id";
        template.description = "project1";
        template.isPublic = true;
        template.membersUserGroupLinks = Collections.singletonList("member-group");
        template.administratorsUserGroupLinks = Collections.singletonList("admin-group");

        return template;
    }

    private void retrieveExpandedState(ProjectState simpleState, Operation get) {
        ProjectUtil.expandProjectState(getHost(), simpleState, getUri())
                .thenAccept((expandedState) -> get.setBody(expandedState))
                .whenCompleteNotify(get);
    }

    private void validateState(ProjectState state) {
        Utils.validateState(getStateDescription(), state);
        AssertUtil.assertNotNullOrEmpty(state.name, ProjectState.FIELD_NAME_NAME);
    }

    private DeferredResult<ProjectState> createAdminAndMemberGroups(ProjectState projectState) {
        if (projectState.documentSelfLink.equals(DEFAULT_PROJECT_LINK)) {
            return DeferredResult.completed(projectState);
        }

        String projectId = Service.getId(projectState.documentSelfLink);
        UserGroupState membersGroupState = AuthUtil.buildProjectMembersUserGroup(projectId);
        UserGroupState adminsGroupState = AuthUtil.buildProjectAdminsUserGroup(projectId);

        if (projectState.administratorsUserGroupLinks != null
                && projectState.membersUserGroupLinks != null
                && projectState.administratorsUserGroupLinks.contains(
                adminsGroupState.documentSelfLink)
                && projectState.membersUserGroupLinks.contains(
                membersGroupState.documentSelfLink)) {
            // No groups to create
            return DeferredResult.completed(projectState);
        }

        ArrayList<DeferredResult<Void>> projectUserGroupsDeferredResults = new ArrayList<>();

        if (projectState.administratorsUserGroupLinks == null
                || !projectState.administratorsUserGroupLinks.contains(
                adminsGroupState.documentSelfLink)) {

            DeferredResult<Void> result = getHost().sendWithDeferredResult(
                    buildCreateUserGroupOperation(adminsGroupState), UserGroupState.class)
                    .thenAccept((groupState) -> {
                        if (projectState.administratorsUserGroupLinks == null) {
                            projectState.administratorsUserGroupLinks = new ArrayList<>();
                        }
                        projectState.administratorsUserGroupLinks.add(groupState.documentSelfLink);
                    });
            projectUserGroupsDeferredResults.add(result);
        }

        if (projectState.membersUserGroupLinks == null
                || !projectState.membersUserGroupLinks.contains(
                membersGroupState.documentSelfLink)) {

            DeferredResult<Void> result = getHost().sendWithDeferredResult(
                    buildCreateUserGroupOperation(membersGroupState), UserGroupState.class)
                    .thenAccept((groupState) -> {
                        if (projectState.membersUserGroupLinks == null) {
                            projectState.membersUserGroupLinks = new ArrayList<>();
                        }
                        projectState.membersUserGroupLinks.add(groupState.documentSelfLink);
                    });
            projectUserGroupsDeferredResults.add(result);
        }

        ArrayList<DeferredResult<List<RoleState>>> projectRolesDeferredResults = new ArrayList<>();

        return DeferredResult.allOf(projectUserGroupsDeferredResults)
                .thenCompose((ignore) -> {
                    return createProjectResourceGroup(projectState);
                })
                .thenCompose((resourceGroup) -> {
                    projectRolesDeferredResults.addAll(Arrays.asList(
                            createProjectAdminRole(projectState, resourceGroup.documentSelfLink),
                            createProjectMemberRole(projectState, resourceGroup.documentSelfLink)
                            ));
                    return DeferredResult.allOf(projectRolesDeferredResults);
                }).thenCompose((ignore) -> {
                    return DeferredResult.completed(projectState);
                });
    }

    private DeferredResult<ResourceGroupState> createProjectResourceGroup(ProjectState projectState) {
        String projectId = Service.getId(projectState.documentSelfLink);
        ResourceGroupState resourceGroupState = AuthUtil.buildProjectResourceGroup(projectId);

        return getHost().sendWithDeferredResult(
                buildCreateResourceGroupOperation(resourceGroupState), ResourceGroupState.class);
    }

    private DeferredResult<List<RoleState>> createProjectAdminRole(ProjectState projectState, String resourceGroupLink) {
        String projectId = Service.getId(projectState.documentSelfLink);

        List<DeferredResult<RoleState>> deferredResultRoles = new ArrayList<>();

        projectState.administratorsUserGroupLinks.stream().forEach((userGroupLink) -> {
            RoleState projectAdminRoleState = AuthUtil.buildProjectAdminsRole(projectId, userGroupLink, resourceGroupLink);
            DeferredResult<RoleState> deferredResultRole = getHost().sendWithDeferredResult(
                    buildCreateRoleOperation(projectAdminRoleState), RoleState.class);
            deferredResultRoles.add(deferredResultRole);
        });

        return DeferredResult.allOf(deferredResultRoles);
    }

    private DeferredResult<List<RoleState>> createProjectMemberRole(ProjectState projectState, String resourceGroupLink) {
        String projectId = Service.getId(projectState.documentSelfLink);

        List<DeferredResult<RoleState>> deferredResultRoles = new ArrayList<>();

        projectState.membersUserGroupLinks.stream().forEach((userGroupLink) -> {
            RoleState projectMemberRoleState = AuthUtil.buildProjectMembersRole(projectId, userGroupLink, resourceGroupLink);
            DeferredResult<RoleState> deferredResultRole = getHost().sendWithDeferredResult(
                    buildCreateRoleOperation(projectMemberRoleState), RoleState.class);
            deferredResultRoles.add(deferredResultRole);
        });

        return DeferredResult.allOf(deferredResultRoles);
    }

    private Operation buildCreateUserGroupOperation(UserGroupState state) {
        return Operation.createPost(getHost(), UserGroupService.FACTORY_LINK)
                .setReferer(getHost().getUri())
                .setBody(state);
    }

    private Operation buildCreateResourceGroupOperation(ResourceGroupState state) {
        return Operation.createPost(getHost(), ResourceGroupService.FACTORY_LINK)
                .setReferer(getHost().getUri())
                .setBody(state);
    }

    private Operation buildCreateRoleOperation(RoleState state) {
        return Operation.createPost(getHost(), RoleService.FACTORY_LINK)
                .setReferer(getHost().getUri())
                .setBody(state);
    }
}
