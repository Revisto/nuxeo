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
 *     Anahide Tchertchian
 */
package org.nuxeo.common.xmap.registry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.nuxeo.common.xmap.Context;
import org.nuxeo.common.xmap.XAnnotatedObject;
import org.w3c.dom.Element;

/**
 * Abstract class for {@link Registry} common logics.
 *
 * @since 11.5
 */
public abstract class AbstractRegistry implements Registry {

    protected boolean initialized = false;

    protected Set<String> markers = new HashSet<>();

    protected List<RegistryContribution> registrations = new ArrayList<>();

    public AbstractRegistry() {
    }

    @Override
    public boolean isNull() {
        return false;
    }

    protected boolean isInitialized() {
        return initialized;
    }

    protected void setInitialized(boolean initialized) {
        this.initialized = initialized;
    }

    protected void checkInitialized() {
        if (isInitialized()) {
            return;
        }
        initialize();
    }

    protected void initialize() {
        registrations.forEach(rc -> register(rc.getContext(), rc.getObject(), rc.getElement()));
        setInitialized(true);
    }

    @Override
    public void mark(String id) {
        markers.add(id);
    }

    @Override
    public boolean isMarked(String id) {
        return markers.contains(id);
    }

    @Override
    public void register(Context ctx, XAnnotatedObject xObject, Element element, String marker) {
        markers.add(marker);
        registrations.add(new RegistryContribution(ctx, xObject, element, marker));
        setInitialized(false);
    }

    @Override
    public void unregister(String marker) {
        if (marker == null || !isMarked(marker)) {
            return;
        }
        markers.remove(marker);
        Iterator<RegistryContribution> it = registrations.iterator();
        while (it.hasNext()) {
            RegistryContribution reg = it.next();
            if (marker.equals(reg.getMarker())) {
                it.remove();
            }
        }
        setInitialized(false);
    }

    protected abstract void register(Context ctx, XAnnotatedObject xObject, Element element);

}