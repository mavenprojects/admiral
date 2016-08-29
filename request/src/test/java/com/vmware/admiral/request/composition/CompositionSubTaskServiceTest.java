/*
 * Copyright (c) 2016 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package com.vmware.admiral.request.composition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.vmware.admiral.compute.ResourceType;
import com.vmware.admiral.compute.container.ContainerDescriptionService;
import com.vmware.admiral.compute.container.ContainerDescriptionService.ContainerDescription;
import com.vmware.admiral.compute.container.ContainerService.ContainerState;
import com.vmware.admiral.request.RequestBaseTest;
import com.vmware.admiral.request.composition.CompositionSubTaskService.CompositionSubTaskState;
import com.vmware.admiral.request.util.TestRequestStateFactory;
import com.vmware.admiral.service.common.ServiceTaskCallback;
import com.vmware.admiral.service.test.MockDockerAdapterService;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;

public class CompositionSubTaskServiceTest extends RequestBaseTest {

    @Test
    public void testTaskWithoutDependencies() throws Throwable {
        CompositionSubTaskState subTask = createCompositionSubTask("test1");

        subTask = doPost(subTask);

        subTask = waitForTaskSuccess(subTask.documentSelfLink, CompositionSubTaskState.class);

        assertValidCompositionSubTask(subTask);
    }

    @Test
    public void testTaskWithDependents() throws Throwable {
        CompositionSubTaskState subTask1 = createCompositionSubTask("test1");
        CompositionSubTaskState subTask2 = createCompositionSubTask("test2");
        CompositionSubTaskState subTask3 = createCompositionSubTask("test3");
        CompositionSubTaskState subTask4 = createCompositionSubTask("test4");

        subTask1.dependentLinks = new HashSet<>(
                Arrays.asList(subTask2.documentSelfLink, subTask4.documentSelfLink));
        subTask2.dependsOnLinks = new HashSet<>(Arrays.asList(subTask1.documentSelfLink));
        subTask2.dependentLinks = new HashSet<>(
                Arrays.asList(subTask3.documentSelfLink, subTask4.documentSelfLink));
        subTask3.dependsOnLinks = new HashSet<>(Arrays.asList(subTask2.documentSelfLink));
        subTask4.dependsOnLinks = new HashSet<>(
                Arrays.asList(subTask1.documentSelfLink, subTask2.documentSelfLink));

        subTask1 = doPost(subTask1);
        subTask2 = doPost(subTask2);
        subTask3 = doPost(subTask3);
        subTask4 = doPost(subTask4);

        subTask1 = waitForTaskSuccess(subTask1.documentSelfLink, CompositionSubTaskState.class);
        subTask2 = waitForTaskSuccess(subTask2.documentSelfLink, CompositionSubTaskState.class);
        subTask3 = waitForTaskSuccess(subTask3.documentSelfLink, CompositionSubTaskState.class);
        subTask4 = waitForTaskSuccess(subTask4.documentSelfLink, CompositionSubTaskState.class);

        assertValidCompositionSubTask(subTask1);
        assertValidCompositionSubTask(subTask2);
        assertValidCompositionSubTask(subTask3);
        assertValidCompositionSubTask(subTask4);
    }

    @Test
    public void testFailureInTaskShouldCompleteAllWithError() throws Throwable {
        CompositionSubTaskState subTask1 = createCompositionSubTask("test1");
        CompositionSubTaskState subTask2 = createCompositionSubTask("test2");

        subTask1.dependentLinks = new HashSet<>(Arrays.asList(subTask2.documentSelfLink));
        subTask2.dependsOnLinks = new HashSet<>(Arrays.asList(subTask1.documentSelfLink));

        // simulate failure in subTaskq
        subTask1.customProperties = new HashMap<>();
        subTask1.customProperties.put(MockDockerAdapterService.FAILURE_EXPECTED,
                Boolean.TRUE.toString());

        subTask1 = doPost(subTask1);
        subTask2 = doPost(subTask2);

        subTask1 = waitForTaskError(subTask1.documentSelfLink, CompositionSubTaskState.class);
        subTask2 = waitForTaskError(subTask2.documentSelfLink, CompositionSubTaskState.class);

        assertNull(subTask1.resourceLinks);
        assertNull(subTask2.resourceLinks);
    }

    private CompositionSubTaskState createCompositionSubTask(String name) throws Throwable {
        ContainerDescription desc = createDescription(name);
        CompositionSubTaskState compositionSubTaskState = new CompositionSubTaskState();
        compositionSubTaskState.name = name;
        compositionSubTaskState.tenantLinks = TestRequestStateFactory.createTenantLinks(TestRequestStateFactory.TENANT_NAME);
        compositionSubTaskState.resourceType = ResourceType.CONTAINER_TYPE.getName();
        compositionSubTaskState.documentSelfLink = UriUtils.buildUriPath(
                CompositionSubTaskFactoryService.SELF_LINK, UUID.randomUUID().toString());
        compositionSubTaskState.resourceDescriptionLink = desc.documentSelfLink;
        compositionSubTaskState.requestId = UUID.randomUUID().toString();
        compositionSubTaskState.serviceTaskCallback = ServiceTaskCallback.createEmpty();
        compositionSubTaskState.documentExpirationTimeMicros = Utils.getNowMicrosUtc()
                + TimeUnit.HOURS.toMicros(5);

        return compositionSubTaskState;
    }

    private void assertValidCompositionSubTask(CompositionSubTaskState subTask) throws Throwable {
        assertNotNull(subTask);
        assertNotNull("Resource links null for compositionSubTask: " + subTask.documentSelfLink,
                subTask.resourceLinks);
        assertEquals(1, subTask.resourceLinks.size());

        ContainerState container = getDocument(ContainerState.class, subTask.resourceLinks.get(0));
        assertNotNull(container);
        addForDeletion(container);
    }

    private CompositionSubTaskState doPost(CompositionSubTaskState compositionSubTaskState)
            throws Throwable {
        return doPost(compositionSubTaskState, CompositionSubTaskFactoryService.SELF_LINK);
    }

    private ContainerDescription createDescription(String name) throws Throwable {
        ContainerDescription desc = TestRequestStateFactory.createContainerDescription();
        desc.documentSelfLink = UUID.randomUUID().toString();
        desc.name = name;
        desc.links = null;
        desc.volumesFrom = null;
        desc.pod = null;
        desc.portBindings = null;
        desc = doPost(desc, ContainerDescriptionService.FACTORY_LINK);
        assertNotNull(desc);
        addForDeletion(desc);

        return desc;
    }

}
