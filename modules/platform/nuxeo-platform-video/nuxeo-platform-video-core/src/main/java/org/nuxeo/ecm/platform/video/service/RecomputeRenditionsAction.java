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
package org.nuxeo.ecm.platform.video.service;

import static org.nuxeo.ecm.core.api.CoreSession.ALLOW_VERSION_WRITE;
import static org.nuxeo.ecm.core.api.versioning.VersioningService.DISABLE_AUTO_CHECKOUT;
import static org.nuxeo.ecm.core.bulk.BulkServiceImpl.STATUS_STREAM;
import static org.nuxeo.ecm.core.bulk.action.SetPropertiesAction.PARAM_DISABLE_AUDIT;
import static org.nuxeo.ecm.platform.picture.listener.PictureViewsGenerationListener.DISABLE_PICTURE_VIEWS_GENERATION_LISTENER;
import static org.nuxeo.lib.stream.computation.AbstractComputation.INPUT_1;
import static org.nuxeo.lib.stream.computation.AbstractComputation.OUTPUT_1;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentNotFoundException;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.model.Property;
import org.nuxeo.ecm.core.bulk.action.computation.AbstractBulkComputation;
import org.nuxeo.ecm.core.bulk.message.BulkCommand;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventService;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.ecm.platform.video.TranscodedVideo;
import org.nuxeo.ecm.platform.video.Video;
import org.nuxeo.ecm.platform.video.VideoConstants;
import org.nuxeo.ecm.platform.video.VideoDocument;
import org.nuxeo.ecm.platform.video.adapter.VideoDocumentAdapter;
import org.nuxeo.lib.stream.computation.Topology;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.stream.StreamProcessorTopology;

/**
 * BAF Computation that fills video renditions for the blob property described
 * by the given xpath.
 *
 * @since 11.4
 */
public class RecomputeRenditionsAction implements StreamProcessorTopology {

	private static final Logger log = LogManager.getLogger(RecomputeRenditionsAction.class);

	public static final String ACTION_NAME = "recomputeRenditions";

	// @since 11.4
	public static final String ACTION_FULL_NAME = "bulk/" + ACTION_NAME;

	public static final String PARAM_XPATH = "xpath";

	public static final String PARAM_CONVERSION_NAME = "conversionName";

	@Override
	public Topology getTopology(Map<String, String> options) {
		return Topology.builder().addComputation(RecomputeRenditionsComputation::new, //
				Arrays.asList(INPUT_1 + ":" + ACTION_FULL_NAME, OUTPUT_1 + ":" + STATUS_STREAM)).build();
	}

	public static class RecomputeRenditionsComputation extends AbstractBulkComputation {

		public static final String PICTURE_VIEWS_GENERATION_DONE_EVENT = "pictureViewsGenerationDone";

		protected String xpath;

		protected String conversionName;

		public RecomputeRenditionsComputation() {
			super(ACTION_FULL_NAME);
		}

		@Override
		public void startBucket(String bucketKey) {
			BulkCommand command = getCurrentCommand();
			xpath = command.getParam(PARAM_XPATH);
			conversionName = command.getParam(PARAM_CONVERSION_NAME);
		}

		@Override
		protected void compute(CoreSession session, List<String> ids, Map<String, Serializable> properties) {
			log.debug("Compute action: {} for doc ids: {}", ACTION_NAME, ids);
			for (String docId : ids) {
				if (!session.exists(new IdRef(docId))) {
					log.debug("Doc id doesn't exist: {}", docId);
					continue;
				}

				DocumentModel workingDocument = session.getDocument(new IdRef(docId));
				Property fileProp = workingDocument.getProperty(xpath);
				Blob blob = (Blob) fileProp.getValue();
				if (blob == null) {
					// do nothing
					log.debug("No blob for doc: {}", workingDocument);
					continue;
				}

				String title = workingDocument.getTitle();
				List<Map<String, Serializable>> transcodedVideos = new ArrayList<>();

				try {
					VideoDocument videoDoc = workingDocument.getAdapter(VideoDocument.class);
					VideoService videoService = Framework.getService(VideoService.class);
					Video video = videoDoc.getVideo();

					List<String> conversionNames = new ArrayList<>();

					if ("*".equals(conversionName)) {
						// Recomputing all available renditions
						for (VideoConversion conversion : videoService.getAvailableVideoConversions()) {
							conversionNames.add(conversion.getName());
						}
					} else {
						// Recomputing wanted renditions
						if(conversionName.contains("-")) {
							// Recomputing a specific rendition list
							String[] conversions = conversionName.split("-");
							for (int i = 0; i < conversions.length; i++) {
								conversionNames.add(conversions[i]);
							}
						} else {
							// Recomputing a specific rendition
							conversionNames.add(conversionName);
						}
					}
					for (String conversion : conversionNames) {
						conversionName = conversion;
						TranscodedVideo transcodedVideo = videoService.convert(video, conversionName);
						transcodedVideos.add(transcodedVideo.toMap());

					}
					workingDocument.setPropertyValue(VideoConstants.TRANSCODED_VIDEOS_PROPERTY,
							(Serializable) transcodedVideos);
					session.saveDocument(workingDocument);
				} catch (DocumentNotFoundException e) {
					// a parent of the document may have been deleted.
					continue;
				}
				if (workingDocument.isVersion()) {
					workingDocument.putContextData(ALLOW_VERSION_WRITE, Boolean.TRUE);
				}
				workingDocument.putContextData("disableNotificationService", Boolean.TRUE);
				workingDocument.putContextData(PARAM_DISABLE_AUDIT, Boolean.TRUE);
				workingDocument.putContextData(DISABLE_AUTO_CHECKOUT, Boolean.TRUE);
				workingDocument.putContextData("", Boolean.TRUE);
				session.saveDocument(workingDocument);

				DocumentEventContext ctx = new DocumentEventContext(session, session.getPrincipal(), workingDocument);
				Event event = ctx.newEvent("");
				Framework.getService(EventService.class).fireEvent(event);
			}
		}

	}

}
