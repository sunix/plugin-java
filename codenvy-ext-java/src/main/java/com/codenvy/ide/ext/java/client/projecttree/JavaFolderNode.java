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
import com.codenvy.ide.api.projecttree.AbstractTreeNode;
import com.codenvy.ide.api.projecttree.TreeSettings;
import com.codenvy.ide.api.projecttree.generic.FolderNode;
import com.codenvy.ide.rest.DtoUnmarshallerFactory;
import com.google.web.bindery.event.shared.EventBus;

/**
 * {@link FolderNode} that may contains {@link SourceFolderNode}s.
 *
 * @author Artem Zatsarynnyy
 */
public class JavaFolderNode extends FolderNode {

    public JavaFolderNode(AbstractTreeNode parent, ItemReference data, JavaTreeStructure treeStructure, TreeSettings settings,
                          EventBus eventBus, EditorAgent editorAgent, ProjectServiceClient projectServiceClient,
                          DtoUnmarshallerFactory dtoUnmarshallerFactory) {
        super(parent, data, treeStructure, settings, eventBus, editorAgent, projectServiceClient, dtoUnmarshallerFactory);
    }

    /** Tests if the specified item is a source folder. */
    protected static boolean isSourceFolder(ItemReference item) {
        // TODO: read source folders from project/module attributes
        return isFolder(item) && item.getPath().endsWith("src/main/java") || item.getPath().endsWith("src/test/java");
    }

    /** {@inheritDoc} */
    @Override
    protected AbstractTreeNode<?> createChildNode(ItemReference item) {
        if (isSourceFolder(item)) {
            return ((JavaTreeStructure)treeStructure).newSourceFolderNode(this, item);
        } else if (isFolder(item)) {
            return ((JavaTreeStructure)treeStructure).newJavaFolderNode(this, item);
        } else {
            return super.createChildNode(item);
        }
    }
}
