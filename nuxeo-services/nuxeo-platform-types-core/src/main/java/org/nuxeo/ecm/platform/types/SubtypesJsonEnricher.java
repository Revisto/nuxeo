/*
 * (C) Copyright 2020 Nuxeo SA (http://nuxeo.com/) and others.
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
 *     Charles Boidot <cboidot@nuxeo.com>
 */

package org.nuxeo.ecm.platform.types;

import static org.nuxeo.ecm.core.io.registry.reflect.Instantiations.SINGLETON;
import static org.nuxeo.ecm.core.io.registry.reflect.Priorities.REFERENCE;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import org.apache.commons.lang3.BooleanUtils;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.io.marshallers.json.enrichers.AbstractJsonEnricher;
import org.nuxeo.ecm.core.io.registry.reflect.Setup;
import org.nuxeo.ecm.core.schema.SchemaManager;
import org.nuxeo.ecm.platform.types.localconfiguration.UITypesConfigurationConstants;
import org.nuxeo.runtime.api.Framework;

import com.fasterxml.jackson.core.JsonGenerator;

/**
 * Enrich {@link DocumentModel} JSON object with an array of the document types that can be created under the current
 * document taking account the local configuration.
 *
 * @since 11.4
 */
@Setup(mode = SINGLETON, priority = REFERENCE)
public class SubtypesJsonEnricher extends AbstractJsonEnricher<DocumentModel> {

    public static final String NAME = "subtypes";

    public SubtypesJsonEnricher() {
        super(NAME);
    }

    @Override
    public void write(JsonGenerator jg, DocumentModel enriched) throws IOException {
        SchemaManager schemaManager = Framework.getService(SchemaManager.class);
        Collection<String> defaultSubtypes = enriched.getDocumentType().getAllowedSubtypes();
        Collection<String> subtypes = new HashSet<String>();

        if (enriched.hasFacet(UITypesConfigurationConstants.UI_TYPES_CONFIGURATION_FACET)) {
            Boolean denyAllTypes = (Boolean) enriched.getPropertyValue(
                    UITypesConfigurationConstants.UI_TYPES_CONFIGURATION_DENY_ALL_TYPES_PROPERTY);
            if (BooleanUtils.isNotTrue(denyAllTypes)) {
                String[] allowedTypesProperty = (String[]) enriched.getPropertyValue(
                        UITypesConfigurationConstants.UI_TYPES_CONFIGURATION_ALLOWED_TYPES_PROPERTY);
                String[] deniedTypesProperty = (String[]) enriched.getPropertyValue(
                        UITypesConfigurationConstants.UI_TYPES_CONFIGURATION_DENIED_TYPES_PROPERTY);

                List<String> allowedTypes = new ArrayList<>();
                List<String> deniedTypes = new ArrayList<>();
                if (allowedTypesProperty != null) {
                    allowedTypes = Arrays.asList(allowedTypesProperty);
                }
                if (deniedTypesProperty != null) {
                    deniedTypes = Arrays.asList(deniedTypesProperty);
                }
                if (!(allowedTypes.isEmpty() && deniedTypes.isEmpty())) {
                    for (String subtype : defaultSubtypes) {
                        if (!deniedTypes.contains(subtype)
                                && (allowedTypes.isEmpty() || allowedTypes.contains(subtype))) {
                            subtypes.add(subtype);
                        }
                    }
                }
            }
        } else {
            subtypes.addAll(defaultSubtypes);
        }

        jg.writeFieldName(NAME);
        jg.writeStartArray();
        for (String subtype : subtypes) {
            jg.writeStartObject();
            jg.writeStringField("type", subtype);
            jg.writeArrayFieldStart("facets");
            for (String facet : schemaManager.getDocumentType(subtype).getFacets()) {
                jg.writeString(facet);
            }
            jg.writeEndArray();
            jg.writeEndObject();
        }
        jg.writeEndArray();
    }
}
