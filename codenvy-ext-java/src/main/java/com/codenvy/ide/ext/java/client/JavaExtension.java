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
package com.codenvy.ide.ext.java.client;

import com.codenvy.api.analytics.logger.AnalyticsEventLogger;
import com.codenvy.api.builder.BuildStatus;
import com.codenvy.api.builder.dto.BuildTaskDescriptor;
import com.codenvy.api.project.shared.dto.ProjectDescriptor;
import com.codenvy.ide.api.action.ActionManager;
import com.codenvy.ide.api.action.DefaultActionGroup;
import com.codenvy.ide.api.app.AppContext;
import com.codenvy.ide.api.build.BuildContext;
import com.codenvy.ide.api.editor.CodenvyTextEditor;
import com.codenvy.ide.api.editor.EditorAgent;
import com.codenvy.ide.api.editor.EditorPartPresenter;
import com.codenvy.ide.api.editor.EditorRegistry;
import com.codenvy.ide.api.event.FileEvent;
import com.codenvy.ide.api.event.FileEventHandler;
import com.codenvy.ide.api.event.ProjectActionEvent;
import com.codenvy.ide.api.event.ProjectActionHandler;
import com.codenvy.ide.api.extension.Extension;
import com.codenvy.ide.api.filetypes.FileType;
import com.codenvy.ide.api.filetypes.FileTypeRegistry;
import com.codenvy.ide.api.icon.Icon;
import com.codenvy.ide.api.icon.IconRegistry;
import com.codenvy.ide.api.notification.Notification;
import com.codenvy.ide.api.notification.NotificationManager;
import com.codenvy.ide.api.text.Document;
import com.codenvy.ide.api.texteditor.reconciler.Reconciler;
import com.codenvy.ide.api.texteditor.reconciler.ReconcilingStrategy;
import com.codenvy.ide.collections.StringMap;
import com.codenvy.ide.ext.java.client.action.NewJavaSourceFileAction;
import com.codenvy.ide.ext.java.client.action.NewPackageAction;
import com.codenvy.ide.ext.java.client.action.UpdateDependencyAction;
import com.codenvy.ide.ext.java.client.editor.JavaEditorProvider;
import com.codenvy.ide.ext.java.client.editor.JavaParserWorker;
import com.codenvy.ide.ext.java.client.editor.JavaReconcilerStrategy;
import com.codenvy.ide.ext.java.shared.Constants;
import com.codenvy.ide.extension.builder.client.build.BuildProjectPresenter;
import com.codenvy.ide.rest.AsyncRequestCallback;
import com.codenvy.ide.rest.AsyncRequestFactory;
import com.codenvy.ide.rest.DtoUnmarshallerFactory;
import com.codenvy.ide.rest.StringUnmarshaller;
import com.codenvy.ide.util.loging.Log;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.google.web.bindery.event.shared.EventBus;

import static com.codenvy.ide.api.action.IdeActions.GROUP_BUILD;
import static com.codenvy.ide.api.action.IdeActions.GROUP_BUILD_CONTEXT_MENU;
import static com.codenvy.ide.api.action.IdeActions.GROUP_FILE_NEW;
import static com.codenvy.ide.api.notification.Notification.Status.FINISHED;
import static com.codenvy.ide.api.notification.Notification.Status.PROGRESS;
import static com.codenvy.ide.api.notification.Notification.Type.ERROR;

/** @author Evgen Vidolob */
@Extension(title = "Java syntax highlighting and code autocompletion.", version = "3.0.0")
public class JavaExtension {

    public static final String BUILD_OUTPUT_CHANNEL = "builder:output:";

    boolean updating      = false;
    boolean needForUpdate = false;
    private NotificationManager      notificationManager;
    private String                   workspaceId;
    private AsyncRequestFactory      asyncRequestFactory;
    private EditorAgent              editorAgent;
    private JavaLocalizationConstant localizationConstant;
    private JavaParserWorker         parserWorker;
    private BuildContext             buildContext;
    private AppContext               appContext;
    private BuildProjectPresenter presenter;
    private DtoUnmarshallerFactory dtoUnmarshallerFactory;

    @Inject
    public JavaExtension(FileTypeRegistry fileTypeRegistry,
                         NotificationManager notificationManager,
                         EditorRegistry editorRegistry,
                         JavaEditorProvider javaEditorProvider,
                         EventBus eventBus,
                         @Named("workspaceId") String workspaceId,
                         ActionManager actionManager,
                         AsyncRequestFactory asyncRequestFactory,
                         EditorAgent editorAgent,
                         AnalyticsEventLogger eventLogger,
                         JavaResources resources,
                         JavaLocalizationConstant localizationConstant,
                         NewPackageAction newPackageAction,
                         NewJavaSourceFileAction newJavaSourceFileAction,
                         JavaParserWorker parserWorker,
                         @Named("JavaFileType") FileType javaFile,
                         BuildContext buildContext,
                         final AppContext appContext,
                         BuildProjectPresenter presenter,
                         DtoUnmarshallerFactory dtoUnmarshallerFactory) {
        this.notificationManager = notificationManager;
        this.workspaceId = workspaceId;
        this.asyncRequestFactory = asyncRequestFactory;
        this.editorAgent = editorAgent;
        this.localizationConstant = localizationConstant;
        this.parserWorker = parserWorker;
        this.buildContext = buildContext;
        this.appContext = appContext;
        this.presenter = presenter;
        this.dtoUnmarshallerFactory = dtoUnmarshallerFactory;

        editorRegistry.registerDefaultEditor(javaFile, javaEditorProvider);
        fileTypeRegistry.registerFileType(javaFile);

        JavaResources.INSTANCE.css().ensureInjected();

        // add actions in File -> New group
        actionManager.registerAction(localizationConstant.actionNewPackageId(), newPackageAction);
        actionManager.registerAction(localizationConstant.actionNewClassId(), newJavaSourceFileAction);
        DefaultActionGroup newGroup = (DefaultActionGroup)actionManager.getAction(GROUP_FILE_NEW);
        newGroup.addSeparator();
        newGroup.add(newJavaSourceFileAction);
        newGroup.add(newPackageAction);

        // add actions in context menu
        DefaultActionGroup buildContextMenuGroup = (DefaultActionGroup)actionManager.getAction(GROUP_BUILD_CONTEXT_MENU);
        buildContextMenuGroup.addSeparator();
        UpdateDependencyAction dependencyAction = new UpdateDependencyAction(this, appContext, eventLogger, resources, buildContext);
        actionManager.registerAction("updateDependency", dependencyAction);
        buildContextMenuGroup.addAction(dependencyAction);

        DefaultActionGroup buildMenuActionGroup = (DefaultActionGroup)actionManager.getAction(GROUP_BUILD);
        buildMenuActionGroup.add(dependencyAction);

        eventBus.addHandler(ProjectActionEvent.TYPE, new ProjectActionHandler() {
            @Override
            public void onProjectOpened(ProjectActionEvent event) {
                ProjectDescriptor project = event.getProject();
                if ("java".equals(project.getAttributes().get(Constants.LANGUAGE).get(0))) {
                    updateDependencies(project.getPath(), false);
                }
            }

            @Override
            public void onProjectClosed(ProjectActionEvent event) {
            }
        });

        eventBus.addHandler(FileEvent.TYPE, new FileEventHandler() {
            @Override
            public void onFileOperation(FileEvent event) {
                String name = event.getFile().getName();
                if (event.getOperationType() == FileEvent.FileOperation.SAVE && "pom.xml".equals(name)) {
                    updateDependencies(event.getFile().getProject().getPath(), true);
                }
            }
        });
    }

    /** For test use only. */
    public JavaExtension() {
    }

    public static native String getJavaCAPath() /*-{
        try {
            return $wnd.IDE.config.javaCodeAssistant;
        } catch (e) {
            return null;
        }

    }-*/;

    @Inject
    private void registerIcons(IconRegistry iconRegistry, JavaResources resources) {
        // icons for project tree nodes
        iconRegistry.registerIcon(new Icon("java.package", resources.packageIcon()));
        iconRegistry.registerIcon(new Icon("java.sourceFolder", resources.sourceFolder()));
        // icons for project types
        iconRegistry.registerIcon(new Icon("maven.projecttype.big.icon", "java-extension/jar_64.png"));
        // icons for file extensions
        iconRegistry.registerIcon(new Icon("maven/java.file.small.icon", resources.javaFile()));
        iconRegistry.registerIcon(new Icon("maven/xml.file.small.icon", resources.xmlFile()));
        iconRegistry.registerIcon(new Icon("maven/css.file.small.icon", resources.cssFile()));
        iconRegistry.registerIcon(new Icon("maven/js.file.small.icon", resources.jsFile()));
        iconRegistry.registerIcon(new Icon("maven/json.file.small.icon", resources.jsonFile()));
        iconRegistry.registerIcon(new Icon("maven/html.file.small.icon", resources.htmlFile()));
        iconRegistry.registerIcon(new Icon("maven/jsp.file.small.icon", resources.jspFile()));
        iconRegistry.registerIcon(new Icon("maven/gif.file.small.icon", resources.imageIcon()));
        iconRegistry.registerIcon(new Icon("maven/jpg.file.small.icon", resources.imageIcon()));
        iconRegistry.registerIcon(new Icon("maven/png.file.small.icon", resources.imageIcon()));
        // icons for file names
        iconRegistry.registerIcon(new Icon("maven/pom.xml.file.small.icon", resources.maven()));
    }

    public void updateDependencies(final String projectPath, final boolean force) {
        if (updating) {
            needForUpdate = true;
            return;
        }

        final Notification notification = new Notification(localizationConstant.updatingDependencies(), PROGRESS);
        notificationManager.showNotification(notification);

        buildContext.setBuilding(true);
        updating = true;

        // send a first request to launch build process and return build task descriptor
        String urlLaunch = getJavaCAPath() + "/java-name-environment/" + workspaceId + "/update-dependencies-launch-task?projectpath=" + projectPath + "&force=" + force;
        asyncRequestFactory.createGetRequest(urlLaunch, false).send(new AsyncRequestCallback<BuildTaskDescriptor>(
            dtoUnmarshallerFactory.newUnmarshaller(BuildTaskDescriptor.class)) {
            @Override
            protected void onSuccess(BuildTaskDescriptor descriptor) {
                if(descriptor.getStatus() == BuildStatus.SUCCESSFUL){
                    notification.setMessage(localizationConstant.dependenciesSuccessfullyUpdated());
                    notification.setStatus(FINISHED);
                    needForUpdate = false;
                    return;
                }
                presenter.showRunningBuild(descriptor, "[INFO] Update Dependencies started...");

                String urlWaitEnd = getJavaCAPath() + "/java-name-environment/" + workspaceId + "/update-dependencies-wait-build-end?projectpath=" + projectPath;
                // send a second request to be notified when dependencies update is finished
                asyncRequestFactory.createPostRequest(urlWaitEnd, descriptor, true).send(new AsyncRequestCallback<String>(new StringUnmarshaller()) {
                    @Override
                    protected void onSuccess(String result) {
                        updating = false;
                        notification.setMessage(localizationConstant.dependenciesSuccessfullyUpdated());
                        notification.setStatus(FINISHED);
                        buildContext.setBuilding(false);
                        parserWorker.dependenciesUpdated();
                        editorAgent.getOpenedEditors().iterate(new StringMap.IterationCallback<EditorPartPresenter>() {
                            @Override
                            public void onIteration(String s, EditorPartPresenter editorPartPresenter) {
                                if (editorPartPresenter instanceof CodenvyTextEditor) {
                                    CodenvyTextEditor editor = (CodenvyTextEditor)editorPartPresenter;
                                    Reconciler reconciler = editor.getConfiguration().getReconciler(editor.getView());
                                    if (reconciler != null) {
                                        ReconcilingStrategy strategy = reconciler.getReconcilingStrategy(Document.DEFAULT_CONTENT_TYPE);
                                        if (strategy != null && strategy instanceof JavaReconcilerStrategy) {
                                            ((JavaReconcilerStrategy)strategy).parse();
                                        }
                                    }
                                }
                                if (needForUpdate) {
                                    needForUpdate = false;
                                    updateDependencies(projectPath, force);
                                }
                            }
                        });
                    }

                    @Override
                    protected void onFailure(Throwable exception) {
                        updating = false;
                        needForUpdate = false;
                        notification.setMessage(exception.getMessage());
                        notification.setType(ERROR);
                        notification.setStatus(FINISHED);
                        buildContext.setBuilding(false);
                    }
                });
            }

            @Override
            protected void onFailure(Throwable exception) {
                Log.warn(JavaExtension.class, "failed to launch build process and get build task descriptor for " + projectPath);
            }
        });
    }
}
