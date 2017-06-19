/*
 * Copyright 2017, Red Hat, Inc. and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.zanata.rest.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.jetbrains.annotations.NotNull;
import org.zanata.async.AsyncTaskHandle;
import org.zanata.async.AsyncTaskHandleManager;
import org.zanata.async.UserTriggerableTaskHandle;
import org.zanata.exception.AuthorizationException;
import org.zanata.rest.dto.ProcessStatus;
import org.zanata.security.ZanataIdentity;

import com.webcohesion.enunciate.metadata.rs.TypeHint;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * This endpoint should let us control and query all async tasks.
 *
 * TODO AsynchronousProcessResourceService are specific for CLI push and pull
 *
 * @see AsynchronousProcessResourceService
 * @author Patrick Huang
 *         <a href="mailto:pahuang@redhat.com">pahuang@redhat.com</a>
 */
@Path("/process")
@Produces(MediaType.APPLICATION_JSON)
public class AsyncProcessService implements RestResource {
    private static final long serialVersionUID = 1L;

    @Inject
    private AsyncTaskHandleManager asyncTaskHandleManager;

    @Inject
    private ZanataIdentity identity;

    @SuppressFBWarnings("SE_BAD_FIELD")
    @Context
    private UriInfo uriInfo;

    /**
     * Get an async task's status.
     *
     * @param keyId
     *            task id
     * @return The following response status codes will be returned from this
     *         operation:<br>
     *         OK(200) - The contents of the response will indicate the process
     *         identifier which may be used to query for its status or a message
     *         indicating what happened.<br>
     *         INTERNAL SERVER ERROR(500) - If there is an unexpected error in
     *         the server while performing this operation.
     */
    @Path("key/{keyId}")
    @GET
    @TypeHint(ProcessStatus.class)
    public Response getAsyncProcessStatus(@PathParam("keyId") String keyId) {
        AsyncTaskHandle handle = asyncTaskHandleManager.getHandleByKeyId(keyId);
        if (handle == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        ProcessStatus status = handleToProcessStatus(handle,
                uriInfo.getRequestUri().toString());
        return Response.ok(status).build();
    }

    /**
     * Get all async task's status.
     *
     * @param includeFinished
     *            whether to include finished tasks
     *
     * @return The following response status codes will be returned from this
     *         operation:<br>
     *         OK(200) - The contents of the response will indicate all
     *         background processes which may be used to query for its status or
     *         a message indicating what happened.<br>
     *         INTERNAL SERVER ERROR(500) - If there is an unexpected error in
     *         the server while performing this operation.
     */
    @GET
    public Response getAllAsyncProcess(
            @QueryParam("includeFinished") @DefaultValue("false") boolean includeFinished) {
        Map<AsyncTaskHandleManager.AsyncTaskKey, AsyncTaskHandle> tasks;
        if (includeFinished) {
            tasks = asyncTaskHandleManager.getAllTasks();
        } else {
            tasks = asyncTaskHandleManager.getRunningTasks();
        }
        List<ProcessStatus> processStatuses = tasks.entrySet().stream()
                .map(taskEntry -> handleToProcessStatus(taskEntry.getValue(),
                        uriInfo.getBaseUri() + "process/key/"
                                + taskEntry.getKey().id()))
                .collect(Collectors.toList());
        return Response.ok(processStatuses).build();
    }

    /**
     * Cancel a specific async task.
     *
     * @param keyId
     *            task id
     *
     * @return The following response status codes will be returned from this
     *         operation:<br>
     *         OK(200) - The contents of the response will indicate the process
     *         identifier which may be used to query for its status or a message
     *         indicating what happened.<br>
     *         INTERNAL SERVER ERROR(500) - If there is an unexpected error in
     *         the server while performing this operation.
     */
    @POST
    @Path("cancel/key/{keyId}")
    public Response cancelAsyncProcess(@PathParam("keyId") String keyId) {
        AsyncTaskHandle handle = asyncTaskHandleManager.getHandleByKeyId(keyId);
        if (handle == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (handle.isStarted() && !handle.isCancelled() && !handle.isDone()) {
            if (!identity.hasRole("admin") && handle instanceof UserTriggerableTaskHandle) {
                UserTriggerableTaskHandle taskHandle =
                        (UserTriggerableTaskHandle) handle;
                if (!taskHandle.canCancel(identity.getAccountUsername())) {
                    throw new AuthorizationException(
                            "Only the task triggerer or admin can cancel the task.");
                }
            }

            handle.cancel(true);
            handle.setCancelledBy(identity.getAccountUsername());
            handle.setCancelledTime(System.currentTimeMillis());

        }
        ProcessStatus processStatus = handleToProcessStatus(handle,
                uriInfo.getBaseUri() + "cancel/key" + keyId);
        return Response.ok(processStatus).build();
    }

    @NotNull
    static ProcessStatus handleToProcessStatus(AsyncTaskHandle handle,
            String url) {
        ProcessStatus status = new ProcessStatus();
        status.setStatusCode(
                handle.isDone() ? ProcessStatus.ProcessStatusCode.Finished
                        : ProcessStatus.ProcessStatusCode.Running);
        int perComplete = 100;
        if (handle.getMaxProgress() > 0) {
            perComplete = (int) (handle.getCurrentProgress() * 100
                    / handle.getMaxProgress());
        }
        status.setPercentageComplete(perComplete);
        status.setUrl(url);
        if (handle.isCancelled()) {
            status.setStatusCode(ProcessStatus.ProcessStatusCode.Cancelled);
            status.addMessage("Cancelled");
        } else if (handle.isDone()) {
            Object result = null;
            try {
                result = handle.getResult();
            } catch (InterruptedException e) {
                // The process was forcefully cancelled
                status.setStatusCode(ProcessStatus.ProcessStatusCode.Failed);
                status.addMessage(e.getMessage());
            } catch (ExecutionException e) {
                // Exception thrown while running the task
                status.setStatusCode(ProcessStatus.ProcessStatusCode.Failed);
                status.addMessage(e.getCause().getMessage());
            } catch (Exception e) {
                status.setStatusCode(ProcessStatus.ProcessStatusCode.Failed);
                status.addMessage("Unknown exception:" + e.getMessage());
            }
            // TODO Need to find a generic way of returning all object types.
            if (result != null) {
                status.addMessage(result.toString());
            }
        }
        return status;
    }
}
