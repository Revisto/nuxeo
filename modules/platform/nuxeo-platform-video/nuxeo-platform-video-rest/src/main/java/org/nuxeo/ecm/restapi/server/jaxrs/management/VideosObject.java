/*
 * (C) Copyright 2020 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Charles Boidot
 */
package org.nuxeo.ecm.restapi.server.jaxrs.management;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.nuxeo.ecm.core.api.security.SecurityConstants.SYSTEM_USERNAME;

import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;

import org.apache.commons.lang3.StringUtils;
import org.nuxeo.ecm.core.bulk.BulkService;
import org.nuxeo.ecm.core.bulk.message.BulkCommand;
import org.nuxeo.ecm.core.bulk.message.BulkStatus;
import org.nuxeo.ecm.platform.video.service.RecomputeRenditionsAction;
import org.nuxeo.ecm.webengine.model.WebObject;
import org.nuxeo.ecm.webengine.model.impl.AbstractResource;
import org.nuxeo.ecm.webengine.model.impl.ResourceTypeImpl;
import org.nuxeo.runtime.api.Framework;

/**
 * since 11.4
 */
@WebObject(type = ManagementObject.MANAGEMENT_OBJECT_PREFIX + "videos")
@Produces(APPLICATION_JSON)
public class VideosObject extends AbstractResource<ResourceTypeImpl> {

	public static final String VIDEOS_DEFAULT_QUERY = "SELECT * FROM Document WHERE ecm:mixinType = 'Video'";

	public static final String VIDEOS_DEFAULT_CONVERSION = "*";

	/**
	 * Recomputes video renditions for the documents matching the given query or
	 * {@link #VIDEOS_DEFAULT_QUERY} if not provided.
	 *
	 * @param query a custom query to specify which videos should be processed
	 * @return the {@link BulkStatus} of the command
	 */
	@POST
	@Path("recompute/{conversionName}/")
	public BulkStatus doPostVideos(@FormParam("query") String query,
			@PathParam("conversionName") String conversionName) {
		String finalQuery = StringUtils.defaultIfBlank(query, VIDEOS_DEFAULT_QUERY);
		String finalConversions = StringUtils.defaultIfBlank(conversionName, VIDEOS_DEFAULT_CONVERSION);
		BulkService bulkService = Framework.getService(BulkService.class);
		String commandId = bulkService
				.submit(new BulkCommand.Builder(RecomputeRenditionsAction.ACTION_NAME, finalQuery, SYSTEM_USERNAME)
						.repository(ctx.getCoreSession().getRepositoryName())
						.param(RecomputeRenditionsAction.PARAM_XPATH, "file:content")
						.param(RecomputeRenditionsAction.PARAM_CONVERSION_NAME, finalConversions).build());
		return bulkService.getStatus(commandId);
	}

	@POST
	@Path("recompute/")
	public BulkStatus doPostVideos(@FormParam("query") String query) {
		String finalQuery = StringUtils.defaultIfBlank(query, VIDEOS_DEFAULT_QUERY);
		String finalConversions = VIDEOS_DEFAULT_CONVERSION;
		BulkService bulkService = Framework.getService(BulkService.class);
		String commandId = bulkService
				.submit(new BulkCommand.Builder(RecomputeRenditionsAction.ACTION_NAME, finalQuery, SYSTEM_USERNAME)
						.repository(ctx.getCoreSession().getRepositoryName())
						.param(RecomputeRenditionsAction.PARAM_XPATH, "file:content")
						.param(RecomputeRenditionsAction.PARAM_CONVERSION_NAME, finalConversions).build());
		return bulkService.getStatus(commandId);
	}
}
