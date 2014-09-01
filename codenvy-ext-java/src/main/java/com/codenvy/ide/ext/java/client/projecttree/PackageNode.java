/*******************************************************************************
 * Copyright (c) 2012-2014 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package com.codenvy.ide.ext.java.client.projecttree;

import com.codenvy.api.project.gwt.client.ProjectServiceClient;
import com.codenvy.api.project.shared.dto.ItemReference;
import com.codenvy.ide.api.editor.EditorAgent;
import com.codenvy.ide.api.icon.IconRegistry;
import com.codenvy.ide.api.projecttree.AbstractTreeNode;
import com.codenvy.ide.api.projecttree.TreeSettings;
import com.codenvy.ide.api.projecttree.generic.FolderNode;
import com.codenvy.ide.collections.Array;
import com.codenvy.ide.collections.Collections;
import com.codenvy.ide.rest.AsyncRequestCallback;
import com.codenvy.ide.rest.DtoUnmarshallerFactory;
import com.codenvy.ide.rest.Unmarshallable;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.web.bindery.event.shared.EventBus;

/**
 * Node that represents a java package.
 *
 * @author Artem Zatsarynnyy
 */
public class PackageNode extends FolderNode {

    public PackageNode(AbstractTreeNode parent, ItemReference data, JavaTreeStructure treeStructure, TreeSettings settings,
                       EventBus eventBus, EditorAgent editorAgent, ProjectServiceClient projectServiceClient,
                       DtoUnmarshallerFactory dtoUnmarshallerFactory, IconRegistry iconRegistry) {
        super(parent, data, treeStructure, settings, eventBus, editorAgent, projectServiceClient, dtoUnmarshallerFactory);

        getPresentation().setSvgIcon(iconRegistry.getIcon("java.package").getSVGImage());
    }

    /** {@inheritDoc} */
    @Override
    public void refreshChildren(final AsyncCallback<AbstractTreeNode<?>> callback) {
        final Unmarshallable<Array<ItemReference>> unmarshaller = dtoUnmarshallerFactory.newArrayUnmarshaller(ItemReference.class);
        projectServiceClient.getChildren(data.getPath(), new AsyncRequestCallback<Array<ItemReference>>(unmarshaller) {
            @Override
            protected void onSuccess(Array<ItemReference> children) {
                final boolean isShowHiddenItems = settings.isShowHiddenItems();
                Array<AbstractTreeNode<?>> newChildren = Collections.createArray();
                setChildren(newChildren);
                for (ItemReference item : children.asIterable()) {
                    if (isShowHiddenItems || !item.getName().startsWith(".")) {
                        if (isFile(item)) {
                            if (item.getName().endsWith(".java")) {
                                newChildren.add(((JavaTreeStructure)treeStructure).newSourceFileNode(PackageNode.this, item));
                            } else {
                                newChildren.add(treeStructure.newFileNode(PackageNode.this, item));
                            }
                        } else if (isFolder(item)) {
                            newChildren.add(((JavaTreeStructure)treeStructure).newPackageNode(PackageNode.this, item));
                        }
                    }
                }
                callback.onSuccess(PackageNode.this);
            }

            @Override
            protected void onFailure(Throwable exception) {
                callback.onFailure(exception);
            }
        });
    }

    /** {@inheritDoc} */
    @Override
    public boolean isRenemable() {
        // Do not allow to rename Java package as simple folder.
        // Need to implement rename refactoring for this type of node.
        return false;
    }
}
