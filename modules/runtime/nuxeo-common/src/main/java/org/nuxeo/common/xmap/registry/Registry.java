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

import org.nuxeo.common.xmap.Context;
import org.nuxeo.common.xmap.XAnnotatedObject;
import org.w3c.dom.Element;

/**
 * @since 11.5
 */
public interface Registry {

    /**
     * Returns true if registry is just a placeholder and should not be used for registrations.
     */
    boolean isNull();

    /**
     * Marks the registry with given identifier.
     */
    void mark(String id);

    /**
     * Returns true if registry has been marked with given id.
     * <p>
     * After {@link #mark(String)} or {@link #register(Context, XAnnotatedObject, Element, String)} are called with
     * given marker, this method should return true.
     * <p>
     * After {@link #unregister(String)} is called with given marker, this method should return false.
     */
    boolean isMarked(String id);

    /**
     * Registers given element with given marker identifier.
     */
    void register(Context ctx, XAnnotatedObject xObject, Element element, String marker);

    /**
     * Unregisters all elements previously registered with given marker identifier.
     */
    void unregister(String marker);

}